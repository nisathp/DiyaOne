@file:Suppress("SameReturnValue")

package com.naminfo.ui.main.model

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.R
import com.naminfo.contacts.AbstractAvatarModel
import com.naminfo.contacts.MobionContactsService
import com.naminfo.contacts.PhoneContactNumbers
import com.naminfo.core.CustomImdn
import com.naminfo.core.OfflineInboxRepository
import org.linphone.core.Account
import org.linphone.core.AccountListenerStub
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom
import org.linphone.core.ConsolidatedPresence
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.MessageWaitingIndication
import org.linphone.core.RegistrationState
import org.linphone.core.SecurityLevel
import org.linphone.core.tools.Log
import com.naminfo.utils.AppUtils
import com.naminfo.utils.LinphoneUtils

class AccountModel
    @WorkerThread
    constructor(
    val account: Account,
    private val onMenuClicked: ((model: AccountModel) -> Unit)? = null,
    private val onLoginTimeClickedAction: ((model: AccountModel) -> Unit)? = null
) : AbstractAvatarModel() {
    companion object {
        private const val TAG = "[Account Model]"
        private const val UNREAD_HISTORY_SETTLE_DELAY_MS = 500L
    }

    val identity = account.params.identityAddress?.asStringUriOnly().orEmpty()

    val displayName = MutableLiveData<String>()

    val registrationState = MutableLiveData<RegistrationState>()

    val registrationStateLabel = MutableLiveData<String>()

    val registrationStateSummary = MutableLiveData<String>()

    val loginTime = MutableLiveData<String>()

    val isDefault = MutableLiveData<Boolean>()

    val notificationsCount = MutableLiveData<Int>()

    val showMwi = MutableLiveData<Boolean>()

    val voicemailCount = MutableLiveData<String>()

    private val accountListener = object : AccountListenerStub() {
        @WorkerThread
        override fun onRegistrationStateChanged(
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            if (!isAccountAlive()) return
            Log.i(
                "$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] registration state changed: [$state]($message)"
            )
            update()
            if (state == RegistrationState.Ok) {
                // CoreContext's registration callback owns FCM token persistence. A Linphone
                // pn-prid is a SIP push identifier, not reliably this device's FCM token.
                com.naminfo.DiyaOneApplication.coreContext.fetchAndSyncFcmToken()

                if (com.naminfo.DiyaOneApplication.corePreferences.loginTime.isNullOrEmpty()) {
                    val now = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                    com.naminfo.DiyaOneApplication.corePreferences.loginTime = now
                }
            }
        }

        @WorkerThread
        override fun onMessageWaitingIndicationChanged(
            account: Account,
            mwi: MessageWaitingIndication
        ) {
            if (!isAccountAlive()) return
            Log.i(
                "$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] has received a MWI NOTIFY. ${if (mwi.hasMessageWaiting()) "Message(s) are waiting." else "No message is waiting."}}"
            )
            showMwi.postValue(mwi.hasMessageWaiting())
            for (summary in mwi.summaries) {
                val context = summary.contextClass
                val nbNew = summary.nbNew
                val nbNewUrgent = summary.nbNewUrgent
                val nbOld = summary.nbOld
                val nbOldUrgent = summary.nbOldUrgent
                Log.i(
                    "$TAG [MWI] [$context]: new [$nbNew] urgent ($nbNewUrgent), old [$nbOld] urgent ($nbOldUrgent)"
                )

                voicemailCount.postValue(nbNew.toString())
            }
        }
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onChatRoomRead(core: Core, chatRoom: ChatRoom) {
            scheduleNotificationsCount()
        }

        @WorkerThread
        override fun onMessageRetracted(core: Core, chatRoom: ChatRoom, message: ChatMessage) {
            scheduleNotificationsCount()
        }

        @WorkerThread
        override fun onChatRoomStateChanged(core: Core, chatRoom: ChatRoom, state: ChatRoom.State?) {
            if (state == ChatRoom.State.Deleted) {
                scheduleNotificationsCount()
            }
        }

        @WorkerThread
        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage>
        ) {
            val visibleMessages = messages.filter {
                it != null && !CustomImdn.isHiddenControlMessage(it)
            }.toTypedArray()

            if (visibleMessages.isEmpty()) return
            scheduleNotificationsCount()
        }

        @WorkerThread
        override fun onCallStateChanged(
            core: Core,
            call: org.linphone.core.Call,
            state: org.linphone.core.Call.State?,
            message: String
        ) {
            if (state == org.linphone.core.Call.State.End || state == org.linphone.core.Call.State.Error) {
                scheduleNotificationsCount()
            }
        }
    }

    @Volatile
    private var isDestroyed = false

    private val contactsListener = object : com.naminfo.contacts.ContactsManager.ContactsListener {
        override fun onContactsLoaded() {
            coreContext.postOnCoreThread {
                update()
            }
        }

        override fun onContactFoundInRemoteDirectory(friend: org.linphone.core.Friend) {
        }
    }

    init {
        account.addListener(accountListener)
        coreContext.core.addListener(coreListener)
        coreContext.contactsManager.addListener(contactsListener)

        isDefault.postValue(false)
        presenceStatus.postValue(ConsolidatedPresence.Offline)
        showMwi.postValue(false)
        voicemailCount.postValue("")

        update()
    }

    private fun isAccountAlive(): Boolean =
        !isDestroyed && coreContext.isReady() && coreContext.core.accountList.any { it == account }

    @WorkerThread
    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        coreContext.contactsManager.removeListener(contactsListener)
        if (coreContext.isReady()) {
            coreContext.core.removeListener(coreListener)
        }
        account.removeListener(accountListener)
    }

    @UiThread
    fun setAsDefault() {
        coreContext.postOnCoreThread { core ->
            if (core.defaultAccount != account) {
                core.defaultAccount = account

                for (friendList in core.friendsLists) {
                    if (friendList.isSubscriptionsEnabled) {
                        Log.i(
                            "$TAG Default account has changed, refreshing friend list [${friendList.displayName}] subscriptions"
                        )
                        // friendList.updateSubscriptions() won't trigger a refresh unless a friend has changed
                        friendList.isSubscriptionsEnabled = false
                        friendList.isSubscriptionsEnabled = true
                    }
                }
            }
        }

        isDefault.value = true
    }

    @UiThread
    fun openMenu() {
        onMenuClicked?.invoke(this)
    }

    @UiThread
    fun onLoginTimeClicked() {
        onLoginTimeClickedAction?.invoke(this)
    }

    @UiThread
    fun refreshRegister() {
        coreContext.postOnCoreThread { core ->
            core.refreshRegisters()
        }
    }

    @UiThread
    fun callVoicemailUri() {
        coreContext.postOnCoreThread {
            if (!isAccountAlive()) return@postOnCoreThread
            val voicemail = account.params.voicemailAddress
            if (voicemail != null) {
                Log.i("$TAG Calling voicemail address [${voicemail.asStringUriOnly()}]")
                coreContext.startAudioCall(voicemail)
            }
        }
    }

    @WorkerThread
    fun computeNotificationsCount() {
        if (!isAccountAlive()) return
        val accountRooms = if (coreContext.core.accountList.size <= 1) {
            coreContext.core.chatRooms.asIterable()
        } else {
            coreContext.core.chatRooms.filter {
                LinphoneUtils.getAccountForAddress(it.localAddress) == account
            }
        }
        val nativeUnreadIds = CustomImdn.visibleUnreadMessageIds(accountRooms)
        val unreadMessages = CustomImdn.visibleUnreadMessagesCount(accountRooms) +
            OfflineInboxRepository.unreadStoredItemsCountForRecipient(
                account.params.identityAddress?.username,
                nativeUnreadIds
            )
        notificationsCount.postValue(unreadMessages + account.missedCallsCount)
    }

    @WorkerThread
    private fun scheduleNotificationsCount() {
        // Native Core callbacks hold its lock. Recompute after the callback returns so
        // unread-history inspection cannot race with navigation or message delivery.
        coreContext.postOnCoreThread { computeNotificationsCount() }
        coreContext.postOnCoreThreadDelayed(
            { computeNotificationsCount() },
            UNREAD_HISTORY_SETTLE_DELAY_MS
        )
    }

    @WorkerThread
    fun updateRegistrationState() {
        if (!isAccountAlive()) return
        val state = if (account.state == RegistrationState.None) {
            // If the account has been disabled manually, use the Cleared status instead of None
            if (!account.params.isRegisterEnabled) {
                Log.w(
                    "$TAG Account real registration state is None but using Cleared instead as it was manually disabled by the user"
                )
                RegistrationState.Cleared
            } else {
                account.state
            }
        } else {
            account.state
        }
        registrationState.postValue(state)

        val label = when (state) {
            RegistrationState.Cleared -> {
                AppUtils.getString(
                    R.string.drawer_menu_account_connection_status_cleared
                )
            }
            RegistrationState.Progress -> AppUtils.getString(
                R.string.drawer_menu_account_connection_status_progress
            )
            RegistrationState.Failed -> {
                AppUtils.getString(
                    R.string.drawer_menu_account_connection_status_failed
                )
            }
            RegistrationState.Ok -> {
                AppUtils.getString(
                    R.string.drawer_menu_account_connection_status_connected
                )
            }
            RegistrationState.None -> {
                AppUtils.getString(
                    R.string.drawer_menu_account_connection_status_disconnected
                )
            }
            RegistrationState.Refreshing -> AppUtils.getString(
                R.string.drawer_menu_account_connection_status_refreshing
            )
            else -> "$state"
        }
        registrationStateLabel.postValue(label)
        Log.i("$TAG Account registration state is [$state]")

        val summary = when (state) {
            RegistrationState.Cleared -> AppUtils.getString(
                R.string.manage_account_status_cleared_summary
            )
            RegistrationState.Refreshing, RegistrationState.Progress -> AppUtils.getString(
                R.string.manage_account_status_progress_summary
            )
            RegistrationState.Failed -> AppUtils.getString(
                R.string.manage_account_status_failed_summary
            )
            RegistrationState.Ok -> AppUtils.getString(
                R.string.manage_account_status_connected_summary
            )
            RegistrationState.None -> AppUtils.getString(
                R.string.manage_account_status_disconnected_summary
            )
            else -> "$state"
        }
        registrationStateSummary.postValue(summary)
    }

    @WorkerThread
    private fun update() {
        if (!isAccountAlive()) return
        Log.i(
            "$TAG Refreshing info for account [${account.params.identityAddress?.asStringUriOnly()}]"
        )

        trust.postValue(SecurityLevel.EndToEndEncryptedAndVerified)
        showTrust.postValue(isEndToEndEncryptionMandatory())

        // The drawer is the signed-in user's profile area. Some OEMs (notably Vivo) don't
        // expose the user's own SIM number as a native contact, so do not make the profile name
        // depend on the handset address book. Use the signed-in SIP/profile label and the cached
        // authenticated directory before falling back to the number.
        val identityAddress = account.params.identityAddress
        val phoneNumber = identityAddress?.username.orEmpty()
        val profileName = identityAddress?.displayName?.trim()?.takeIf {
            it.isNotBlank() && !PhoneContactNumbers.isNumericPlaceholder(it, phoneNumber)
        }
        val name = profileName
            ?: MobionContactsService.findCachedContactName(phoneNumber)
            ?: identityAddress?.let(coreContext.contactsManager::findSavedNativeName)
            ?: phoneNumber.ifBlank { LinphoneUtils.getDisplayName(identityAddress) }
        displayName.postValue(name)

        initials.postValue(AppUtils.getInitials(name))

        val pictureUri = account.params.pictureUri.orEmpty()
        if (pictureUri != picturePath.value.orEmpty()) {
            picturePath.postValue(pictureUri)
            Log.d("$TAG Account picture URI is [$pictureUri]")
        }

        isDefault.postValue(coreContext.core.defaultAccount == account)
        computeNotificationsCount()

        updateRegistrationState()

        val savedLoginTime = com.naminfo.DiyaOneApplication.corePreferences.loginTime.orEmpty()
        if (savedLoginTime.isNotEmpty()) {
            loginTime.postValue("Login: $savedLoginTime")
        } else {
            loginTime.postValue("")
        }
    }
}

@WorkerThread
fun isEndToEndEncryptionMandatory(): Boolean {
    return false // TODO: Will be done later in SDK
}
