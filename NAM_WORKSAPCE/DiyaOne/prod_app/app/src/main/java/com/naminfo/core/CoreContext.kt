package com.naminfo.core

import org.linphone.core.*

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Context.SENSOR_SERVICE
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlin.system.exitProcess
import com.naminfo.BuildConfig
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.compatibility.Compatibility
import com.naminfo.contacts.ContactsManager
import org.linphone.core.tools.Log
import com.naminfo.notifications.NotificationsManager
import com.naminfo.telecom.TelecomManager
import com.naminfo.ui.call.CallActivity
import com.naminfo.utils.ActivityMonitor
import com.naminfo.utils.AppUtils
import com.naminfo.utils.AudioUtils
import com.naminfo.utils.Event
import com.naminfo.utils.FileUtils
import com.naminfo.utils.LinphoneUtils
import com.naminfo.ui.main.history.model.TranslationCallHistoryMetadata
import com.naminfo.translation.CallLanguageSyncApi
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class CoreContext
    @UiThread
    constructor(val context: Context) : HandlerThread("Core Thread") {
    companion object {
        private const val TAG = "[Core Context]"
    }

    lateinit var core: Core

    @Volatile
    var pendingWakeCallId: String? = null

    @Volatile
    var pendingWakeReadyToken: String? = null

    private val pendingWakeAckLock = Any()
    private var pendingWakeAckInProgressFor: String? = null

    fun isCoreAvailable(): Boolean {
        return ::core.isInitialized
    }

    val contactsManager = ContactsManager()

    val notificationsManager = NotificationsManager(context)

    val telecomManager = TelecomManager(context)

    @get:AnyThread
    val sdkVersion: String by lazy {
        val sdkVersion = context.getString(R.string.linphone_sdk_version)
        val sdkBranch = context.getString(R.string.linphone_sdk_branch)
        val sdkBuildType = org.linphone.core.BuildConfig.BUILD_TYPE
        "$sdkVersion ($sdkBranch, $sdkBuildType)"
    }

    private val activityMonitor = ActivityMonitor()

    private val mainThread = Handler(Looper.getMainLooper())

    var defaultAccountHasVideoConferenceFactoryUri: Boolean = false

    var bearerAuthInfoPendingPasswordUpdate: AuthInfo? = null
    var digestAuthInfoPendingPasswordUpdate: AuthInfo? = null

    var isConnectedToAndroidAuto: Boolean = false

    val bearerAuthenticationRequestedEvent: MutableLiveData<Event<Pair<String, String?>>> by lazy {
        MutableLiveData()
    }

    val digestAuthenticationRequestedEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val clearAuthenticationRequestDialogEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val refreshMicrophoneMuteStateEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val showGreenToastEvent: MutableLiveData<Event<Pair<Int, Int>>> by lazy {
        MutableLiveData()
    }

    val showRedToastEvent: MutableLiveData<Event<Pair<Int, Int>>> by lazy {
        MutableLiveData()
    }

    val showFormattedRedToastEvent: MutableLiveData<Event<Pair<String, Int>>> by lazy {
        MutableLiveData()
    }

    val provisioningAppliedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val mdmConfigAppliedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val mdmConfigRemovedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    private var filesToExportToNativeMediaGallery = arrayListOf<String>()
    val filesToExportToNativeMediaGalleryEvent: MutableLiveData<Event<List<String>>> by lazy {
        MutableLiveData()
    }

    private var keepAliveServiceStarted = false

    private lateinit var proximityWakeLock: PowerManager.WakeLock

    @SuppressLint("HandlerLeak")
    private lateinit var coreThread: Handler

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        @WorkerThread
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (!addedDevices.isNullOrEmpty()) {
                Log.i("$TAG [${addedDevices.size}] new device(s) have been added:")
                var atLeastOneNewDeviceIsBluetooth = false
                var atLeastOneNewDeviceIsHeadset = false
                for (device in addedDevices) {
                    Log.i(
                        "$TAG Added device [${device.productName}] with ID [${device.id}] and type [${device.type}]"
                    )

                    when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> {
                            atLeastOneNewDeviceIsBluetooth = true
                        }

                        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                            atLeastOneNewDeviceIsHeadset = true
                        }
                        else -> {}
                    }
                }

                Log.i("$TAG Reloading sound devices in 500ms")
                postOnCoreThreadDelayed({
                    Log.i("$TAG Reloading sound devices")
                    core.reloadSoundDevices()

                    if (atLeastOneNewDeviceIsBluetooth && core.callsNb > 0 && corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG It seems a bluetooth device is now available, trying to route audio to it")
                        AudioUtils.routeAudioBluetoothOrHearingAid()
                    } else if (atLeastOneNewDeviceIsHeadset && core.callsNb > 0) {
                        Log.i("$TAG It seems a headset or headphones device is now available, trying to route audio to it")
                        AudioUtils.routeAudioToHeadset()
                    }
                }, 500)
            }
        }

        @WorkerThread
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (!removedDevices.isNullOrEmpty()) {
                Log.i("$TAG [${removedDevices.size}] existing device(s) have been removed")
                for (device in removedDevices) {
                    Log.i(
                        "$TAG Removed device [${device.id}][${device.productName}][${device.type}]"
                    )
                }

                Log.i("$TAG Reloading sound devices in 500ms")
                postOnCoreThreadDelayed({
                    Log.i("$TAG Reloading sound devices")
                    core.reloadSoundDevices()
                }, 500)
            }
        }
    }

    private var previousCallState = Call.State.Idle

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            Log.i("$TAG SIP registration callback state=[$state] message=[$message]")
            if (state != RegistrationState.Ok) return
            if (account == core.defaultAccount) {
                OutgoingMessageRetryManager.onSipRegistrationRestored()
                // Core may start before the user creates the first SIP account.
                // In that case onCoreStarted() has nothing to configure, so
                // enable push once the account has actually registered.
                enablePushNotificationsForConfiguredFreeSwitchAccounts()
                acknowledgePendingWakeCallIfRegistered()
            }
        }

        @WorkerThread
        override fun onDefaultAccountChanged(core: Core, account: Account?) {
            defaultAccountHasVideoConferenceFactoryUri = account?.params?.audioVideoConferenceFactoryAddress != null

            val defaultDomain = corePreferences.defaultDomain
            val isAccountOnDefaultDomain = account?.params?.domain == defaultDomain
            val domainFilter = corePreferences.contactsFilter
            Log.i("$TAG Currently selected filter is [$domainFilter]")

            if (!isAccountOnDefaultDomain && domainFilter == defaultDomain) {
                corePreferences.contactsFilter = "*"
                Log.i(
                    "$TAG New default account isn't on default domain, changing filter to any SIP contacts instead"
                )
            } else if (isAccountOnDefaultDomain && domainFilter != "") {
                corePreferences.contactsFilter = defaultDomain
                Log.i("$TAG New default account is on default domain, using that domain as filter instead of wildcard")
            }
        }

        @WorkerThread
        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage?>
        ) {
            for (message in messages) {
                if (message != null) {
                    if (!CustomComposing.receive(chatRoom, message)) {
                        CustomImdn.receive(chatRoom, message)
                    }
                }
            }

            if (
                corePreferences.makePublicMediaFilesDownloaded &&
                core.maxSizeForAutoDownloadIncomingFiles >= 0
            ) {
                for (message in messages) {
                    // Never do auto media export for ephemeral messages!
                    if (message?.isEphemeral == true) continue

                    for (content in message?.contents.orEmpty()) {
                        if (content.isFile) {
                            val path = content.filePath
                            if (path.isNullOrEmpty()) continue

                            val mime = "${content.type}/${content.subtype}"
                            val mimeType = FileUtils.getMimeType(mime)

                            when (mimeType) {
                                FileUtils.MimeType.Image,
                                FileUtils.MimeType.Video,
                                FileUtils.MimeType.Audio -> {
                                    Log.i(
                                        "$TAG Added file path [$path] " +
                                                "to the list of media to export to native media gallery"
                                    )
                                    filesToExportToNativeMediaGallery.add(path)
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }

            if (filesToExportToNativeMediaGallery.isNotEmpty()) {
                Log.i(
                    "$TAG Creating event with " +
                            "[${filesToExportToNativeMediaGallery.size}] files to export"
                )
                filesToExportToNativeMediaGalleryEvent.postValue(
                    Event(filesToExportToNativeMediaGallery)
                )
            }
        }

        @WorkerThread
        override fun onChatRoomRead(core: Core, chatRoom: ChatRoom) {
            CustomImdn.roomRead(chatRoom)
        }

        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
            Log.i("$TAG Global state changed [$state]")

            if (state == GlobalState.On) {
                // Wait for GlobalState.ON as some settings modification won't be saved
                // in RC file if Core isn't ON
                onCoreStarted()
            } else if (state == GlobalState.Shutdown) {
                onCoreStopped()
            }
        }

        @WorkerThread
        override fun onConfiguringStatus(
            core: Core,
            status: ConfiguringState?,
            message: String?
        ) {
            Log.i("$TAG Configuring state changed [$status], message is [$message]")
            if (status == ConfiguringState.Successful) {
                val accounts = core.accountList
                if (core.defaultAccount == null && accounts.isNotEmpty()) {
                    val firstAccount = accounts.firstOrNull()
                    if (firstAccount != null) {
                        val sipUri = firstAccount.params.identityAddress?.asStringUriOnly()
                        Log.w(
                            "$TAG Default account is null but account list isn't empty, using account [$sipUri] as default"
                        )
                        core.defaultAccount = firstAccount
                    }
                }

                provisioningAppliedEvent.postValue(Event(true))
                corePreferences.firstLaunch = false
                showGreenToastEvent.postValue(
                    Event(
                        Pair(
                            com.naminfo.R.string.remote_provisioning_config_applied_toast,
                            com.naminfo.R.drawable.smiley
                        )
                    )
                )
            } else if (status == ConfiguringState.Failed) {
                showRedToastEvent.postValue(
                    Event(
                        Pair(
                            com.naminfo.R.string.remote_provisioning_config_failed_toast,
                            com.naminfo.R.drawable.warning_circle
                        )
                    )
                )
            }
        }

        @WorkerThread
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            val currentState = call.state
            Log.i(
                "$TAG Call [${call.remoteAddress.asStringUriOnly()}] state changed [$currentState]"
            )
            if (currentState == Call.State.End || currentState == Call.State.Error || currentState == Call.State.Released) {
                val callId = call.callLog.callId ?: call.callLog.refKey
                SipTraceRepository.onCallCompleted(callId)
                CallLanguageSyncApi.clear(callId)
            }
            when (currentState) {
                Call.State.IncomingReceived -> {
                    if (isLinphoneFallbackAddress(call.remoteAddress)) {
                        Log.e(
                            "$TAG Blocking incoming call with Linphone fallback identity " +
                                "[${call.remoteAddress.asStringUriOnly()}]"
                        )
                        call.decline(Reason.Declined)
                        notifyFallbackIdentityBlocked()
                        return
                    }
                    val isTCall = isTranslationCall(call)
                    val isVideo = LinphoneUtils.isVideoEnabled(call)
                    if (isTCall) {
                        TranslationCallHistoryMetadata.markTranslationCall(context, call.callLog.callId ?: call.callLog.refKey)
                    }
                    Log.i("[Call Diagnostics] INCOMING CALL -> Type: [${if (isVideo) "VIDEO" else "AUDIO"}], From: [${call.remoteAddress.asStringUriOnly()}], isTCall: [$isTCall], remoteVideoRequested: [${call.remoteParams?.isVideoEnabled}]")
                    if (corePreferences.autoAnswerEnabled) {
                        val autoAnswerDelay = corePreferences.autoAnswerDelay
                        if (autoAnswerDelay == 0) {
                            Log.w("$TAG Auto answering call immediately")
                            answerCall(call, true)
                        } else {
                            Log.i("$TAG Scheduling auto answering in $autoAnswerDelay milliseconds")
                            postOnCoreThreadDelayed({
                                Log.w("$TAG Auto answering call")
                                answerCall(call, true)
                            }, autoAnswerDelay.toLong())
                        }
                    }
                }
                Call.State.IncomingEarlyMedia -> {
                    Log.i("[Call Diagnostics] INCOMMING EARLYMEDIA STATE -> IncomingEarlyMedia")

                    if (core.ringDuringIncomingEarlyMedia) {
                        val speaker = core.audioDevices.find {
                            it.type == AudioDevice.Type.Speaker
                        }
                        if (speaker != null) {
                            Log.i("$TAG Ringing during incoming early media enabled, make sure speaker audio device [${speaker.id}] is used")
                            call.outputAudioDevice = speaker
                        } else {
                            Log.w("$TAG No speaker device found, incoming call early media ringing will be played on default device")
                        }
                    }
                }
                Call.State.OutgoingInit -> {
                    Log.i("[Call Diagnostics] OUTGOING STATE -> OutgoingInit (Call initialized)")
                    LinphoneUtils.rememberOutgoingCallMedia(call)
                    val conferenceInfo = core.findConferenceInformationFromUri(call.remoteAddress)
                    // Do not show outgoing call view for conference calls, wait for connected state
                    if (conferenceInfo == null) {
                        postOnMainThread {
                            showCallActivity()
                        }
                    } else {
                        Log.i(
                            "$TAG Call peer address matches known conference, delaying in-call UI until Connected state"
                        )
                    }
                }
                Call.State.OutgoingProgress -> {
                    Log.i("[Call Diagnostics] OUTGOING STATE -> OutgoingProgress (Connecting to server / waiting for ringing - Scenario 1)")
                    syncTranslationCallLanguage(call, "caller")
                }
                Call.State.OutgoingRinging -> {
                    Log.i("[Call Diagnostics] OUTGOING STATE -> OutgoingRinging (Received 180 Ringing from server - Scenario 2)")
                    // Retry here if the SIP Call-ID was not available at OutgoingProgress.
                    syncTranslationCallLanguage(call, "caller")
                    if (corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG Trying to route audio to either bluetooth, hearing aid, headphones or headset if available")
                        AudioUtils.routeAudioToAnyConnectedAudioDeviceOtherThanEarpieceAndSpeaker(call)
                    }
                }
                Call.State.OutgoingEarlyMedia -> {
                    Log.i("[Call Diagnostics] OUTGOING STATE -> OutgoingEarlyMedia (Received 183 Early Media with RTP audio - Scenario 3)")
                    if (corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG Trying to route audio to either bluetooth, hearing aid, headphones or headset if available")
                        AudioUtils.routeAudioToAnyConnectedAudioDeviceOtherThanEarpieceAndSpeaker(call)
                    }
                }
                Call.State.Connected -> {
                    if (call.dir == Call.Dir.Incoming) {
                        syncTranslationCallLanguage(call, "attender")
                    }
                    postOnMainThread {
                        showCallActivity()
                    }

                    if (previousCallState == Call.State.IncomingEarlyMedia && core.ringDuringIncomingEarlyMedia && !LinphoneUtils.isVideoEnabled(call)) {
                        Log.i("$TAG Audio call is in Connected state and was in IncomingEarlyMedia before with ring during early media enabled")
                        val earpiece = core.audioDevices.find {
                            it.type == AudioDevice.Type.Earpiece
                        }
                        if (earpiece != null) {
                            Log.i("$TAG Switching audio back to earpiece ([${earpiece.id}])")
                            call.outputAudioDevice = earpiece
                        } else {
                            Log.w("$TAG No earpiece device found")
                        }
                    }

                    if (corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG Call is connected, trying to route audio to either bluetooth, hearing aid, headphones or headset if available")
                        AudioUtils.routeAudioToAnyConnectedAudioDeviceOtherThanEarpieceAndSpeaker(call)
                    }
                }
                Call.State.StreamsRunning -> {
                    // Some incoming calls expose the Linphone SIP Call-ID only
                    // after media starts. Retry with that exact value rather
                    // than ever falling back to an internal refKey.
                    if (call.dir == Call.Dir.Incoming) {
                        syncTranslationCallLanguage(call, "attender")
                    }
                    if (previousCallState == Call.State.Connected) {
                        if (corePreferences.automaticallyStartCallRecording && !call.params.isRecording) {
                            if (call.conference == null) { // TODO: FIXME: Conference recordings are currently disabled
                                Log.i("$TAG Auto record calls is enabled, starting it now")
                                call.startRecording()
                            }
                        }

                        if (core.isInBackground) {
                            // App is in background which means user likely answered the call from the notification
                            // In this case start proximity sensor, otherwise CallActivity will handle it
                            postOnMainThread {
                                Log.i("$TAG App is in background, start proximity sensor")
                                enableProximitySensor(true)
                            }
                        }
                    }
                }
                Call.State.Error -> {
                    val errorInfo = call.errorInfo
                    Log.w(
                        "$TAG Call error reason is [${errorInfo.reason}](${errorInfo.protocolCode}): ${errorInfo.phrase}"
                    )
                    val text = LinphoneUtils.getCallErrorInfoToast(call)
                    showFormattedRedToastEvent.postValue(
                        Event(Pair(text, com.naminfo.R.drawable.warning_circle))
                    )
                }
                else -> {
                }
            }

            previousCallState = currentState
        }

        @WorkerThread
        override fun onTransferStateChanged(core: Core, transfered: Call, state: Call.State) {
            Log.i(
                "$TAG Transferred call [${transfered.remoteAddress.asStringUriOnly()}] state changed [$state]"
            )
            if (state == Call.State.Connected) {
                val icon = com.naminfo.R.drawable.phone_transfer
                showGreenToastEvent.postValue(
                    Event(Pair(com.naminfo.R.string.call_transfer_successful_toast, icon))
                )
            }
        }

        @WorkerThread
        override fun onAudioDevicesListUpdated(core: Core) {
            Log.i("$TAG Available audio devices list was updated")
        }

        @WorkerThread
        override fun onFirstCallStarted(core: Core) {
            Log.i("$TAG First call started")
        }

        @WorkerThread
        override fun onLastCallEnded(core: Core) {
            Log.i("$TAG Last call ended")
            val currentCamera = core.videoDevice
            if (currentCamera != "FrontFacingCamera") {
                val frontFacing = core.videoDevicesList.find { it == "FrontFacingCamera" }
                if (frontFacing == null) {
                    Log.w("$TAG Failed to find [FrontFacingCamera] camera, doing nothing...")
                } else {
                    Log.i("$TAG Last call ended, setting [$frontFacing] as the default one")
                    core.videoDevice = frontFacing
                }
            }

            postOnMainThread {
                Log.i("$TAG Releasing proximity sensor if it was enabled")
                enableProximitySensor(false)
            }
        }

        @WorkerThread
        override fun onAuthenticationRequested(core: Core, authInfo: AuthInfo, method: AuthMethod) {
            when (method) {
                AuthMethod.Bearer -> {
                    if (authInfo.authorizationServer == null) {
                        Log.e(
                            "$TAG Authentication request using Bearer method but authorization server is null!"
                        )
                        core.abortAuthentication(null)
                        return
                    }

                    val serverUrl = authInfo.authorizationServer
                    val username = authInfo.username
                    if (!serverUrl.isNullOrEmpty()) {
                        Log.i(
                            "$TAG Authentication requested method is Bearer, starting Single Sign On activity with server URL [$serverUrl] and username [$username]"
                        )
                        bearerAuthInfoPendingPasswordUpdate = authInfo
                        bearerAuthenticationRequestedEvent.postValue(
                            Event(Pair(serverUrl, username))
                        )
                    } else {
                        Log.e(
                            "$TAG Authentication requested method is Bearer but no authorization server was found in auth info!"
                        )
                        core.abortAuthentication(null)
                    }
                }
                AuthMethod.HttpDigest -> {
                    if (authInfo.username == null || authInfo.domain == null || authInfo.realm == null) {
                        Log.e(
                            "$TAG Authentication request using Digest method but either username [${authInfo.username}], domain [${authInfo.domain}] or realm [${authInfo.realm}] is null!"
                        )
                        return
                    }

                    val accountFound = core.accountList.find {
                        it.params.identityAddress?.username == authInfo.username && it.params.identityAddress?.domain == authInfo.domain
                    }
                    if (accountFound == null) {
                        Log.w(
                            "$TAG Failed to find account matching auth info, aborting auth dialog"
                        )
                        return
                    }

                    // DiyaOne's operator SIP accounts use the 10-digit mobile
                    // number as both the SIP username and password. FreeSWITCH
                    // can request a second Digest auth-info object with its own
                    // realm after REGISTER has started; fill that object here
                    // instead of interrupting a successful sign-in with a dialog.
                    val mobileNumber = authInfo.username.orEmpty()
                    if (mobileNumber.length == 10 && mobileNumber.all(Char::isDigit)) {
                        authInfo.password = mobileNumber
                        core.addAuthInfo(authInfo)
                        Log.i(
                            "$TAG Reused mobile number as SIP password for Digest challenge " +
                                "for account [${authInfo.username}@${authInfo.domain}]"
                        )
                        return
                    }

                    val identity = "${authInfo.username}@${authInfo.domain}"
                    Log.i(
                        "$TAG Authentication requested method is HttpDigest, showing dialog asking user for password for identity [$identity]"
                    )
                    digestAuthInfoPendingPasswordUpdate = authInfo
                    digestAuthenticationRequestedEvent.postValue(Event(identity))
                }
                AuthMethod.Tls -> {
                    Log.w("$TAG Authentication requested method is TLS, not doing anything...")
                }
                else -> {
                    Log.w("$TAG Unexpected authentication request method [$method]")
                }
            }
        }

        @WorkerThread
        override fun onAccountAdded(core: Core, account: Account) {
            // Prevent this trigger when core is stopped/start in remote prov
            if (core.globalState == GlobalState.Off) return

            Log.i(
                "$TAG New account configured: [${account.params.identityAddress?.asStringUriOnly()}]"
            )
            if (!account.params.isPushNotificationAvailable) {
                if (!corePreferences.keepServiceAlive) {
                    Log.w(
                        "$TAG Newly added account (or the whole Core) doesn't support push notifications, enabling keep-alive foreground service..."
                    )
                    corePreferences.keepServiceAlive = true
                }
                startKeepAliveService()
            }
        }

        @WorkerThread
        override fun onAccountRemoved(core: Core, account: Account) {
            Log.i("$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] removed, clearing auth request dialog if needed")
            if (account.findAuthInfo() == digestAuthInfoPendingPasswordUpdate) {
                Log.i("$TAG Removed account matches auth info pending password update, removing dialog")
                clearAuthenticationRequestDialogEvent.postValue(Event(true))
                digestAuthInfoPendingPasswordUpdate = null
            }

            if (core.defaultAccount == null || core.defaultAccount == account) {
                Log.w("$TAG Removed account was the default one, choosing another as default if possible")
                val newDefaultAccount = core.accountList.find {
                    it.params.isRegisterEnabled
                } ?: core.accountList.firstOrNull()
                if (newDefaultAccount == null) {
                    Log.e("$TAG Failed to find a new default account!")
                } else {
                    Log.i("$TAG New default account will be [${newDefaultAccount.params.identityAddress?.asStringUriOnly()}]")
                    // Delay changing default account to allow for other onAccountRemoved listeners to trigger first
                    postOnCoreThread {
                        core.defaultAccount = newDefaultAccount
                    }
                }
            }
        }
    }

    private var logcatEnabled: Boolean = corePreferences.printLogsInLogcat

    private var crashlyticsEnabled: Boolean = corePreferences.sendLogsToCrashlytics
    private var crashlyticsAvailable = BuildConfig.CRASHLYTICS_ENABLED

    private val loggingServiceListener = object : LoggingServiceListenerStub() {
        @WorkerThread
        override fun onLogMessageWritten(
            logService: LoggingService,
            domain: String,
            level: LogLevel,
            message: String
        ) {
            SipTraceRepository.onLogMessageWritten(message)
            FileTransferApiLogger.onNativeLog(message)
            if (logcatEnabled) {
                when (level) {
                    LogLevel.Error -> android.util.Log.e(domain, message)
                    LogLevel.Warning -> android.util.Log.w(domain, message)
                    LogLevel.Message -> android.util.Log.i(domain, message)
                    LogLevel.Fatal -> android.util.Log.wtf(domain, message)
                    else -> android.util.Log.d(domain, message)
                }
            }
            if (crashlyticsAvailable && crashlyticsEnabled) {
                FirebaseCrashlytics.getInstance().log("[$domain] [${level.name}] $message")
            }
        }
    }

    private val proximitySensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(
            sensor: Sensor?,
            accuracy: Int
        ) {
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
                if (event.values[0] == 0f) {
                    Log.i("$TAG Proximity sensor triggered, screen will turn off")
                } else {
                    Log.i("$TAG Proximity sensor released, screen will turn back on")
                }
            }
        }
    }

    init {
        SipTraceRepository.initialize(context)
        (context as Application).registerActivityLifecycleCallbacks(activityMonitor)
    }

    @WorkerThread
    override fun run() {
        Log.i("$TAG Creating Core")
        Looper.prepare()

        if (BuildConfig.CRASHLYTICS_ENABLED) {
            Log.i("$TAG Crashlytics is enabled, registering logging service listener")
            try {
                // The backend setting can otherwise leave reports queued on a device.
                // This explicit opt-in is intentional for this production diagnostic build.
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
            } catch (e: Exception) {
                Log.e("$TAG Failed to instantiate Crashlytics: $e")
                crashlyticsEnabled = false
                crashlyticsAvailable = false
            }
        } else {
            Log.i("$TAG Crashlytics is disabled")
            crashlyticsAvailable = false
        }
        runCatching {
            Factory.instance().loggingService.addListener(loggingServiceListener)
        }.onFailure { error ->
            Log.e("$TAG Failed to attach SIP trace listener: ${error.message}")
        }
        Log.i("=========================================")
        Log.i("==== Linphone-android information dump ====")
        val gitVersion = AppUtils.getString(com.naminfo.R.string.linphone_app_version)
        val gitBranch = AppUtils.getString(com.naminfo.R.string.linphone_app_branch)
        Log.i("VERSION=${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE} ($gitVersion from $gitBranch branch)")
        Log.i("PACKAGE=${BuildConfig.APPLICATION_ID}")
        Log.i("BUILD TYPE=${BuildConfig.BUILD_TYPE}")
        Log.i("=========================================")

        val looper = Looper.myLooper() ?: return
        coreThread = Handler(looper)

        core = Factory.instance().createCoreWithConfig(corePreferences.config, context)
        enableSrtpByDefault()
        // `disable_ringing` only controls the ringtone for incoming calls. The outgoing
        // ringback tone has to be configured separately for the caller to hear it after
        // the remote endpoint starts ringing (SIP 180 Ringing).
        core.ringback = corePreferences.outgoingCallRingbackSound
        Log.i("$TAG Outgoing call ringback configured [${core.ringback}]")
        // Keep outgoing chat messages in Linphone's history and retry them when a
        // broken channel becomes usable again, including queued file transfers.
        core.messageAutomaticResendingDelay = 5
        Log.i("$TAG Automatic outgoing chat-message retry delay set to 5 seconds")
        // Disable automatic outgoing delivery/read/error receipts.
        val imdnPolicy = checkNotNull(core.imNotifPolicy) {
            "Linphone IM notification policy is unavailable"
        }

        imdnPolicy.setSendImdnDelivered(false)
        imdnPolicy.setSendImdnDisplayed(false)
        imdnPolicy.setSendImdnDeliveryError(false)

        Log.i(
            "$TAG [IMDN] Automatic outgoing receipts: " +
                    "delivered=${imdnPolicy.getSendImdnDelivered()}, " +
                    "displayed=${imdnPolicy.getSendImdnDisplayed()}, " +
                    "error=${imdnPolicy.getSendImdnDeliveryError()}"
        )
        core.isAutoIterateEnabled = true
        core.addListener(coreListener)

        defaultAccountHasVideoConferenceFactoryUri = core.defaultAccount?.params?.audioVideoConferenceFactoryAddress != null

        coreThread.postDelayed({
            startCore()
            ManagedConfiguration.applyMdmConfigToCore(context, core)
        }, 50)

        Looper.loop()
    }

    override fun quit(): Boolean {
        destroyCore()
        return super.quit()
    }

    override fun quitSafely(): Boolean {
        destroyCore()
        return super.quitSafely()
    }

    @WorkerThread
    fun startCore() {
        Log.i("$TAG Starting Core")

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, coreThread)

        val accounts = core.accountList
        if (core.defaultAccount == null && accounts.isNotEmpty()) {
            Log.e("$TAG No default account set but accounts list not empty!")
            val firstAccount = accounts.first()
            core.defaultAccount = firstAccount
            Log.w("$TAG Set account [${firstAccount?.params?.identityAddress?.asStringUriOnly()}] as default")
        }
        SipTraceRepository.setLocalIdentity(core.defaultAccount?.params?.identityAddress?.username)

        computeUserAgent()
        val localFileTransferServer = context.getString(com.naminfo.R.string.local_file_transfer_server_url)
        if (localFileTransferServer.isNotBlank()) {
            core.fileTransferServer = localFileTransferServer
            Log.i("$TAG File transfer server configured as [$localFileTransferServer]")
        }
        Log.i("$TAG Core has been configured with user-agent [${core.userAgent}], starting it")
        core.start()
    }

    /** Set the media-encryption default before any account registers or call is created. */
    @WorkerThread
    private fun enableSrtpByDefault() {
        if (core.config.getBool("app", "srtp_default_initialized", false)) {
            Log.i("$TAG Keeping user's selected media encryption [${core.mediaEncryption}]")
            return
        }

        if (!core.isMediaEncryptionSupported(MediaEncryption.SRTP)) {
            Log.e("$TAG SRTP is not supported by this SDK build")
            return
        }

        core.mediaEncryption = MediaEncryption.SRTP
        core.config.setBool("app", "srtp_default_initialized", true)
        Log.i("$TAG Default media encryption set to SDES-SRTP")
    }

    /** Use the FreeSWITCH-compatible SDES-SRTP suite only while SRTP is selected in Settings. */
    @WorkerThread
    private fun applySelectedSrtp(params: CallParams, direction: String) {
        if (core.mediaEncryption != MediaEncryption.SRTP) {
            Log.i("$TAG Keeping selected media encryption [${core.mediaEncryption}] for $direction call")
            return
        }

        params.mediaEncryption = MediaEncryption.SRTP
        params.srtpSuites = arrayOf(SrtpSuite.AESCM128HMACSHA180)
        Log.i("$TAG Offering SDES-SRTP for $direction call")
    }

    @WorkerThread
    fun onCoreStarted() {
        Log.i("$TAG Core started, updating configuration if required")
        enablePushNotificationsForConfiguredFreeSwitchAccounts()
        if (!pendingWakeCallId.isNullOrEmpty()) {
            Log.i("$TAG Processing pending FCM call wake after Core startup")
            core.refreshRegisters()
            // The registration-state listener sends readiness ACK only after
            // Linphone reports the refreshed registration result.
        }
        core.videoCodecPriorityPolicy = CodecPriorityPolicy.Auto

        // Set in the Core the list of directories from which it is allowed to delete a file related to a chat message
        val paths = arrayListOf<String>()
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            paths.add(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath.orEmpty())
            paths.add(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.absolutePath.orEmpty())
            paths.add(context.getExternalFilesDir(Compatibility.getRecordingsDirectory())?.absolutePath.orEmpty())
        }
        paths.add(context.filesDir?.absolutePath.orEmpty())
        val pathsArray = arrayOfNulls<String>(paths.size)
        paths.toArray(pathsArray)
        core.setChatMessageFilesDirectories(pathsArray)
        for (path in paths) {
            Log.i("$TAG Adding path [$path] to list of directories from which Core is allowed to delete files from")
        }

        val currentVersion = BuildConfig.VERSION_CODE
        val oldVersion = corePreferences.linphoneConfigurationVersion
        Log.w("$TAG Current configuration version is [$oldVersion]")

        if (oldVersion < currentVersion) {
            Log.w("$TAG Migrating configuration to [$currentVersion]")

            if (oldVersion < 600000) { // 6.0.0 initial release
                configurationMigration5To6()
            } else if (oldVersion < 600004) { // 6.0.4
                disablePushNotificationsFromThirdPartySipAccounts()
            } else if (oldVersion < 600009) { // 6.0.9
                removePortFromSipIdentity()
            } else if (oldVersion < 602000) { // 6.2.0
                if (!core.isChatMessageFilesDeletionEnabled) {
                    core.isChatMessageFilesDeletionEnabled = true
                    Log.i("$TAG Core is allowed to automatically delete files from previously logged directories when a chat message is deleted")
                }

                val rlsUri = core.config.getString("sip", "rls_uri", "").orEmpty()
                if (rlsUri.isNotEmpty()) {
                    var accountOnDefaultDomainFound = false
                    for (account in core.accountList) {
                        if (account.params.identityAddress?.domain == corePreferences.defaultDomain) {
                            accountOnDefaultDomainFound = true
                            break
                        }
                    }
                    if (!accountOnDefaultDomainFound) {
                        Log.w("$TAG Removing rls_uri from [sip] section & all friend lists to prevent sending SUBSCRIBE")
                        core.config.setString("sip", "rls_uri", "")
                        for (friendList in core.friendsLists) {
                            if (friendList.rlsAddress != null) {
                                Log.i("$TAG Removing RLS URI from friend list [${friendList.displayName}]")
                                friendList.rlsAddress = null
                            }
                        }
                    } else {
                        Log.i("$TAG Keeping RLS URI as an account on the default domain has been found")
                    }
                }
            }

            if (core.logCollectionUploadServerUrl.isNullOrEmpty()) {
                Log.w("$TAG Logs sharing server URL not set, fixing that")
                core.logCollectionUploadServerUrl = "https://files.linphone.org/http-file-transfer-server/hft.php"
            }

            corePreferences.linphoneConfigurationVersion = currentVersion
            Log.w(
                "$TAG Core configuration updated to version [${corePreferences.linphoneConfigurationVersion}]"
            )
        } else {
            Log.i("$TAG No configuration migration required")
        }

        // Remove the old network-country prefix once, including for already signed-in accounts.
        // Later explicit country-code selections on login are retained.
        if (!core.config.getBool("app", "blank_dialing_prefix_migrated", false)) {
            for (account in core.accountList) {
                val params = account.params.clone()
                params.internationalPrefix = ""
                params.internationalPrefixIsoCountryCode = ""
                account.params = params
            }
            core.config.setBool("app", "blank_dialing_prefix_migrated", true)
            core.config.sync()
        }

        if (!core.isFriendListSubscriptionEnabled) {
            Log.i("$TAG Enabling friend-list presence subscriptions")
            core.isFriendListSubscriptionEnabled = true
        }
        for (friendList in core.friendsLists) {
            friendList.updateSubscriptions()
        }

        contactsManager.onCoreStarted(core)
        telecomManager.onCoreStarted(core)
        notificationsManager.onCoreStarted(core, oldVersion < 600000) // Re-create channels when migrating from a non 6.0 version
        Log.i("$TAG Started contacts, telecom & notifications managers")

        if (corePreferences.keepServiceAlive) {
            if (activityMonitor.isInForeground() || corePreferences.autoStart) {
                Log.i("$TAG Keep alive service is enabled and either app is in foreground or auto start is enabled, starting it")
                startKeepAliveService()
            } else {
                Log.w("$TAG Keep alive service is enabled but auto start isn't and app is not in foreground, not starting it")
            }
        }

        val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            Log.w("$TAG PROXIMITY_SCREEN_OFF_WAKE_LOCK isn't supported on this device!")
        } else {
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "${context.packageName};proximity_sensor"
            )
            val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
            val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            val added = sensorManager.registerListener(proximitySensorListener, proximity, SensorManager.SENSOR_DELAY_NORMAL)
            if (!added) {
                Log.e("$TAG Failed to add proximity sensor listener!")
            }
        }
    }

    @WorkerThread
    private fun onCoreStopped() {
        Log.w("$TAG Core is being shut down, notifying managers so they can remove their listeners and do some cleanup if needed")
        val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.unregisterListener(proximitySensorListener)

        contactsManager.onCoreStopped(core)
        telecomManager.onCoreStopped(core)
        notificationsManager.onCoreStopped(core)
    }

    @WorkerThread
    private fun destroyCore() {
        if (!::core.isInitialized) {
            return
        }

        val state = core.globalState
        if (state != GlobalState.On) {
            Log.w("$TAG Core is in state [$state], do not continue destroy process")
            return
        }
        Log.w("$TAG Stopping Core and destroying context related objects")

        postOnMainThread {
            (context as Application).unregisterActivityLifecycleCallbacks(activityMonitor)
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)

        core.stop()

        // It's very unlikely the process will survive until the Core reaches GlobalStateOff sadly
        Log.w("$TAG Core has been shut down")
        exitProcess(0)
    }

    @AnyThread
    fun isReady(): Boolean {
        return ::core.isInitialized
    }

    @AnyThread
    fun postOnCoreThread(
        @WorkerThread lambda: (core: Core) -> Unit
    ) {
        if (::coreThread.isInitialized) {
            coreThread.post {
                lambda.invoke(core)
            }
        } else {
            Log.e("$TAG Core's thread not initialized yet!")
        }
    }

    @AnyThread
    fun postOnCoreThreadDelayed(
        @WorkerThread lambda: (core: Core) -> Unit,
        delay: Long
    ) {
        if (::coreThread.isInitialized) {
            coreThread.postDelayed({
                lambda.invoke(core)
            }, delay)
        } else {
            Log.e("$TAG Core's thread not initialized yet!")
        }
    }

    @AnyThread
    fun postOnCoreThreadWhenAvailableForHeavyTask(@WorkerThread lambda: (core: Core) -> Unit, name: String) {
        postOnCoreThread {
            if (core.callsNb >= 1) {
                Log.i("$TAG At least one call is active, wait until there is no more call before executing lambda [$name] (checking again in 1 sec)")
                coreContext.postOnCoreThreadDelayed({
                    postOnCoreThreadWhenAvailableForHeavyTask(lambda, name)
                }, 1000)
            } else {
                Log.i("$TAG No active call at the moment, executing lambda [$name] right now")
                lambda.invoke(core)
            }
        }
    }

    @AnyThread
    fun postOnMainThread(
        @UiThread lambda: () -> Unit
    ) {
        mainThread.post {
            lambda.invoke()
        }
    }

    @UiThread
    fun onForeground() {
        postOnCoreThread {
            // We can't rely on defaultAccount?.params?.isPublishEnabled
            // as it will be modified by the SDK when changing the presence status
            if (corePreferences.publishPresence) {
                Log.i("$TAG App is in foreground, PUBLISHING presence as Online")
                core.consolidatedPresence = ConsolidatedPresence.Online
            }

            if (corePreferences.keepServiceAlive && !keepAliveServiceStarted) {
                startKeepAliveService()
            }
        }
    }

    @UiThread
    fun onBackground() {
        postOnCoreThread {
            // We can't rely on defaultAccount?.params?.isPublishEnabled
            // as it will be modified by the SDK when changing the presence status
            if (corePreferences.publishPresence) {
                Log.i("$TAG App is in background, un-PUBLISHING presence info")
                // We don't use ConsolidatedPresence.Busy but Offline to do an unsubscribe,
                // Flexisip will handle the Busy status depending on other devices
                core.consolidatedPresence = ConsolidatedPresence.Offline
            }
        }
    }

    @WorkerThread
    fun updateAuthInfo(password: String) {
        val authInfo = digestAuthInfoPendingPasswordUpdate
        if (authInfo != null) {
            Log.i(
                "$TAG Updating password for username [${authInfo.username}] using auth info [$authInfo]"
            )
            authInfo.password = password
            core.addAuthInfo(authInfo)
            digestAuthInfoPendingPasswordUpdate = null
            core.refreshRegisters()
        } else {
            Log.e("$TAG No pending auth info for digest authentication!")
        }
    }

    @AnyThread
    fun abortBearerAuthIfAny() {
        coreContext.postOnCoreThread { core ->
            if (bearerAuthInfoPendingPasswordUpdate != null) {
                Log.e("$TAG Aborting bearer authentication")
                core.abortAuthentication(bearerAuthInfoPendingPasswordUpdate)
            }
        }
    }

    @WorkerThread
    fun isAddressMyself(address: Address): Boolean {
        val found = core.accountList.find {
            it.params.identityAddress?.weakEqual(address) == true
        }
        return found != null
    }

    @WorkerThread
    fun clearFilesToExportToNativeGallery() {
        filesToExportToNativeMediaGallery.clear()
    }

    @WorkerThread
    fun startAudioCall(
        address: Address,
        forceZRTP: Boolean = false,
        localAddress: Address? = null,
        skipNetworkReachabilityTest: Boolean = false
    ) {
        val params = core.createCallParams(null)
        params?.isVideoEnabled = false
        params?.videoDirection = MediaDirection.Inactive
        startCall(address, params, forceZRTP, localAddress, skipNetworkReachabilityTest)
    }

    @WorkerThread
    fun startVideoCall(
        address: Address,
        forceZRTP: Boolean = false,
        localAddress: Address? = null,
        skipNetworkReachabilityTest: Boolean = false
    ) {
        val params = core.createCallParams(null)
        if (params == null) {
            Log.e("$TAG Unable to create video call parameters; refusing to silently downgrade to audio")
            return
        }
        params.isVideoEnabled = true
        params.videoDirection = MediaDirection.SendRecv
        startCall(address, params, forceZRTP, localAddress, skipNetworkReachabilityTest)
    }

    @WorkerThread
    fun startCall(
        address: Address,
        callParams: CallParams? = null,
        forceZRTP: Boolean = false,
        localAddress: Address? = null,
        skipNetworkReachabilityTest: Boolean = false
    ) {
        if (isLinphoneFallbackAddress(address)) {
            Log.e("$TAG Refusing outgoing call to Linphone fallback identity [${address.asStringUriOnly()}]")
            notifyFallbackIdentityBlocked()
            return
        }
        val isTranslationCallRequest = isTranslationCallUsername(address.username.orEmpty())
        if (isTranslationCallRequest && corePreferences.callLanguage.code.isNullOrBlank()) {
            Log.e("$TAG Can't start a T Call without a selected call language")
            return
        }

        if (!skipNetworkReachabilityTest && !core.isNetworkReachable) {
            Log.e("$TAG Network unreachable, abort outgoing call")
            return
        }

        val currentCall = core.currentCall
        if (currentCall != null) {
            Log.w(
                "$TAG Found current call [${currentCall.remoteAddress.asStringUriOnly()}], pausing it first"
            )
            currentCall.pause()
        }

        val params = callParams ?: core.createCallParams(null)
        if (params == null) {
            Log.e("$TAG Unable to create call parameters, aborting call")
            return
        }

        if (forceZRTP) {
            params.mediaEncryption = MediaEncryption.ZRTP
        }

        if (localAddress != null) {
            val account = core.accountList.find { account ->
                account.params.identityAddress?.weakEqual(localAddress) == true
            }
            if (account != null) {
                params.account = account
                Log.i(
                    "$TAG Using account matching address ${localAddress.asStringUriOnly()} as From"
                )
            } else {
                val defaultAccount = core.defaultAccount
                params.account = defaultAccount
                Log.e(
                    "$TAG Failed to find account matching address ${localAddress.asStringUriOnly()}, using default one [${defaultAccount?.params?.identityAddress?.asStringUriOnly()}]"
                )
            }
        } else {
            val defaultAccount = core.defaultAccount
            params.account = defaultAccount
            Log.i("$TAG No local address given, using default account [${defaultAccount?.params?.identityAddress?.asStringUriOnly()}]")
        }

        // Never send an anonymous fallback INVITE. Without an active account
        // liblinphone uses sip:linphone.android@<local-ip> as the From header,
        // which FreeSWITCH cannot associate with the signed-in subscriber.
        val outgoingAccount = params.account
        if (outgoingAccount == null) {
            Log.e("$TAG No SIP account is available; refusing to send an anonymous outgoing INVITE")
            notifyFallbackIdentityBlocked()
            return
        }
        if (isLinphoneFallbackAddress(outgoingAccount.params.identityAddress)) {
            Log.e(
                "$TAG Refusing outgoing call because configured account uses a Linphone fallback identity " +
                    "[${outgoingAccount.params.identityAddress?.asStringUriOnly()}]"
            )
            notifyFallbackIdentityBlocked()
            return
        }
        if (outgoingAccount.state != RegistrationState.Ok) {
            Log.e(
                "$TAG SIP account [${outgoingAccount.params.identityAddress?.asStringUriOnly()}] " +
                    "is not registered (state=${outgoingAccount.state}); refusing outgoing INVITE"
            )
            return
        }

        // Cached contacts, call logs and chat rooms may still contain the old Linphone domain.
        // Resolve it here so every audio/video call entry point uses the configured SIP server.
        val destination = if (address.domain.equals("sip.linphone.org", ignoreCase = true)) {
            val accountDomain = params.account?.params?.identityAddress?.domain
            val serverDomain = accountDomain?.takeIf {
                it.isNotBlank() && !it.equals("sip.linphone.org", ignoreCase = true)
            } ?: corePreferences.thirdPartySipAccountDefaultDomain
                .removePrefix("sips:").removePrefix("sip:").substringBefore(":")
            if (serverDomain.isBlank() || serverDomain.equals("sip.linphone.org", ignoreCase = true)) {
                Log.e("$TAG No configured third-party server available, aborting legacy-domain call")
                return
            }
            address.clone().apply { domain = serverDomain }
        } else {
            address
        }
        // SDES-SRTP is the app default. Keep an explicit ZRTP request intact, but otherwise
        // offer the same SRTP suite for every outgoing SIP call.
        if (!forceZRTP) {
            applySelectedSrtp(params, "outgoing")
        }
        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(destination)
        Log.i(
            "$TAG Outgoing media offer: videoRequested=${params.isVideoEnabled}, " +
                "videoDirection=${params.videoDirection}, coreVideoEnabled=${core.isVideoEnabled}, " +
                "camera=${core.videoDevice}, encryption=${params.mediaEncryption}"
        )
        val isTCall = isTranslationCallUsername(destination.username.orEmpty())
        val callType = if (params.isVideoEnabled) "VIDEO" else "AUDIO"
        Log.i("[Call Diagnostics] OUTGOING CALL -> Type: [$callType], Target: [${destination.asStringUriOnly()}], isTCall: [$isTCall]")
        if (isTCall) addTCallLanguageHeaders(params)

        core.inviteAddressWithParams(destination, params)
        Log.i("$TAG Starting call to [${destination.asStringUriOnly()}]")
    }

    @WorkerThread
    private fun isLinphoneFallbackAddress(address: Address?): Boolean {
        // The domain can be sip.linphone.org or a local IPv4/IPv6 address,
        // depending on which fallback path liblinphone took. The username is
        // the stable invalid marker in both cases.
        return address?.username.equals("linphone.android", ignoreCase = true)
    }

    @AnyThread
    private fun notifyFallbackIdentityBlocked() {
        showRedToastEvent.postValue(
            Event(
                Pair(
                    com.naminfo.R.string.call_blocked_invalid_fallback_identity,
                    com.naminfo.R.drawable.warning_circle
                )
            )
        )
    }

    /** A current RegistrationState.Ok is valid readiness even if refresh emits no new callback. */
    fun acknowledgePendingWakeCallIfRegistered() {
        if (!isCoreAvailable() || core.defaultAccount?.state != RegistrationState.Ok) return

        acknowledgePendingWakeCall()
    }

    private fun acknowledgePendingWakeCall() {
        val callId = pendingWakeCallId ?: return
        val readyToken = pendingWakeReadyToken ?: return
        synchronized(pendingWakeAckLock) {
            if (pendingWakeAckInProgressFor == callId) return
            pendingWakeAckInProgressFor = callId
        }

        val startedAt = android.os.SystemClock.elapsedRealtime()
        Log.i("$TAG RegistrationState.Ok confirmed for FCM wake call [$callId]; acknowledging Vercel API")
        Thread {
            var acknowledged = false
            repeat(3) { attempt ->
                if (acknowledged) return@repeat
                var conn: HttpURLConnection? = null
                try {
                    conn = URL("https://androidpushnotification.vercel.app/api/call-ready")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    conn.doOutput = true
                    val body = JSONObject().apply {
                        put("callId", callId)
                        put("readyToken", readyToken)
                    }.toString()
                    val logId = ApiLogRepository.begin("POST", "https://androidpushnotification.vercel.app/api/call-ready", body)
                    OutputStreamWriter(conn.outputStream).use { it.write(body) }
                    val responseCode = conn.responseCode
                    val response = (if (responseCode in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }
                    ApiLogRepository.complete(logId, responseCode, response)
                    acknowledged = responseCode in 200..299
                    Log.i(
                        "$TAG Call-ready HTTP response for [$callId], attempt=[${attempt + 1}], " +
                                "status=[$responseCode], elapsedMs=[${android.os.SystemClock.elapsedRealtime() - startedAt}]"
                    )
                } catch (e: Exception) {
                    ApiLogRepository.begin("POST", "https://androidpushnotification.vercel.app/api/call-ready")
                        .also { ApiLogRepository.fail(it, e) }
                    Log.w("$TAG Call-ready ACK attempt ${attempt + 1} failed: ${e.message}")
                } finally {
                    conn?.disconnect()
                }
                if (!acknowledged && attempt < 2) Thread.sleep(350)
            }

            if (acknowledged) {
                if (pendingWakeCallId == callId) {
                    pendingWakeCallId = null
                    pendingWakeReadyToken = null
                }
                Log.i("$TAG Vercel API acknowledged SIP readiness for call [$callId] in [${android.os.SystemClock.elapsedRealtime() - startedAt}ms]")
            } else {
                Log.w("$TAG Could not acknowledge SIP readiness for call [$callId]; FreeSWITCH will fail this wake call closed")
            }
            synchronized(pendingWakeAckLock) {
                if (pendingWakeAckInProgressFor == callId) pendingWakeAckInProgressFor = null
            }
        }.start()
    }

    fun saveTokenToFirestoreBackend(phoneNumber: String, fcmToken: String) {
        if (phoneNumber.isBlank() || fcmToken.isBlank()) return
        Thread {
            try {
                val url = URL("https://androidpushnotification.vercel.app/api/save-token")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-signature", "015a3e11e51129f1e74326b993b508103506093f884b4f66e94404d334473820")
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doOutput = true

                val jsonBody = JSONObject().apply {
                    put("phoneNumber", phoneNumber)
                    put("fcmToken", fcmToken.trim())
                }
                val logId = ApiLogRepository.begin("POST", url.toString(), jsonBody.toString())

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(jsonBody.toString())
                    writer.flush()
                }

                val code = conn.responseCode
                val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
                ApiLogRepository.complete(logId, code, response)
                Log.i("$TAG Token auto-save to Firestore backend for [$phoneNumber] status: $code")
            } catch (e: Exception) {
                ApiLogRepository.begin("POST", "https://androidpushnotification.vercel.app/api/save-token")
                    .also { ApiLogRepository.fail(it, e) }
                Log.e("$TAG Error saving token to Firestore backend: $e")
            }
        }.start()
    }

    fun fetchAndSyncFcmToken() {
        val username = core.defaultAccount?.params?.identityAddress?.username.orEmpty()
        if (username.isBlank()) return
        val prid = core.defaultAccount?.params?.pushNotificationConfig?.prid.orEmpty()
        if (prid.isNotBlank()) {
            Log.i("$TAG Syncing FCM token from pushNotificationConfig.prid for $username: $prid")
            saveTokenToFirestoreBackend(username, prid)
            return
        }
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful && !task.result.isNullOrBlank()) {
                        val token = task.result
                        Log.i("$TAG Fetched current live FCM token: $token for $username")
                        saveTokenToFirestoreBackend(username, token)
                    } else {
                        Log.e("$TAG Failed to fetch FCM token: ${task.exception}")
                    }
                }
        } catch (e: Exception) {
            Log.e("$TAG Exception fetching FCM token: $e")
        }
    }

    @WorkerThread
    fun switchCamera() {
        val currentDevice = core.videoDevice
        Log.i("$TAG Current camera device is $currentDevice")

        for (camera in core.videoDevicesList) {
            if (camera != currentDevice && camera != "StaticImage: Static picture") {
                Log.i("$TAG New camera device will be $camera")
                core.videoDevice = camera
                break
            }
        }

        val call = core.currentCall
        if (call == null) {
            Log.w("$TAG Switching camera while not in call")
            return
        }
        call.update(null)
    }

    @WorkerThread
    fun showSwitchCameraButton(): Boolean {
        return core.isVideoCaptureEnabled && core.videoDevicesList.size > 2 // Count StaticImage camera
    }

    @WorkerThread
    fun answerCall(call: Call, autoAnswer: Boolean = false) {
        Log.i(
            "$TAG Answering call with remote address [${call.remoteAddress.asStringUriOnly()}] and to address [${call.toAddress.asStringUriOnly()}]"
        )
        val params = core.createCallParams(call)
        if (params == null) {
            Log.w("$TAG Answering call without params!")
            call.accept()
            return
        }

        // Mirror the caller's default on the B-party answer. This ensures the 200 OK offers
        // SDES-SRTP too, rather than relying only on the incoming INVITE's parameters.
        applySelectedSrtp(params, "incoming answer")

        // `Call-ID` itself is generated and owned by the SIP stack.  Do not
        // attempt to overwrite it: send the B-party's actual SIP Call-ID as a
        // separate response header for FreeSWITCH to correlate the B leg.
        val bPartyCallId = call.callLog.callId
        if (!bPartyCallId.isNullOrBlank()) {
            params.addCustomHeader("X-B-CALLID", bPartyCallId)
            Log.i("$TAG Added B-party call ID header to 200 OK: X-B-CALLID=[$bPartyCallId]")
        } else {
            Log.w("$TAG B-party SIP Call-ID isn't available; X-B-CALLID won't be added to 200 OK")
        }

        if (isTranslationCall(call)) {
            addTCallLanguageHeaders(params)
        }

        // Apply response headers to the incoming call before acceptWithParams(),
        // so Linphone includes them in the generated 200 OK.
        call.setParams(params)

        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(call.remoteAddress)

     /*   if (LinphoneUtils.checkIfNetworkHasLowBandwidth(context)) {
            Log.w("$TAG Enabling low bandwidth mode!")
            params.isLowBandwidthEnabled = true
        }*/

        if (!autoAnswer && call.remoteParams?.isVideoEnabled == true &&
            call.remoteParams?.videoDirection != MediaDirection.Inactive
        ) {
            params.isVideoEnabled = true
            params.videoDirection = MediaDirection.SendRecv
        } else if (call.callLog.wasConference()) {
            // Prevent incoming group call to start in audio only layout
            // Do the same as the conference waiting room
            params.isVideoEnabled = true
            params.videoDirection = if (core.videoActivationPolicy.automaticallyInitiate) MediaDirection.SendRecv else MediaDirection.RecvOnly
            Log.i(
                "$TAG Enabling video on call params to prevent audio-only layout when answering"
            )
        } else if (autoAnswer) {
            val videoBothWays = corePreferences.autoAnswerVideoCallsWithVideoDirectionSendReceive
            if (videoBothWays) {
                Log.i("$TAG Call is being auto-answered, requesting video in both ways according to user setting")
                params.videoDirection = MediaDirection.SendRecv
            }
        }

        call.acceptWithParams(params)
    }

    @WorkerThread
    private fun isTranslationCallUsername(username: String): Boolean =
        username.startsWith("T_", ignoreCase = true) ||
            username.startsWith("tac_", ignoreCase = true) ||
            username.startsWith("tvc_", ignoreCase = true)

    @WorkerThread
    private fun isTranslationCall(call: Call): Boolean =
        isTranslationCallUsername(call.remoteAddress.username.orEmpty()) ||
            isTranslationCallUsername(call.toAddress.username.orEmpty()) ||
            !call.remoteParams?.getCustomHeader("X-User-Language").isNullOrBlank()

    @WorkerThread
    private fun addTCallLanguageHeaders(params: CallParams) {
        val language = corePreferences.callLanguage.code
        if (language.isNullOrBlank()) {
            Log.w("$TAG T Call language isn't selected; language headers won't be added")
            return
        }

        params.addCustomHeader("X-User-Language", language)
        Log.i("$TAG Added T Call language header: X-User-Language=[$language]")
    }

    /** Sends the active T-call's selected language to the controller. */
    @AnyThread
    fun syncActiveTranslationCallLanguage() {
        postOnCoreThread { core ->
            val call = core.currentCall ?: return@postOnCoreThread
            if (!isTranslationCall(call) || !LinphoneUtils.isCallActive(call.state)) return@postOnCoreThread
            val party = if (call.dir == Call.Dir.Outgoing) "caller" else "attender"
            syncTranslationCallLanguage(call, party)
        }
    }

    /**
     * Sends the attender's language as soon as they confirm it and press Answer
     * for an incoming TCall. The Connected-state callback below remains a
     * safe retry in case the SIP Call-ID was not available yet.
     */
    @AnyThread
    fun syncIncomingTranslationCallLanguageBeforeAnswer(call: Call) {
        postOnCoreThread {
            if (call.dir != Call.Dir.Incoming || !isTranslationCall(call)) return@postOnCoreThread
            syncTranslationCallLanguage(call, "attender")
        }
    }

    @WorkerThread
    private fun syncTranslationCallLanguage(call: Call, party: String) {
        if (!isTranslationCall(call)) return
        val sipCallId = call.callLog.callId
        if (sipCallId.isNullOrBlank()) {
            Log.w("$TAG Skipping language sync: Linphone SIP Call-ID isn't available yet")
            return
        }
        CallLanguageSyncApi.update(
            callId = sipCallId,
            party = party,
            language = corePreferences.callLanguage.code
        )
    }

    @WorkerThread
    fun terminateCall(call: Call) {
        val conference = call.conference
        if (conference != null) {
            Log.i("$TAG Terminating conference [${call.remoteAddress.asStringUriOnly()}]")
            conference.terminate()
        } else {
            if (call.dir == Call.Dir.Incoming && LinphoneUtils.isCallIncoming(call.state)) {
                val reason = if (call.core.callsNb > 1) Reason.Busy else Reason.Declined
                Log.i(
                    "$TAG Declining call [${call.remoteAddress.asStringUriOnly()}] with reason [$reason]"
                )
                call.decline(reason)
            } else {
                Log.i("$TAG Terminating call [${call.remoteAddress.asStringUriOnly()}]")
                call.terminate()
            }
        }
    }

    @UiThread
    fun showCallActivity() {
        Log.i("$TAG Starting Call activity")
        val intent = Intent(context, CallActivity::class.java)
        // This flag is required to start an Activity from a Service context
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        val options = Compatibility.getPendingIntentActivityOptions(true)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options.toBundle()
        )

        val senderOptions = Compatibility.getPendingIntentActivityOptions(false)
        Compatibility.sendPendingIntent(pendingIntent, senderOptions.toBundle())
    }

    @WorkerThread
    fun startKeepAliveService() {
        if (keepAliveServiceStarted) {
            Log.w("$TAG Keep alive service already started, skipping")
            return
        }

        val serviceIntent = Intent(Intent.ACTION_MAIN).setClass(
            context,
            CoreKeepAliveThirdPartyAccountsService::class.java
        )
        Log.i("$TAG Starting Keep alive for third party accounts Service")
        try {
            context.startForegroundService(serviceIntent)
            keepAliveServiceStarted = true
        } catch (e: Exception) {
            Log.e("$TAG Failed to start keep alive service: $e")
        }
    }

    @WorkerThread
    fun onKeepAliveServiceDestroyed() {
        keepAliveServiceStarted = false
    }

    @WorkerThread
    fun stopKeepAliveService() {
        val serviceIntent = Intent(Intent.ACTION_MAIN).setClass(
            context,
            CoreKeepAliveThirdPartyAccountsService::class.java
        )
        Log.i(
            "$TAG Stopping Keep alive for third party accounts Service"
        )
        context.stopService(serviceIntent)
        keepAliveServiceStarted = false
    }

    @WorkerThread
    fun computeUserAgent() {
        val savedDeviceName = corePreferences.deviceName
        val deviceName = if (savedDeviceName.isEmpty()) {
            Log.i("$TAG Device name not fetched yet, doing it now")
            AppUtils.getDeviceName(context)
        } else if (savedDeviceName.contains("'")) {
            // Some VoIP providers such as voip.ms seem to not like apostrophe in user-agent
            // https://github.com/BelledonneCommunications/linphone-android/issues/2287
            Log.i("$TAG Found an apostrophe in device name, removing it")
            savedDeviceName.replace("'", "")
        } else {
            savedDeviceName
        }
        if (savedDeviceName != deviceName) {
            corePreferences.deviceName = deviceName
        }
        Log.i("$TAG Device name for user-agent is [$deviceName]")

        val appName = context.getString(com.naminfo.R.string.app_name)
        val androidVersion = BuildConfig.VERSION_NAME
        val userAgent = "${appName}Android/$androidVersion ($deviceName) LinphoneSDK"
        val sdkVersion = context.getString(R.string.linphone_sdk_version)
        val sdkBranch = context.getString(R.string.linphone_sdk_branch)
        val sdkUserAgent = "$sdkVersion ($sdkBranch)"
        core.setUserAgent(userAgent, sdkUserAgent)
    }

    @WorkerThread
    private fun removePortFromSipIdentity() {
        for (account in core.accountList) {
            val params = account.params
            val identity = params.identityAddress
            if (identity != null && identity.port != 0) {
                val clone = params.clone()
                val newIdentity = identity.clone()
                newIdentity.port = 0
                clone.identityAddress = newIdentity
                Log.w("$TAG Found account with identity address [${identity.asStringUriOnly()}] that contains port information in domain, removing port information in new identity [${newIdentity.asStringUriOnly()}]")
                account.params = clone
            }
        }
    }

    @WorkerThread
    private fun disablePushNotificationsFromThirdPartySipAccounts() {
        for (account in core.accountList) {
            val params = account.params
            val pushAvailableForDomain = params.identityAddress?.domain in corePreferences.pushNotificationCompatibleDomains
            if (!pushAvailableForDomain && params.pushNotificationAllowed) {
                val clone = params.clone()
                clone.pushNotificationAllowed = false
                Log.w("$TAG Updating account [${params.identityAddress?.asStringUriOnly()}] params to disable push notifications, they won't work and may cause issues when used with UDP transport protocol")
                account.params = clone
            }
        }
    }

    @WorkerThread
    private fun configurationMigration5To6() {
        val policy = core.videoActivationPolicy.clone()
        policy.automaticallyInitiate = false
        policy.automaticallyAccept = true
        policy.automaticallyAcceptDirection = MediaDirection.RecvOnly
        core.videoActivationPolicy = policy
        Log.i(
            "$TAG Updated video activation policy to disable auto initiate, enable auto accept with media direction RecvOnly"
        )

        core.isFecEnabled = true
        Log.i("$TAG Video FEC has been enabled")

        core.config.setBool("magic_search", "return_empty_friends", true)
        Log.i("$TAG Showing 'empty' friends enabled")

        if (LinphoneUtils.getDefaultAccount()?.params?.domain == corePreferences.defaultDomain) {
            corePreferences.contactsFilter = corePreferences.defaultDomain
            Log.i(
                "$TAG Setting default contacts list filter to [${corePreferences.contactsFilter}]"
            )
        }

        for (account in core.accountList) {
            val params = account.params
            if (params.identityAddress?.domain == corePreferences.defaultDomain && params.limeAlgo.isNullOrEmpty()) {
                val clone = params.clone()
                clone.limeAlgo = "c25519"
                Log.i("$TAG Updating account [${params.identityAddress?.asStringUriOnly()}] params to use LIME algo c25519")
                account.params = clone
            }
        }

        Log.i("$TAG Making sure both RFC2833 & SIP INFO are enabled for DTMFs")
        core.useRfc2833ForDtmf = true
        core.useInfoForDtmf = true

        // Add that flag back, was disabled for a time during dev process
        Log.i("$TAG Enabling hiding empty chat rooms")
        core.config.setBool("misc", "hide_empty_chat_rooms", true)

        // Replace old URLs by new ones
        if (corePreferences.checkForUpdateServerUrl == "https://www.linphone.org/releases") {
            corePreferences.checkForUpdateServerUrl = "https://download.linphone.org/releases"
        }
        if (core.fileTransferServer == "https://www.linphone.org:444/lft.php") {
            core.fileTransferServer = "https://files.linphone.org/http-file-transfer-server/hft.php"
        }
        if (core.logCollectionUploadServerUrl == "https://www.linphone.org:444/lft.php") {
            core.logCollectionUploadServerUrl = "https://files.linphone.org/http-file-transfer-server/hft.php"
        }

        Log.i("$TAG IMDN threshold set to 1 (meaning only sender will receive delivery & read notifications)")
        core.imdnToEverybodyThreshold = 1

        Log.i("$TAG Removing previous grammar files (without .belr extension)")
        corePreferences.clearPreviousGrammars()
    }

    @WorkerThread
    private fun enablePushNotificationsForConfiguredFreeSwitchAccounts() {
        val supportedDomains = corePreferences.pushNotificationCompatibleDomains.toMutableSet()
        var domainsUpdated = false

        for (account in core.accountList) {
            val domain = account.params.identityAddress?.domain
            if (!domain.isNullOrEmpty() && supportedDomains.add(domain)) {
                domainsUpdated = true
            }
        }

        if (domainsUpdated) {
            corePreferences.pushNotificationCompatibleDomains = supportedDomains.toTypedArray()
        }

        if (!core.isPushNotificationAvailable) {
            Log.w("$TAG Firebase Cloud Messaging isn't available; using the keep-alive service")
        }

        var hasAccount = false
        for (account in core.accountList) {
            val params = account.params
            val domain = params.identityAddress?.domain
            if (domain.isNullOrEmpty()) continue

            hasAccount = true
            if (params.pushNotificationAllowed || !core.isPushNotificationAvailable) continue

            val updatedParams = params.clone()
            updatedParams.pushNotificationAllowed = true
            account.params = updatedParams
            Log.i("$TAG Enabled Firebase push registration for account [${params.identityAddress?.asStringUriOnly()}]")
        }

        if (hasAccount && !corePreferences.keepServiceAlive) {
            corePreferences.keepServiceAlive = true
            startKeepAliveService()
            Log.i("$TAG Enabled foreground keep-alive service for registered accounts")
        }
    }

    @WorkerThread
    fun isCrashlyticsAvailable(): Boolean {
        return crashlyticsAvailable
    }

    @WorkerThread
    fun updateLogcatEnabledSetting(enabled: Boolean) {
        logcatEnabled = enabled
    }

    @WorkerThread
    fun updateCrashlyticsEnabledSetting(enabled: Boolean) {
        crashlyticsEnabled = enabled
    }

    @UiThread
    fun enableProximitySensor(enable: Boolean) {
        if (enable && !corePreferences.useProximitySensor) {
            Log.w("$TAG App tried to enable proximity sensor but it's been disabled in settings, doing nothing")
            return
        }

        if (::proximityWakeLock.isInitialized) {
            if (enable && !proximityWakeLock.isHeld) {
                Log.i("$TAG Acquiring proximity sensor wake lock for 2 hours")
                proximityWakeLock.acquire(7200 * 1000L) // 2 hours
            } else if (!enable && proximityWakeLock.isHeld) {
                Log.i("$TAG Releasing proximity sensor wake lock")
                proximityWakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        }
    }

    fun setBackCamera(): Boolean {
        val list = core.videoDevicesList
        for (camera in list) {
            if (camera.contains("Back")) {
                Log.i("$TAG Found back facing camera [$camera], using it")
                coreContext.core.videoDevice = camera
                return true
            }
        }
        Log.i("$TAG Back camera wasn't found in [${list.size}] detected video devices")
        return false
    }

    fun setFrontCamera(): Boolean {
        val list = core.videoDevicesList
        for (camera in list) {
            if (camera.contains("Front")) {
                Log.i("$TAG Found front facing camera [$camera], using it")
                coreContext.core.videoDevice = camera
                return true
            }
        }
        Log.i("$TAG Front camera wasn't found in [${list.size}] detected video devices")
        return false
    }
}
