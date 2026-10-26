package com.naminfo.ui.main.history.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.R
import org.linphone.core.Address
import org.linphone.core.CallLog
import org.linphone.core.ChatRoom
import org.linphone.core.ChatRoomListenerStub
import org.linphone.core.Conference
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.tools.Log
import com.naminfo.ui.GenericViewModel
import com.naminfo.ui.main.history.model.CallLogHistoryModel
import com.naminfo.ui.main.history.model.CallLogModel
import com.naminfo.utils.AppUtils
import com.naminfo.utils.Event
import com.naminfo.utils.LinphoneUtils
import com.naminfo.contacts.ContactsManager
import org.linphone.core.Friend
import com.naminfo.translation.CallLanguage

class HistoryViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[History ViewModel]"
    }

    val showBackButton = MutableLiveData<Boolean>()

    val callLogModel = MutableLiveData<CallLogModel>()

    val historyCallLogs = MutableLiveData<ArrayList<CallLogHistoryModel>>()

    val chatDisabled = MutableLiveData<Boolean>()

    val videoCallDisabled = MutableLiveData<Boolean>()

    val operationInProgress = MutableLiveData<Boolean>()

    val isConferenceCallLog = MutableLiveData<Boolean>()

    val isChatRoomAvailable = MutableLiveData<Boolean>()

    val hideSipAddresses = MutableLiveData<Boolean>()

    val callLogFoundEvent = MutableLiveData<Event<Boolean>>()

    val goToMeetingConversationEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val goToConversationEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val conferenceToJoinEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val historyDeletedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    private lateinit var address: Address

    private var callLocalAddress: Address? = null

    private var meetingChatRoom: ChatRoom? = null
    private var displayedCallId: String? = null

    private val contactsListener = object : ContactsManager.ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
            val id = displayedCallId ?: return
            coreContext.core.findCallLogFromCallId(id)?.let {
                callLogModel.postValue(CallLogModel(it))
            }
        }

        @WorkerThread
        override fun onContactFoundInRemoteDirectory(friend: Friend) { }
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onCallLogUpdated(core: Core, callLog: CallLog) {
            val peerAddress = callLog.remoteAddress
            val localAddress = callLog.localAddress
            val defaultAccount = LinphoneUtils.getDefaultAccount()
            if (::address.isInitialized && address.weakEqual(peerAddress) && defaultAccount?.params?.identityAddress?.weakEqual(
                    localAddress
                ) == true
            ) {
                Log.i("$TAG A call log was created, updating list")
                computeCallLogs()
            }
        }
    }

    private val chatRoomListener = object : ChatRoomListenerStub() {
        @WorkerThread
        override fun onStateChanged(chatRoom: ChatRoom, newState: ChatRoom.State?) {
            val state = chatRoom.state
            if (state == ChatRoom.State.Instantiated) return

            val id = LinphoneUtils.getConversationId(chatRoom)
            Log.i("$TAG Conversation [$id] (${chatRoom.subjectUtf8}) state changed: [$state]")

            if (state == ChatRoom.State.Created) {
                Log.i("$TAG Conversation [$id] successfully created")
                chatRoom.removeListener(this)
                operationInProgress.postValue(false)
                goToConversationEvent.postValue(Event(LinphoneUtils.getConversationId(chatRoom)))
            } else if (state == ChatRoom.State.CreationFailed) {
                Log.e("$TAG Conversation [$id] creation has failed!")
                chatRoom.removeListener(this)
                operationInProgress.postValue(false)
                showRedToast(R.string.conversation_failed_to_create_toast, R.drawable.warning_circle)
            }
        }
    }

    init {
        coreContext.postOnCoreThread { core ->
            core.addListener(coreListener)
            coreContext.contactsManager.addListener(contactsListener)
            chatDisabled.postValue(corePreferences.disableChat)
            videoCallDisabled.postValue(!core.isVideoEnabled)
            hideSipAddresses.postValue(corePreferences.hideSipAddresses)
        }
    }

    @UiThread
    override fun onCleared() {
        super.onCleared()

        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
            coreContext.contactsManager.removeListener(contactsListener)
        }
    }

    @UiThread
    fun findCallLogByCallId(callId: String) {
        coreContext.postOnCoreThread { core ->
            displayedCallId = callId
            val callLog = core.findCallLogFromCallId(callId)
            if (callLog != null) {
                address = callLog.remoteAddress
                callLocalAddress = callLog.localAddress

                val model = CallLogModel(callLog)
                callLogModel.postValue(model)

                val conference = model.wasConference
                isConferenceCallLog.postValue(conference)
                meetingChatRoom = callLog.chatRoom
                isChatRoomAvailable.postValue(meetingChatRoom != null)
                if (conference) {
                    Log.i(
                        "$TAG Conference call log, chat room is ${ if (meetingChatRoom != null) "available" else "not available"}"
                    )
                }

                computeCallLogs()

                callLogFoundEvent.postValue(Event(true))
            } else {
                callLogFoundEvent.postValue(Event(false))
            }
        }
    }

    @UiThread
    fun deleteHistory() {
        coreContext.postOnCoreThread { core ->
            for (model in historyCallLogs.value.orEmpty()) {
                core.removeCallLog(model.callLog)
            }
            historyDeletedEvent.postValue(Event(true))
        }
    }

    @UiThread
    fun startAudioCall() {
        Log.i("[Call Diagnostics] HISTORY UI -> Action: [Normal Audio Call]")
        coreContext.postOnCoreThread { core ->
            if (!::address.isInitialized) return@postOnCoreThread
            Log.i("[Call Diagnostics] HISTORY UI -> Target: [${address.asStringUriOnly()}]")
            coreContext.startAudioCall(address, localAddress = callLocalAddress)
        }
    }

    @UiThread
    fun startVideoCall() {
        Log.i("[Call Diagnostics] HISTORY UI -> Action: [Normal Video Call]")
        coreContext.postOnCoreThread { core ->
            if (!::address.isInitialized) return@postOnCoreThread
            Log.i("[Call Diagnostics] HISTORY UI -> Target: [${address.asStringUriOnly()}]")
            coreContext.startVideoCall(address, localAddress = callLocalAddress)
        }
    }

    val showTCallTypeDialogEvent = MutableLiveData<Event<Address>>()
    val showTCallLanguageDialogEvent = MutableLiveData<Event<Address>>()

    @UiThread
    fun startTCall() {
        Log.i("[Call Diagnostics] HISTORY UI -> Action: [T Call Button Clicked]")
        coreContext.postOnCoreThread { core ->
            if (!::address.isInitialized) return@postOnCoreThread
            if (corePreferences.callLanguage == CallLanguage.NONE) {
                showTCallLanguageDialogEvent.postValue(Event(address))
            } else {
                startTAudioCall(address)
            }
        }
    }

    @UiThread
    fun selectTCallLanguage(language: CallLanguage, sourceAddress: Address) {
        corePreferences.callLanguage = language
        startTAudioCall(sourceAddress)
    }

    @UiThread
    fun startTAudioCall(tAddress: Address) {
        coreContext.postOnCoreThread {
            val audioAddress = getTranslationCallAddress(tAddress, "tac_")
            Log.i("[Call Diagnostics] HISTORY UI -> Action: [T Audio Call Selected], Target: [${audioAddress.asStringUriOnly()}]")
            coreContext.startAudioCall(audioAddress, localAddress = callLocalAddress)
        }
    }

    @UiThread
    fun startTVideoCall(tAddress: Address) {
        coreContext.postOnCoreThread {
            val videoAddress = getTranslationCallAddress(tAddress, "tvc_")
            Log.i("[Call Diagnostics] HISTORY UI -> Action: [T Video Call Selected], Target: [${videoAddress.asStringUriOnly()}]")
            coreContext.startVideoCall(videoAddress, localAddress = callLocalAddress)
        }
    }

    @WorkerThread
    private fun getTranslationCallAddress(sourceAddress: Address, prefix: String): Address {
        val rawUsername = sourceAddress.username.orEmpty()
        val cleanNumber = rawUsername
            .replace(Regex("^(T_|tac_|tvc_)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\+91[\\s-]*"), "")
            .trim()
        val domain = sourceAddress.domain.takeIf { !it.isNullOrBlank() }
            ?: coreContext.core.defaultAccount?.params?.domain.orEmpty()
        val sipUri = "sip:$prefix$cleanNumber@$domain"
        return coreContext.core.interpretUrl(sipUri, false) ?: sourceAddress
    }

    @UiThread
    fun goToMeetingConversation() {
        coreContext.postOnCoreThread {
            val chatRoom = meetingChatRoom
            if (chatRoom != null) {
                goToMeetingConversationEvent.postValue(Event(LinphoneUtils.getConversationId(chatRoom)))
            } else {
                Log.e("$TAG Failed to find chat room for current call log!")
            }
        }
    }

    @UiThread
    fun goToConversation() {
        coreContext.postOnCoreThread { core ->
            if (!::address.isInitialized) return@postOnCoreThread

            val account = core.defaultAccount
            val localSipUri = account?.params?.identityAddress?.asStringUriOnly()
            if (!localSipUri.isNullOrEmpty()) {
                val remote = address
                val remoteSipUri = remote.asStringUriOnly()
                Log.i(
                    "$TAG Looking for existing conversation between [$localSipUri] and [$remoteSipUri]"
                )

                val params = coreContext.core.createConferenceParams(null)
                params.isChatEnabled = true
                params.isGroupEnabled = false
                params.subject = AppUtils.getString(R.string.conversation_one_to_one_hidden_subject)
                params.account = account

                val chatParams = params.chatParams ?: return@postOnCoreThread
                chatParams.ephemeralLifetime = 0 // Make sure ephemeral is disabled by default

                val sameDomain = remote.domain == corePreferences.defaultDomain && remote.domain == account.params.domain
                if (account.params.instantMessagingEncryptionMandatory && sameDomain) {
                    Log.i(
                        "$TAG Account is in secure mode & domain matches, creating an E2E encrypted conversation"
                    )
                    chatParams.backend = ChatRoom.Backend.FlexisipChat
                    params.securityLevel = Conference.SecurityLevel.EndToEnd
                } else if (!account.params.instantMessagingEncryptionMandatory) {
                    if (LinphoneUtils.isEndToEndEncryptedChatAvailable(core)) {
                        Log.i(
                            "$TAG Account is in interop mode but LIME is available, creating an E2E encrypted conversation"
                        )
                        chatParams.backend = ChatRoom.Backend.FlexisipChat
                        params.securityLevel = Conference.SecurityLevel.EndToEnd
                    } else {
                        Log.i(
                            "$TAG Account is in interop mode but LIME isn't available, creating a SIP simple conversation"
                        )
                        chatParams.backend = ChatRoom.Backend.Basic
                        params.securityLevel = Conference.SecurityLevel.None
                    }
                } else {
                    Log.e(
                        "$TAG Account is in secure mode, can't chat with SIP address of different domain [${remote.asStringUriOnly()}]"
                    )
                    // TODO: show error
                    return@postOnCoreThread
                }

                val participants = arrayOf(remote)
                val localAddress = account.params.identityAddress
                val canonicalRemote = LinphoneUtils.canonicalizeBasicChatAddress(remote, account)
                val existingChatRoom = LinphoneUtils.findExistingOneToOneChatRoom(
                    core, params, localAddress, canonicalRemote
                )
                if (existingChatRoom != null) {
                    Log.i(
                        "$TAG Found existing conversation [${LinphoneUtils.getConversationId(
                            existingChatRoom
                        )}], going to it"
                    )
                    goToConversationEvent.postValue(Event(LinphoneUtils.getConversationId(existingChatRoom)))
                } else {
                    Log.i(
                        "$TAG No existing conversation between [$localSipUri] and [$remoteSipUri] was found, let's create it"
                    )
                    operationInProgress.postValue(true)
                    val chatRoom = core.createChatRoom(params, participants)
                    if (chatRoom != null) {
                        if (chatParams.backend == ChatRoom.Backend.FlexisipChat) {
                            if (chatRoom.state == ChatRoom.State.Created) {
                                val id = LinphoneUtils.getConversationId(chatRoom)
                                Log.i("$TAG 1-1 conversation [$id] has been created")
                                operationInProgress.postValue(false)
                                goToConversationEvent.postValue(Event(LinphoneUtils.getConversationId(chatRoom)))
                            } else {
                                Log.i("$TAG Conversation isn't in Created state yet, wait for it")
                                chatRoom.addListener(chatRoomListener)
                            }
                        } else {
                            val id = LinphoneUtils.getConversationId(chatRoom)
                            Log.i("$TAG Conversation successfully created [$id]")
                            operationInProgress.postValue(false)
                            goToConversationEvent.postValue(Event(LinphoneUtils.getConversationId(chatRoom)))
                        }
                    } else {
                        Log.e(
                            "$TAG Failed to create 1-1 conversation with [${remote.asStringUriOnly()}]!"
                        )
                        operationInProgress.postValue(false)
                        showRedToast(R.string.conversation_failed_to_create_toast, R.drawable.warning_circle)
                    }
                }
            }
        }
    }

    @UiThread
    fun goToMeetingWaitingRoom() {
        coreContext.postOnCoreThread {
            if (!::address.isInitialized) return@postOnCoreThread
            conferenceToJoinEvent.postValue(Event(address.asStringUriOnly()))
        }
    }

    @WorkerThread
    private fun computeCallLogs() {
        val history = arrayListOf<CallLogHistoryModel>()
        val account = LinphoneUtils.getDefaultAccount()
        account ?: return

        val list = account.getCallLogsForAddress(address)
        for (log in list) {
            val historyModel = CallLogHistoryModel(log)
            history.add(historyModel)
        }

        // Required when "unique account displays all call logs from Core" workaround
        // TODO FIXME: remove workaround later
        if (list.isEmpty()) {
            for (log in coreContext.core.callLogs) {
                if (log.remoteAddress.weakEqual(address) &&
                    account.params.identityAddress?.weakEqual(log.localAddress) == true
                ) {
                    val historyModel = CallLogHistoryModel(log)
                    history.add(historyModel)
                }
            }
        }

        Log.i("$TAG Found [${history.size}] call logs")
        historyCallLogs.postValue(history)
    }
}
