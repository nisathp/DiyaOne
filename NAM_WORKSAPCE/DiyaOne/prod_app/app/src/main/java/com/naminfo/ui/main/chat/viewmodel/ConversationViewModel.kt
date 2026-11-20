package com.naminfo.ui.main.chat.viewmodel

import android.net.Uri
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.R
import com.naminfo.contacts.ContactsManager
import org.linphone.core.Address
import org.linphone.core.ChatMessage
import org.linphone.core.ChatMessageReaction
import org.linphone.core.ChatRoom
import org.linphone.core.ChatRoom.HistoryFilter
import org.linphone.core.ChatRoomListenerStub
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.EventLog
import org.linphone.core.Friend
import org.linphone.core.SearchDirection
import org.linphone.core.tools.Log
import com.naminfo.ui.main.chat.model.EventLogModel
import com.naminfo.ui.main.chat.model.FileModel
import com.naminfo.ui.main.chat.model.MessageModel
import com.naminfo.ui.main.contacts.model.ContactAvatarModel
import com.naminfo.utils.Event
import com.naminfo.utils.FileUtils
import com.naminfo.utils.LinphoneUtils
import androidx.core.net.toUri
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.core.CustomComposing
import com.naminfo.core.CustomImdn
import com.naminfo.core.OfflineInboxRepository
import com.naminfo.ui.main.chat.RemoteComposingState
import com.naminfo.translation.ChatTranslationLanguage
import com.naminfo.translation.TranslationRepository
import com.naminfo.utils.AppUtils

class ConversationViewModel
    @UiThread
    constructor() : AbstractConversationViewModel() {
    companion object {
        private const val TAG = "[Conversation ViewModel]"
        private const val MESSAGES_PER_PAGE = 30

        const val MAX_TIME_TO_GROUP_MESSAGES = 60 // 1 minute
        const val ITEMS_TO_LOAD_BEFORE_SEARCH_RESULT = 6
    }

    val showBackButton = MutableLiveData<Boolean>()

    val isCallConversation = MutableLiveData<Boolean>()

    val avatarModel = MutableLiveData<ContactAvatarModel>()

    val isEmpty = MutableLiveData<Boolean>()

    val isMuted = MutableLiveData<Boolean>()

    val isEndToEndEncrypted = MutableLiveData<Boolean>()

    val isEndToEndEncryptionAvailable = MutableLiveData<Boolean>()

    val isGroup = MutableLiveData<Boolean>()

    val subject = MutableLiveData<String>()

    val isReadOnly = MutableLiveData<Boolean>()

    val isDisabledBecauseNotSecured = MutableLiveData<Boolean>()

    val ephemeralLifetime = MutableLiveData<Long>()

    val ephemeralLifeTimeLabel = MutableLiveData<String>()

    val composingLabel = MutableLiveData<String>()

    val composingIcon = MutableLiveData<Int>()

    val searchBarVisible = MutableLiveData<Boolean>()

    val searchFilter = MutableLiveData<String>()

    val searchInProgress = MutableLiveData<Boolean>()

    val canSearchDown = MutableLiveData<Boolean>()

    val canSearchUp = MutableLiveData<Boolean>()

    val itemToScrollTo = MutableLiveData<Int>()

    val isUserScrollingUp = MutableLiveData<Boolean>()

    val unreadMessagesCount = MutableLiveData<Int>()

    val focusSearchBarEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val fileToDisplayEvent: MutableLiveData<Event<FileModel>> by lazy {
        MutableLiveData()
    }

    val sipUriToCallEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val conferenceToJoinEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val openWebBrowserEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val contactToDisplayEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val messageDeletedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val historyClearedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val updateEvents: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val forwardMessageEvent: MutableLiveData<Event<MessageModel>> by lazy {
        MutableLiveData()
    }

    val voiceRecordPlaybackEndedEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    var eventsList = arrayListOf<EventLogModel>()

    var pendingForwardMessage: MessageModel? = null

    private var latestMatch: EventLog? = null

    private var latestMatchModel: MessageModel? = null

    // Callback EventLog wrappers can be invalidated by liblinphone while a Basic SIP room
    // persists several attachments. Never dereference those callback objects directly.
    // Re-read the durable history after the Core has finished its current callback instead.
    private var historyRefreshScheduled = false

    // Basic SIP chat rooms normally notify their own listener, but some
    // FreeSWITCH/SDK timing combinations only emit the Core-level callback.
    // Keep this fallback while the conversation is visible so a received
    // message never needs a screen re-open before it is rendered.
    private var coreListenerRegistered = false

    // Some Basic SIP stacks can accept a MESSAGE yet temporarily fail to write its local
    // event (storage id -1). Keep the live ChatMessage until history contains it, so the
    // user immediately sees incoming and outgoing text/image instead of an empty conversation.
    private val optimisticMessages = linkedMapOf<String, ChatMessage>()

    private val remoteComposingState = RemoteComposingState {
        coreContext.postOnCoreThread { computeComposingLabel() }
    }

    // Queue read acknowledgements are applied to the durable outgoing row, while the currently
    // visible bubble can still be an optimistic Basic-SIP wrapper. Update that live model as
    // well so B reading a message turns A's tick double without requiring a screen reopen.
    private val customReceiptObserver: (String, ChatMessage.State) -> Unit = { messageKey, state ->
        val matched = eventsList
            .asSequence()
            .mapNotNull { it.model as? MessageModel }
            .filter { it.isOutgoing && CustomImdn.key(it.chatMessage) == messageKey }
            .toList()
        if (matched.isNotEmpty()) {
            Log.i("$TAG Refreshing ${matched.size} visible delivery icon(s) for [$messageKey] to [$state]")
            matched.forEach { model ->
                model.statusIcon.postValue(LinphoneUtils.getChatIconResId(state))
                model.isInError.postValue(false)
            }
        }
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onMessagesReceived(
            core: Core,
            room: ChatRoom,
            messages: Array<out ChatMessage?>
        ) {
            if (!isDisplayedRoom(room)) return
            var visibleAdded = false
            for (msg in messages) {
                if (
                    msg == null ||
                    !LinphoneUtils.isDisplayableChatMessage(msg) ||
                    OfflineInboxRepository.isMessageDeletedLocally(room, msg)
                ) {
                    continue
                }
                visibleAdded = true
                val key = messageKey(msg) ?: continue
                optimisticMessages[key] = msg
            }
            unreadMessagesCount.postValue(totalUnreadMessagesCount())
            if (!visibleAdded) return

            Log.i("$TAG Core receive fallback for active conversation, refreshing history")
            // Do not inspect history in this callback: Core owns its native lock here.
            refreshEventsFromHistorySoon()
        }

        @WorkerThread
        override fun onChatRoomRead(core: Core, room: ChatRoom) {
            if (!isDisplayedRoom(room)) return
            unreadMessagesCount.postValue(totalUnreadMessagesCount())
        }
    }

    @WorkerThread
    private fun isDisplayedRoom(candidate: ChatRoom): Boolean {
        if (!isChatRoomInitialized()) return false
        if (candidate == chatRoom) return true

        // Basic SIP's Core callback can allocate a fresh ChatRoom JNI wrapper for
        // the same peer/local addresses (e.g. with or without :5060). Reference
        // equality drops the live update even though both rooms are one conversation.
        val displayedId = LinphoneUtils.getConversationId(chatRoom)
        return (displayedId.isNotBlank() && displayedId == LinphoneUtils.getConversationId(candidate)) ||
            LinphoneUtils.areSameOneToOnePeer(chatRoom, candidate)
    }

    @WorkerThread
    private fun getSiblingChatRooms(): List<ChatRoom> {
        if (!isChatRoomInitialized()) return emptyList()
        return LinphoneUtils.findSiblingOneToOneChatRooms(coreContext.core, chatRoom)
    }

    private val customComposingObserver: (String, String, Boolean) -> Unit = { roomKey, address, active ->
        coreContext.postOnCoreThread {
            if (isChatRoomInitialized() && roomKey == CustomComposing.roomKey(chatRoom)) {
                val parsedAddress = coreContext.core.interpretUrl(address, false)
                val displayName = parsedAddress?.let { LinphoneUtils.getDisplayName(it) }
                    ?.takeIf { it.isNotBlank() } ?: address
                remoteComposingState.update(address, displayName, active)
                computeComposingLabel()
            }
        }
    }

    private val chatRoomListener = object : ChatRoomListenerStub() {
        @WorkerThread
        override fun onNewEvents(chatRoom: ChatRoom, eventLogs: Array<out EventLog>) {
            // This callback includes locally-created outgoing messages. Relying only on
            // onChatMessagesReceived() leaves a race where a freshly sent text or file is
            // already in the Core but is missing from the open conversation UI.
            for (event in eventLogs) {
                val msg = event.chatMessage ?: continue
                if (
                    CustomImdn.isHiddenControlMessage(msg) ||
                    OfflineInboxRepository.isMessageDeletedLocally(chatRoom, msg)
                ) {
                    continue
                }
                val key = messageKey(msg) ?: continue
                optimisticMessages[key] = msg
            }
            if (eventLogs.isNotEmpty()) {
                Log.i("$TAG Received [${eventLogs.size}] new local or remote event(s), refreshing history")
                refreshEventsFromHistorySoon()
            }
        }

        @WorkerThread
        override fun onConferenceJoined(chatRoom: ChatRoom, eventLog: EventLog) {
            Log.i("$TAG Conversation was joined")
            if (LinphoneUtils.isChatRoomAGroup(chatRoom)) {
                addEvents(arrayOf(eventLog))
            }
            computeConversationInfo()

            val messageToForward = pendingForwardMessage
            if (messageToForward != null) {
                Log.i("$TAG Found pending forward message, doing it now")
                forwardMessageEvent.postValue(Event(messageToForward))
                pendingForwardMessage = null
            }
        }

        @WorkerThread
        override fun onConferenceLeft(chatRoom: ChatRoom, eventLog: EventLog) {
            Log.w("$TAG Conversation was left")
            if (LinphoneUtils.isChatRoomAGroup(chatRoom)) {
                addEvents(arrayOf(eventLog))
            }
            isReadOnly.postValue(chatRoom.isReadOnly)
        }

        @WorkerThread
        override fun onChatRoomRead(chatRoom: ChatRoom) {
            unreadMessagesCount.postValue(0)

            // Make sure message models are aware that they were read,
            // required for scroll to bottom or first unread message behaves as expected
            for (eventLog in eventsList.reversed()) {
                if (eventLog.model is MessageModel) {
                    if (!eventLog.model.isRead) {
                        eventLog.model.isRead = true
                    } else {
                        break
                    }
                }
            }
            Log.i("$TAG Conversation was marked as read")
        }

        @WorkerThread
        override fun onChatMessagesReceived(chatRoom: ChatRoom, eventLogs: Array<EventLog>) {
            if (eventLogs.isEmpty()) return
            Log.i("$TAG Received [${eventLogs.size}] new message(s), refreshing history")
            for (event in eventLogs) {
                val msg = event.chatMessage ?: continue
                if (
                    CustomImdn.isHiddenControlMessage(msg) ||
                    OfflineInboxRepository.isMessageDeletedLocally(chatRoom, msg)
                ) {
                    continue
                }
                val key = messageKey(msg) ?: continue
                optimisticMessages[key] = msg
            }
            computeComposingLabel()

            unreadMessagesCount.postValue(totalUnreadMessagesCount())
            refreshEventsFromHistorySoon()
        }

        @WorkerThread
        override fun onNewMessageReaction(
            chatRoom: ChatRoom,
            message: ChatMessage,
            reaction: ChatMessageReaction
        ) {
            Log.i(
                "$TAG A reaction [${reaction.body}] was received from [${reaction.fromAddress.asStringUriOnly()}]"
            )
        }

        @WorkerThread
        override fun onIsComposingReceived(
            chatRoom: ChatRoom,
            remoteAddress: Address,
            isComposing: Boolean
        ) {
            val address = remoteAddress.asStringUriOnly()
            val avatar = coreContext.contactsManager.getContactAvatarModelForAddress(remoteAddress)
            val name = avatar.name.value?.takeIf { it.isNotBlank() }
                ?: LinphoneUtils.getDisplayName(remoteAddress)
            remoteComposingState.update(address, name, isComposing)
            Log.i(
                "$TAG Remote [$address] is ${if (isComposing) "composing" else "no longer composing"}"
            )
            computeComposingLabel()
        }

        @WorkerThread
        override fun onParticipantAdded(chatRoom: ChatRoom, eventLog: EventLog) {
            Log.i("$TAG A participant was added to the conversation")
            addEvents(arrayOf(eventLog))
        }

        @WorkerThread
        override fun onParticipantRemoved(chatRoom: ChatRoom, eventLog: EventLog) {
            Log.i("$TAG A participant was removed from the conversation or has left")
            addEvents(arrayOf(eventLog))
        }

        @WorkerThread
        override fun onParticipantAdminStatusChanged(chatRoom: ChatRoom, eventLog: EventLog) {
            Log.i("$TAG A participant has been granted/removed admin rights")
            addEvents(arrayOf(eventLog))
        }

        @WorkerThread
        override fun onSubjectChanged(chatRoom: ChatRoom, eventLog: EventLog) {
            Log.i("$TAG Conversation subject changed [${chatRoom.subjectUtf8}]")
            addEvents(arrayOf(eventLog))
        }

        @WorkerThread
        override fun onSecurityEvent(chatRoom: ChatRoom, eventLog: EventLog) {
            Log.i("$TAG A security event was triggered")
            addEvents(arrayOf(eventLog))
        }

        @WorkerThread
        override fun onEphemeralEvent(chatRoom: ChatRoom, eventLog: EventLog) {
            Log.i("$TAG Adding new ephemeral event [${eventLog.type}]")
            addEvents(arrayOf(eventLog))

            ephemeralLifetime.postValue(
                if (!chatRoom.isEphemeralEnabled) 0L else chatRoom.ephemeralLifetime
            )
            ephemeralLifeTimeLabel.postValue(
                LinphoneUtils.formatEphemeralExpiration(chatRoom.ephemeralLifetime)
            )
        }

        @WorkerThread
        override fun onEphemeralMessageDeleted(chatRoom: ChatRoom, eventLog: EventLog) {
            val eventsLogs = eventsList
            val message = eventLog.chatMessage
            Log.i("$TAG Message [${message?.messageId}] ephemeral lifetime has expired")

            val found = eventsLogs.find {
                (it.model as? MessageModel)?.chatMessage == message
            }
            if (found != null) {
                val list = arrayListOf<EventLogModel>()
                list.addAll(eventsLogs)

                // Update previous & next messages if needed
                updatePreviousAndNextMessages(list, found)

                Log.i("$TAG Removing message from conversation events list")
                list.remove(found)
                eventsList = list
                updateEvents.postValue(Event(true))
                isEmpty.postValue(eventsList.isEmpty())
            } else {
                Log.e("$TAG Failed to find matching message in conversation events list")
            }
        }

        @WorkerThread
        override fun onMessageRetracted(chatRoom: ChatRoom, message: ChatMessage) {
            updateRepliesUpTo(message)

            if (message.isOutgoing) {
                messageDeletedEvent.postValue(Event(true))
            }

            unreadMessagesCount.postValue(totalUnreadMessagesCount())
        }

        @WorkerThread
        override fun onMessageContentEdited(chatRoom: ChatRoom, message: ChatMessage) {
            updateRepliesUpTo(message)
        }
    }

    private val contactsListener = object : ContactsManager.ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
            Log.i("$TAG Contacts have been (re)loaded, updating list")
            computeParticipantsInfo()

            // Contact avatars are replaced when the contacts cache reloads. Refresh message
            // avatars for one-to-one and group chats so their presence LiveData is not left
            // attached to a detached Friend listener.
            for (event in eventsList) {
                (event.model as? MessageModel)?.updateAvatarModel()
            }
        }

        @WorkerThread
        override fun onContactFoundInRemoteDirectory(friend: Friend) { }
    }

    init {
        coreContext.postOnCoreThread { core ->
            isEndToEndEncryptionAvailable.postValue(LinphoneUtils.isEndToEndEncryptedChatAvailable(core))
            coreContext.contactsManager.addListener(contactsListener)
            coreContext.contactsManager.syncMobionDirectoryAsync()
            CustomComposing.addObserver(customComposingObserver)
            CustomImdn.addObserver(customReceiptObserver)
        }

        searchBarVisible.value = false
        isUserScrollingUp.value = false
        isDisabledBecauseNotSecured.value = false
        searchInProgress.value = false
        canSearchDown.value = false
        canSearchUp.value = false
        itemToScrollTo.value = -1
    }

    @UiThread
    override fun onCleared() {
        super.onCleared()

        coreContext.postOnCoreThread {
            coreContext.contactsManager.removeListener(contactsListener)
            CustomComposing.removeObserver(customComposingObserver)
            CustomImdn.removeObserver(customReceiptObserver)
            remoteComposingState.clear()
            if (coreListenerRegistered) {
                coreContext.core.removeListener(coreListener)
                coreListenerRegistered = false
            }
            if (isChatRoomInitialized()) {
                chatRoom.removeListener(chatRoomListener)
            }
            eventsList.forEach(EventLogModel::destroy)
        }
    }

    @WorkerThread
    override fun beforeNotifyingChatRoomFound(sameOne: Boolean) {
        if (!coreListenerRegistered) {
            coreContext.core.addListener(coreListener)
            coreListenerRegistered = true
        }
        if (!sameOne) {
            Log.i("$TAG Conversation found and not the same as before, configuring it...")
            remoteComposingState.clear()
            chatRoom.addListener(chatRoomListener)
            configureChatRoom()
        } else {
            // Rebuild the list from persistent Core history. A local send can race the
            // initial UI insertion, so reusing the old in-memory list here would keep a
            // message invisible even though it was successfully stored in the database.
            Log.i("$TAG Reopening the same conversation, reloading persistent history")
            computeEvents()
        }
    }

    @UiThread
    fun openSearchBar() {
        canSearchUp.value = true
        searchBarVisible.value = true
        focusSearchBarEvent.value = Event(true)
    }

    @UiThread
    fun closeSearchBar() {
        coreContext.postOnCoreThread {
            latestMatchModel?.highlightText("")
            latestMatchModel = null
        }

        searchFilter.value = ""
        searchBarVisible.value = false
        focusSearchBarEvent.value = Event(false)
        latestMatch = null
        canSearchDown.value = false
        canSearchUp.value = false
    }

    @UiThread
    fun searchUp() {
        if (canSearchUp.value == true) {
            coreContext.postOnCoreThread {
                searchChatMessage(SearchDirection.Up)
            }
        }
    }

    @UiThread
    fun searchDown() {
        if (canSearchDown.value == true) {
            coreContext.postOnCoreThread {
                searchChatMessage(SearchDirection.Down)
            }
        }
    }

    @UiThread
    fun refresh() {
        coreContext.postOnCoreThread {
            Log.i("$TAG Refreshing conversation info (subject, participants, etc...)")
            computeConversationInfo()
        }
    }

    @UiThread
    fun updateUnreadMessageCount() {
        if (!isChatRoomInitialized()) return
        coreContext.postOnCoreThread {
            unreadMessagesCount.postValue(totalUnreadMessagesCount())
        }
    }

    @UiThread
    fun applyFilter() {
        coreContext.postOnCoreThread {
            computeEvents()
        }
    }

    @UiThread
    fun clearMessageHistory() {
        coreContext.postOnCoreThread {
            if (!isChatRoomInitialized()) return@postOnCoreThread

            Log.i("$TAG Clearing local message history for conversation [$conversationId]")
            getSiblingChatRooms().forEach { it.deleteHistory() }
            eventsList.forEach(EventLogModel::destroy)
            eventsList = arrayListOf()
            updateEvents.postValue(Event(true))
            isEmpty.postValue(true)
            unreadMessagesCount.postValue(0)
            historyClearedEvent.postValue(Event(true))
        }
    }

    @UiThread
    fun deleteChatMessage(chatMessageModel: MessageModel) {
        coreContext.postOnCoreThread {
            val message = chatMessageModel.chatMessage
            val deletedIds = OfflineInboxRepository.markMessageDeletedLocally(message)
            if (chatMessageModel.isOutgoing) {
                OfflineInboxRepository.removeQueuedOutgoing(message)
            }

            fun isDeletedCopy(candidate: ChatMessage): Boolean = candidate === message ||
                OfflineInboxRepository.messageIdentifiers(candidate).any { it in deletedIds }

            optimisticMessages.entries.removeAll { (_, candidate) -> isDeletedCopy(candidate) }
            val remaining = ArrayList(eventsList)
            val matchingEvents = remaining.filter { event ->
                (event.model as? MessageModel)?.chatMessage?.let(::isDeletedCopy) == true
            }
            for (event in matchingEvents) {
                updatePreviousAndNextMessages(remaining, event)
                remaining.remove(event)
                event.destroy()
            }
            eventsList = remaining
            updateEvents.postValue(Event(true))
            isEmpty.postValue(remaining.isEmpty())

            LinphoneUtils.findSiblingOneToOneChatRooms(coreContext.core, message.chatRoom).forEach { room ->
                val storedCopies = room.getHistoryMessageEvents(0).mapNotNull { it.chatMessage }.filter(::isDeletedCopy)
                storedCopies.forEach { room.deleteMessage(it) }
            }
            unreadMessagesCount.postValue(totalUnreadMessagesCount())
            messageDeletedEvent.postValue(Event(true))
            updateRepliesUpTo(message)
        }
    }

    @UiThread
    fun deleteChatMessageForEveryone(chatMessageModel: MessageModel) {
        coreContext.postOnCoreThread {
            val message = chatMessageModel.chatMessage
            Log.i("$TAG Sending order to delete contents of message [${message.messageId}] to every participant of the conversation")
            chatRoom.retractMessage(message)
        }
    }

    @UiThread
    fun markAsRead() {
        if (!isChatRoomInitialized()) return

        coreContext.postOnCoreThread {
            for (room in getSiblingChatRooms()) {
                if (CustomImdn.visibleUnreadMessagesCount(room) > 0) {
                    Log.i("$TAG Marking chat room as read")
                    room.markAsRead()
                }
                CustomImdn.markQueueDeliveredMessagesRead(listOf(room))
                CustomImdn.roomRead(room)
                coreContext.notificationsManager.dismissChatNotification(room)
            }
            OfflineInboxRepository.markLocalFallbackItemsRead(chatRoom)
            unreadMessagesCount.postValue(totalUnreadMessagesCount())
        }
    }

    /** Marks a batch of scrolled-into-view unread messages as read and emits displayed receipts. */
    @UiThread
    fun markMessagesAsRead(messages: List<MessageModel>) {
        if (!isChatRoomInitialized() || messages.isEmpty()) return

        messages.forEach { it.isRead = true }
        val nativeMessages = messages.map { it.chatMessage }
        coreContext.postOnCoreThread {
            nativeMessages.forEach { message ->
                message.markAsRead()
                CustomImdn.messageRead(message)
            }
            unreadMessagesCount.postValue(totalUnreadMessagesCount())
        }
    }

    /**
     * The receiver has made the latest page visible. Re-send displayed receipts once so
     * messages whose first receipt arrived while A was offline can immediately change from
     * a single to a double tick when SIP is available again.
     */
    @UiThread
    fun refreshDisplayedReceipts() {
        if (!isChatRoomInitialized()) return

        coreContext.postOnCoreThread {
            for (room in getSiblingChatRooms()) {
                if (CustomImdn.visibleUnreadMessagesCount(room) > 0) {
                    room.markAsRead()
                }
                CustomImdn.markQueueDeliveredMessagesRead(listOf(room))
                CustomImdn.roomRead(room)
            }
            OfflineInboxRepository.markLocalFallbackItemsRead(chatRoom)

            val visibleMessages = eventsList
                .asSequence()
                .mapNotNull { (it.model as? MessageModel)?.chatMessage }
                .toList()
                .takeLast(MESSAGES_PER_PAGE)
            CustomImdn.refreshDisplayedReceipts(chatRoom, visibleMessages)
        }
    }

    @UiThread
    fun mute() {
        if (!isChatRoomInitialized()) return
        coreContext.postOnCoreThread {
            chatRoom.muted = true
            isMuted.postValue(chatRoom.muted)
        }
    }

    @UiThread
    fun unMute() {
        if (!isChatRoomInitialized()) return
        coreContext.postOnCoreThread {
            chatRoom.muted = false
            isMuted.postValue(chatRoom.muted)
        }
    }

    @UiThread
    fun updateCurrentlyDisplayedConversation() {
        coreContext.postOnCoreThread {
            if (isChatRoomInitialized()) {
                val id = LinphoneUtils.getConversationId(chatRoom)
                Log.i(
                    "$TAG Asking notifications manager not to notify messages for conversation [$id]"
                )
                coreContext.notificationsManager.setCurrentlyDisplayedChatRoomId(id)

                checkIfConversationShouldBeDisabledForSecurityReasons()
            }
        }
    }

    @UiThread
    fun updateEphemeralLifetime(lifetime: Long) {
        if (!isChatRoomInitialized()) return
        coreContext.postOnCoreThread {
            LinphoneUtils.chatRoomConfigureEphemeralMessagesLifetime(chatRoom, lifetime)
            ephemeralLifetime.postValue(
                if (!chatRoom.isEphemeralEnabled) 0L else chatRoom.ephemeralLifetime
            )
            ephemeralLifeTimeLabel.postValue(
                LinphoneUtils.formatEphemeralExpiration(chatRoom.ephemeralLifetime)
            )
        }
    }

    @UiThread
    fun loadMoreData(totalItemsCount: Int) {
        if (!isChatRoomInitialized()) return
        coreContext.postOnCoreThread {
            // Visible bubbles are not database offsets: receipts are filtered out.
            // Read strictly before the oldest displayed event instead.
            val oldestEvent = eventsList.firstOrNull()?.eventLog
            val rooms = getSiblingChatRooms()
            val isBasicRoom = rooms.any { it.hasCapability(ChatRoom.Capabilities.Basic.toInt()) }
            val maxSize = rooms.sumOf {
                if (it.hasCapability(ChatRoom.Capabilities.Basic.toInt())) it.historySize else it.historyEventsSize
            }.coerceAtLeast(
                if (chatRoom.hasCapability(ChatRoom.Capabilities.Basic.toInt())) chatRoom.historySize else chatRoom.historyEventsSize
            )
            val mask = HistoryFilter.ChatMessage.toInt() or HistoryFilter.InfoNoDevice.toInt()
            var requested = if (isBasicRoom) {
                (eventsList.size + MESSAGES_PER_PAGE).coerceAtLeast(MESSAGES_PER_PAGE)
            } else {
                minOf(MESSAGES_PER_PAGE, maxSize)
            }
            Log.i("$TAG Loading older history before displayed events, visible count=$totalItemsCount")

            while (requested > 0) {
                val history = if (oldestEvent == null || isBasicRoom) {
                    val fetchLimit = if (requested >= maxSize && maxSize > 0) 0 else requested
                    getDisplayHistoryEvents(fetchLimit)
                } else {
                    // getHistoryRangeNear includes the anchor as the final event.
                    chatRoom.getHistoryRangeNear(requested, 0, oldestEvent, mask)
                        .dropLast(1).toTypedArray()
                }
                val visible = filterMessageEvents(history, eventsList.mapNotNull { it.eventLog })
                if (visible.isNotEmpty()) {
                    Log.i("$TAG Prepending [${visible.size}] previously undisplayed events")
                    prependEvents(visible)
                    return@postOnCoreThread
                }

                // Skip receipt-only pages, but stop at the beginning of history.
                if (maxSize <= 0 || requested >= maxSize || (!isBasicRoom && history.size < requested)) break
                requested = minOf(requested + MESSAGES_PER_PAGE, maxSize)
            }
            Log.i("$TAG No older visible events to load")
        }
    }

    @WorkerThread
    fun checkIfConversationShouldBeDisabledForSecurityReasons() {
        if (!isChatRoomInitialized()) return
        if (!chatRoom.hasCapability(ChatRoom.Capabilities.Encrypted.toInt())) {
            if (LinphoneUtils.getAccountForAddress(chatRoom.localAddress)?.params?.instantMessagingEncryptionMandatory == true) {
                Log.w(
                    "$TAG Conversation with subject [${chatRoom.subjectUtf8}] is considered as read-only because it isn't encrypted and default account is in secure mode"
                )
                isDisabledBecauseNotSecured.postValue(true)
            } else {
                isDisabledBecauseNotSecured.postValue(false)
            }
        } else {
            isDisabledBecauseNotSecured.postValue(false)
        }
    }

    @UiThread
    fun loadDataUpUntilToMessageId(messageId: String?, candidateIds: List<String> = emptyList()) {
        val references = (listOfNotNull(messageId) + candidateIds)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (references.isEmpty()) return
        if (!isChatRoomInitialized()) return

        coreContext.postOnCoreThread {
            val loadedIndex = eventsList.indexOfFirst { event ->
                val messageModel = event.model as? MessageModel ?: return@indexOfFirst false
                references.any(messageModel::matchesReplyReference)
            }
            if (loadedIndex >= 0) {
                searchInProgress.postValue(false)
                itemToScrollTo.postValue(loadedIndex)
                return@postOnCoreThread
            }

            searchInProgress.postValue(true)
            val rooms = getSiblingChatRooms()
            var targetEvent: EventLog? = null
            var targetRoom: ChatRoom? = null
            var matchedReference: String? = null
            for (room in rooms) {
                for (ref in references) {
                    val direct = room.findEventLog(ref)
                    if (direct != null) {
                        targetEvent = direct
                        targetRoom = room
                        matchedReference = ref
                        break
                    }
                }
                if (targetEvent != null) break

                val byCustomId = getDisplayHistoryEventsForRoom(room, 0).lastOrNull { event ->
                    val msg = event.chatMessage ?: return@lastOrNull false
                    msg.messageId in references || CustomImdn.id(msg) in references
                }
                if (byCustomId != null) {
                    targetEvent = byCustomId
                    targetRoom = room
                    matchedReference = byCustomId.chatMessage?.let { msg ->
                        references.firstOrNull { ref -> msg.messageId == ref || CustomImdn.id(msg) == ref }
                    } ?: references.first()
                    break
                }
            }

            if (targetEvent != null && targetRoom != null) {
                Log.i("$TAG Found event log [$targetEvent] with message ID [$matchedReference]")
                loadMessagesUpToTarget(rooms, targetRoom, targetEvent, matchedReference)
            } else {
                Log.e(
                    "$TAG Failed to find event log with message IDs [$references] in chat room history!"
                )
                searchInProgress.postValue(false)
            }
        }
    }

    @UiThread
    fun addSentMessageToEventsList(message: ChatMessage) {
        coreContext.postOnCoreThread {
            // In a multi-attachment Basic SIP send, message.eventLog can point to an EventLog
            // that liblinphone has already released. Accessing eventLog.chatMessage then causes
            // a native SIGSEGV. The normal Core callback above will refresh this message from
            // persistent history; scheduling it here also covers callback ordering races.
            val key = messageKey(message)
            if (key != null) {
                optimisticMessages[key] = message
            }
            // Do not wait for the asynchronous SQLite event callback before showing the
            // message. That callback is particularly late on some Android 14 devices;
            // if the user leaves the room first, the very first message looked lost.
            // computeEvents() builds a safe EventLogModel directly from the ChatMessage
            // for optimistic entries and never dereferences message.eventLog.
            Log.i("$TAG Showing sent message [${message.messageId}] optimistically and scheduling history refresh")
            computeEvents()
            refreshEventsFromHistorySoon()
        }
    }

    @WorkerThread
    private fun refreshEventsFromHistorySoon() {
        if (historyRefreshScheduled) return
        historyRefreshScheduled = true
        coreContext.postOnCoreThreadDelayed(
            {
                historyRefreshScheduled = false
                computeEvents()
            },
            150L
        )
    }

    @WorkerThread
    private fun configureChatRoom() {
        if (!isChatRoomInitialized()) return

        cachedTranslationConversationId = LinphoneUtils.getConversationId(chatRoom)
        cachedPeerUsername = chatRoom.peerAddress.username.orEmpty()
        cachedLocalTranslationUsername = chatRoom.localAddress.username.orEmpty()

        computeComposingLabel()

        isEndToEndEncrypted.postValue(
            chatRoom.hasCapability(ChatRoom.Capabilities.Encrypted.toInt())
        )
        isMuted.postValue(chatRoom.muted)

        computeConversationInfo()

        unreadMessagesCount.postValue(totalUnreadMessagesCount())
        computeEvents()
    }

    @WorkerThread
    private fun computeConversationInfo() {
        if (!isChatRoomInitialized()) return

        val group = LinphoneUtils.isChatRoomAGroup(chatRoom)
        isGroup.postValue(group)

        val empty =
            chatRoom.hasCapability(ChatRoom.Capabilities.Conference.toInt()) && chatRoom.participants.isEmpty()
        if (empty) {
            Log.w("$TAG Conversation has conference capability but has no participants!")
        }
        val readOnly = chatRoom.isReadOnly
        isReadOnly.postValue(readOnly)
        if (readOnly) {
            Log.w("$TAG Conversation with subject [${chatRoom.subjectUtf8}] is read only!")
        }

        checkIfConversationShouldBeDisabledForSecurityReasons()

        subject.postValue(chatRoom.subjectUtf8)

        computeParticipantsInfo()

        ephemeralLifetime.postValue(
            if (!chatRoom.isEphemeralEnabled) 0L else chatRoom.ephemeralLifetime
        )
        ephemeralLifeTimeLabel.postValue(
            LinphoneUtils.formatEphemeralExpiration(chatRoom.ephemeralLifetime)
        )
    }

    @WorkerThread
    private fun computeParticipantsInfo() {
        if (!isChatRoomInitialized()) return

        val friends = arrayListOf<Friend>()
        val address = if (chatRoom.hasCapability(ChatRoom.Capabilities.Basic.toInt())) {
            chatRoom.peerAddress
        } else {
            for (participant in chatRoom.participants) {
                val friend = coreContext.contactsManager.findContactByAddress(participant.address)
                if (friend != null && !friends.contains(friend)) {
                    friends.add(friend)
                }
            }

            val firstParticipant = chatRoom.participants.firstOrNull()
            firstParticipant?.address ?: chatRoom.peerAddress
        }

        val avatar = if (LinphoneUtils.isChatRoomAGroup(chatRoom)) {
            val fakeFriend = coreContext.core.createFriend()
            fakeFriend.name = chatRoom.subjectUtf8
            val model = ContactAvatarModel(fakeFriend)
            model.updateSecurityLevelUsingConversation(chatRoom)
            model
        } else {
            coreContext.contactsManager.getContactAvatarModelForAddress(address)
        }
        avatarModel.postValue(avatar)
    }

    @WorkerThread
    private fun computeEvents() {
        if (!isChatRoomInitialized()) return

        eventsList.forEach(EventLogModel::destroy)

        val rooms = getSiblingChatRooms()
        var history = getDisplayHistoryEvents(MESSAGES_PER_PAGE)
        rooms.forEach { room ->
            room.unreadHistory.forEach { msg ->
                if (
                    msg != null &&
                    !CustomImdn.isHiddenControlMessage(msg) &&
                    !OfflineInboxRepository.isMessageDeletedLocally(room, msg)
                ) {
                    val key = messageKey(msg)
                    if (key != null && !optimisticMessages.containsKey(key)) {
                        optimisticMessages[key] = msg
                    }
                }
            }
        }
        var list = getEventsListFromHistory(history)
        // If trailing control/receipt events fill the entire first page (e.g. after reply/forward),
        // or if paginated fetch returned no displayable items, widen to full history.
        if (list.isEmpty()) {
            list.forEach(EventLogModel::destroy)
            history = getDisplayHistoryEvents(0)
            list = getEventsListFromHistory(history)
        }
        val storedMessages = history.mapNotNull { it.chatMessage }
        val storedMessageKeys = storedMessages.mapNotNull(::messageKey).toSet()
        val storedCustomIds = storedMessages.mapNotNull(::extractCustomMessageId).toSet()
        val storedNativeIds = storedMessages.mapNotNull {
            it.messageId.takeIf(String::isNotBlank)
        }.toSet()
        val storedFingerprints = storedMessages.mapNotNull(::incomingFingerprint).toSet()
        optimisticMessages.entries.removeAll { (key, msg) ->
            key in storedMessageKeys ||
                extractCustomMessageId(msg) in storedCustomIds ||
                msg.messageId.takeIf(String::isNotBlank) in storedNativeIds ||
                incomingFingerprint(msg) in storedFingerprints ||
                OfflineInboxRepository.isMessageDeletedLocally(chatRoom, msg)
        }
        optimisticMessages.values.forEach { message ->
            if (
                LinphoneUtils.isDisplayableChatMessage(message) &&
                !OfflineInboxRepository.isMessageDeletedLocally(chatRoom, message)
            ) {
                Log.w("$TAG Showing optimistic message [${message.messageId}] because it isn't in Core history yet")
                list.add(createOptimisticSentMessageModel(message))
            }
        }
        // Gather all IDs and fingerprints already present in history, optimistic messages, or current list
        val allPresentCustomIds = (
            storedCustomIds +
                optimisticMessages.values.mapNotNull(::extractCustomMessageId) +
                list.mapNotNull { (it.model as? MessageModel)?.chatMessage?.let(::extractCustomMessageId) }
        ).toMutableSet()
        val allPresentFingerprints = (
            storedFingerprints +
                optimisticMessages.values.mapNotNull(::incomingFingerprint) +
                list.mapNotNull { (it.model as? MessageModel)?.chatMessage?.let(::incomingFingerprint) }
        ).toMutableSet()

        // Vivo Android 14 can dispatch the incoming callback yet reject the synthesized Basic
        // event in Core SQLite. Render only durable queue rows not already in native history or optimistic list.
        OfflineInboxRepository.localFallbackMessages(chatRoom, allPresentCustomIds).forEach { message ->
            val customId = extractCustomMessageId(message)
            val fingerprint = incomingFingerprint(message)
            if (customId != null && customId in allPresentCustomIds) {
                return@forEach
            }
            if (fingerprint != null && fingerprint in allPresentFingerprints) {
                return@forEach
            }
            if (customId != null) allPresentCustomIds.add(customId)
            if (fingerprint != null) allPresentFingerprints.add(fingerprint)
            Log.w("$TAG Showing durable queue fallback [${CustomImdn.id(message)}] missing from Core history")
            list.add(createOptimisticSentMessageModel(message))
        }

        // Deduplicate the combined list to ensure no duplicate bubbles are displayed
        val deduplicatedList = arrayListOf<EventLogModel>()
        val seenCustomIds = mutableSetOf<String>()
        val seenNativeIds = mutableSetOf<String>()
        val seenFingerprints = mutableSetOf<String>()
        val seenKeys = mutableSetOf<String>()

        for (eventModel in list) {
            val message = (eventModel.model as? MessageModel)?.chatMessage
            if (message == null) {
                deduplicatedList.add(eventModel)
                continue
            }
            val customId = extractCustomMessageId(message)
            val nativeId = message.messageId.takeIf { it.isNotBlank() }
            val fingerprint = incomingFingerprint(message)
            val key = messageKey(message)

            val isDuplicate = (customId != null && customId in seenCustomIds) ||
                (nativeId != null && nativeId in seenNativeIds) ||
                (fingerprint != null && fingerprint in seenFingerprints) ||
                (key != null && key in seenKeys)

            if (!isDuplicate) {
                if (customId != null) seenCustomIds.add(customId)
                if (nativeId != null) seenNativeIds.add(nativeId)
                if (fingerprint != null) seenFingerprints.add(fingerprint)
                if (key != null) seenKeys.add(key)
                deduplicatedList.add(eventModel)
            } else {
                Log.w("$TAG Dropped duplicate message from UI: customId=$customId nativeId=$nativeId fingerprint=$fingerprint")
                eventModel.destroy()
            }
        }
        list = deduplicatedList

        list.sortWith(
            // Kotlin's sort is stable, so native order remains the tiebreaker when timestamps match.
            compareBy<EventLogModel> { (it.model as? MessageModel)?.timestampMillis ?: Long.MIN_VALUE }
        )
        Log.i("$TAG Extracted [${list.size}] events from conversation history in database")
        eventsList = list
        updateEvents.postValue(Event(true))
        isEmpty.postValue(eventsList.isEmpty())
        checkAndTranslateIncomingMessages()
    }

    @WorkerThread
    private fun totalUnreadMessagesCount(): Int {
        val rooms = getSiblingChatRooms()
        val nativeUnreadIds = CustomImdn.visibleUnreadMessageIds(rooms)
        return CustomImdn.visibleUnreadMessagesCount(rooms) +
            OfflineInboxRepository.unreadStoredItemsCountForRoom(chatRoom, nativeUnreadIds)
    }

    /**
     * Basic SIP rooms persist messages in the message-history collection, whereas
     * conference rooms expose their timeline through the generic event collection.
     * Calling getHistoryEvents() for a Basic room returns an empty list on some SDK
     * builds, which made Vivo's otherwise delivered messages invisible in the UI.
     */
    @WorkerThread
    private fun getDisplayHistoryEvents(limit: Int): Array<EventLog> {
        val rooms = getSiblingChatRooms()
        if (rooms.size <= 1) {
            return getDisplayHistoryEventsForRoom(chatRoom, limit)
        }
        return rooms.flatMap { getDisplayHistoryEventsForRoom(it, limit).asList() }.toTypedArray()
    }

    @WorkerThread
    private fun getDisplayHistoryEventsForRoom(room: ChatRoom, limit: Int): Array<EventLog> =
        if (room.hasCapability(ChatRoom.Capabilities.Basic.toInt())) {
            room.getHistoryMessageEvents(limit)
        } else {
            room.getHistoryEvents(limit)
        }

    @WorkerThread
    private fun addEvents(eventLogs: Array<EventLog>) {
        Log.i("$TAG Adding [${eventLogs.size}] event(s)")
        // Need to use a new list, otherwise ConversationFragment's dataObserver isn't triggered...
        val list = arrayListOf<EventLogModel>()
        list.addAll(eventsList)
        val lastEvent = list.lastOrNull()

        // Prevents message duplicates
        val eventsToAdd = arrayListOf<EventLog>()
        eventsToAdd.addAll(filterMessageEvents(eventLogs, list.mapNotNull { it.eventLog }))

        val newList = getEventsListFromHistory(
            eventsToAdd.toTypedArray()
        )
        val newEvent = newList.firstOrNull()

        if (lastEvent != null && lastEvent.model is MessageModel && newEvent != null && newEvent.model is MessageModel &&
            newEvent.eventLog != null && lastEvent.eventLog != null && shouldWeGroupTwoEvents(
                newEvent.eventLog!!,
                lastEvent.eventLog!!
            )
        ) {
            lastEvent.model.groupedWithNextMessage.postValue(true)
            newEvent.model.groupedWithPreviousMessage.postValue(true)
        }

        list.addAll(newList)
        eventsList = list
        updateEvents.postValue(Event(true))
        isEmpty.postValue(eventsList.isEmpty())
        checkAndTranslateIncomingMessages()
    }

    @WorkerThread
    private fun prependEvents(eventLogs: Array<EventLog>) {
        Log.i("$TAG Prepending [${eventLogs.size}] events")
        // Need to use a new list, otherwise ConversationFragment's dataObserver isn't triggered...
        val list = arrayListOf<EventLogModel>()
        val firstEvent = eventsList.firstOrNull()

        // Prevents message duplicates
        val eventsToAdd = arrayListOf<EventLog>()
        eventsToAdd.addAll(filterMessageEvents(eventLogs, eventsList.mapNotNull { it.eventLog }))

        val newList = getEventsListFromHistory(
            eventsToAdd.toTypedArray()
        )
        val lastEvent = newList.lastOrNull()

        if (lastEvent != null && lastEvent.model is MessageModel && firstEvent != null && firstEvent.model is MessageModel &&
            firstEvent.eventLog != null && lastEvent.eventLog != null && shouldWeGroupTwoEvents(
                firstEvent.eventLog!!,
                lastEvent.eventLog!!
            )
        ) {
            lastEvent.model.groupedWithNextMessage.postValue(true)
            firstEvent.model.groupedWithPreviousMessage.postValue(true)
        }

        list.addAll(newList)
        list.addAll(eventsList)
        eventsList = list
        updateEvents.postValue(Event(true))
        isEmpty.postValue(eventsList.isEmpty())
        checkAndTranslateIncomingMessages()
    }

    @WorkerThread
    private fun processGroupedEvents(
        groupedEventLogs: ArrayList<EventLog>
    ): ArrayList<EventLogModel> {
        val groupChatRoom = LinphoneUtils.isChatRoomAGroup(chatRoom)
        val eventsList = arrayListOf<EventLogModel>()

        var index = 0
        for (groupedEvent in groupedEventLogs) {
            val model = EventLogModel(
                groupedEvent,
                groupChatRoom,
                index > 0,
                index != groupedEventLogs.size - 1,
                searchFilter.value.orEmpty(),
                { fileModel -> // onContentClicked
                    fileToDisplayEvent.postValue(Event(fileModel))
                },
                { sipUri -> // onSipUriClicked
                    sipUriToCallEvent.postValue(Event(sipUri))
                },
                { conferenceUri -> // onJoinConferenceClicked
                    conferenceToJoinEvent.postValue(Event(conferenceUri))
                },
                { url -> // onWebUrlClicked
                    openWebBrowserEvent.postValue(Event(url))
                },
                { friendRefKey -> // onContactClicked
                    contactToDisplayEvent.postValue(Event(friendRefKey))
                },
                { redToast -> // onRedToastToShow
                    showRedToastEvent.postValue(Event(redToast))
                },
                { id -> // onVoiceRecordingPlaybackEnded
                    voiceRecordPlaybackEndedEvent.postValue(Event(id))
                },
                { filePath -> // onFileToExportToNativeGallery
                    viewModelScope.launch {
                        withContext(Dispatchers.IO) {
                            Log.i("$TAG Export file [$filePath] to Android's MediaStore")
                            val mediaStorePath = FileUtils.addContentToMediaStore(filePath)
                            if (mediaStorePath.isNotEmpty()) {
                                Log.i("$TAG File [$filePath] has been successfully exported to MediaStore")
                            } else {
                                Log.e("$TAG Failed to export file [$filePath] to MediaStore!")
                            }
                        }
                    }
                },
                { message -> // onFileTransferCompleted
                    updateRepliesUpTo(message)
                }
            )
            eventsList.add(model)

            index += 1
        }

        return eventsList
    }

    private fun canonicalAddressKey(address: org.linphone.core.Address?): String {
        if (address == null) return ""
        val username = address.username?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (username != null) {
            if (username.none(Char::isLetter)) {
                val normalized = com.naminfo.contacts.PhoneContactNumbers.normalize(username)
                if (normalized.length >= 6) return normalized
            }
            return username
        }
        return address.asStringUriOnly().lowercase()
    }

    private fun extractCustomMessageId(message: ChatMessage): String? =
        CustomImdn.id(message)
            ?: message.getCustomHeader("X-Diya-Message-ID")?.takeIf { it.isNotBlank() }
            ?: if (!CustomImdn.isSemanticallyIncoming(message)) OfflineInboxRepository.outgoingHistoryId(message) else null

    @WorkerThread
    private fun messageKey(message: ChatMessage): String? {
        val customId = extractCustomMessageId(message)
        val identity = if (customId != null) {
            "custom:$customId"
        } else {
            message.messageId.takeIf { it.isNotBlank() }?.let { "native:$it" } ?: return null
        }
        val isOutgoing = !CustomImdn.isSemanticallyIncoming(message)
        return "$isOutgoing|${canonicalAddressKey(message.fromAddress)}|$identity"
    }

    @WorkerThread
    private fun messageEventKey(event: EventLog): String? {
        return event.chatMessage?.let(::messageKey)
    }

    @WorkerThread
    private fun createOptimisticSentMessageModel(message: ChatMessage): EventLogModel {
        return EventLogModel(
            message,
            LinphoneUtils.isChatRoomAGroup(chatRoom),
            false,
            false,
            searchFilter.value.orEmpty(),
            { fileModel -> fileToDisplayEvent.postValue(Event(fileModel)) },
            { sipUri -> sipUriToCallEvent.postValue(Event(sipUri)) },
            { conferenceUri -> conferenceToJoinEvent.postValue(Event(conferenceUri)) },
            { url -> openWebBrowserEvent.postValue(Event(url)) },
            { friendRefKey -> contactToDisplayEvent.postValue(Event(friendRefKey)) },
            { redToast -> showRedToastEvent.postValue(Event(redToast)) },
            { id -> voiceRecordPlaybackEndedEvent.postValue(Event(id)) },
            { filePath ->
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        FileUtils.addContentToMediaStore(filePath)
                    }
                }
            },
            { completedMessage ->
                updateRepliesUpTo(completedMessage)
            }
        )
    }

    @WorkerThread
    private fun filterMessageEvents(
        incoming: Array<EventLog>,
        existing: List<EventLog> = emptyList()
    ): Array<EventLog> {
        val seen = existing.mapNotNull { messageEventKey(it) }.toMutableSet()
        // During an online/offline race, the durable queue can inject a local
        // incoming copy while the original SIP MESSAGE is arriving. Older Basic
        // SDKs occasionally lose the custom header on one of those two events,
        // so the stable-ID check above cannot recognise the pair. Keep a very
        // narrow five-second fingerprint keyed alongside any observed custom IDs
        // so unkeyed copies still collapse while intentionally repeated messages
        // with distinct custom IDs stay distinct.
        val incomingFingerprintIds = mutableMapOf<String, MutableSet<String?>>()
        existing.forEach { event ->
            val message = event.chatMessage ?: return@forEach
            incomingFingerprint(message)?.let { fingerprint ->
                incomingFingerprintIds.getOrPut(fingerprint) { mutableSetOf() }
                    .add(extractCustomMessageId(message))
            }
        }
        return incoming.filter { event ->
            val message = event.chatMessage
            if (
                message != null &&
                (
                    !LinphoneUtils.isDisplayableChatMessage(message) ||
                        OfflineInboxRepository.isMessageDeletedLocally(message.chatRoom, message)
                    )
            ) {
                false
            } else {
                val key = messageEventKey(event)
                val stableIdIsNew = key == null || seen.add(key)
                val fingerprint = message?.let(::incomingFingerprint)
                val customId = message?.let(::extractCustomMessageId)
                val existingIds = if (fingerprint != null) incomingFingerprintIds[fingerprint] else null
                val pairedByFingerprint = existingIds != null &&
                    (customId == null || existingIds.contains(null) || existingIds.contains(customId))
                if (fingerprint != null) {
                    incomingFingerprintIds.getOrPut(fingerprint) { mutableSetOf() }.add(customId)
                }
                val keep = stableIdIsNew && !pairedByFingerprint
                if (!keep) Log.i("$TAG Skipping duplicate message event [$key]")
                keep
            }
        }.toTypedArray()
    }

    @WorkerThread
    private fun incomingFingerprint(message: ChatMessage): String? {
        if (!CustomImdn.isSemanticallyIncoming(message)) return null
        val body = message.utf8Text.orEmpty()
        val fileNames = message.contents.joinToString("|") { it.name.orEmpty() }
        if (body.isBlank() && fileNames.isBlank()) return null
        // A queue copy is synthesized locally, so its native fromAddress may be B itself.
        // Use the Basic room peer (A) for that copy, matching the sender of the direct SIP copy.
        val sender = if (CustomImdn.isQueueDeliveredIncoming(message)) {
            message.chatRoom.peerAddress
        } else {
            message.fromAddress
        }
        val bucket5s = CustomImdn.timestampMillis(message) / 5_000L
        return "${canonicalAddressKey(sender)}|$bucket5s|$body|$fileNames"
    }

    @WorkerThread
    private fun getEventsListFromHistory(
        history: Array<EventLog>
    ): ArrayList<EventLogModel> {
        val eventsList = arrayListOf<EventLogModel>()
        val groupedEventLogs = arrayListOf<EventLog>()
        // The native Basic-SIP history order can change when offline queue items are
        // inserted during login. Always build the bubbles from sender date/time instead.
        val visibleHistory = filterMessageEvents(history)
            .sortedWith(
                compareBy<EventLog> { eventTimestamp(it) }
            )

        if (visibleHistory.size == 1) {
            // If there is a single event, improve processing speed by skipping grouping tasks
            val event = visibleHistory[0]
            if (!CustomImdn.isHiddenControlMessage(event.chatMessage)) {
                eventsList.addAll(processGroupedEvents(arrayListOf(event)))
            }
        } else {
            for (event in visibleHistory) {
                if (CustomImdn.isHiddenControlMessage(event.chatMessage)) continue
                if (groupedEventLogs.isEmpty()) {
                    groupedEventLogs.add(event)
                    continue
                }

                val previousGroupEvent = groupedEventLogs.last()
                val groupEvents = shouldWeGroupTwoEvents(event, previousGroupEvent)

                // Handle all events in group, then re-start a new group with current item
                if (!groupEvents) {
                    eventsList.addAll(processGroupedEvents(groupedEventLogs))
                    groupedEventLogs.clear()
                }

                groupedEventLogs.add(event)
            }

            if (groupedEventLogs.isNotEmpty()) {
                eventsList.addAll(processGroupedEvents(groupedEventLogs))
                groupedEventLogs.clear()
            }
        }

        return eventsList
    }

    @WorkerThread
    private fun eventTimestamp(event: EventLog): Long {
        val message = event.chatMessage
        return if (message != null) CustomImdn.timestampMillis(message) else Long.MIN_VALUE
    }

    @WorkerThread
    private fun shouldWeGroupTwoEvents(event: EventLog, previousGroupEvent: EventLog): Boolean {
        return if (previousGroupEvent.type == EventLog.Type.ConferenceChatMessage && event.type == EventLog.Type.ConferenceChatMessage) {
            val previousChatMessage = previousGroupEvent.chatMessage!!
            val chatMessage = event.chatMessage!!

            // If they have the same direction, the same from address and were sent in a short timelapse, group them
            CustomImdn.isSemanticallyIncoming(chatMessage) ==
                CustomImdn.isSemanticallyIncoming(previousChatMessage) &&
                chatMessage.fromAddress.weakEqual(previousChatMessage.fromAddress) &&
                kotlin.math.abs(
                    CustomImdn.timestamp(chatMessage) - CustomImdn.timestamp(previousChatMessage)
                ) < MAX_TIME_TO_GROUP_MESSAGES
        } else {
            false
        }
    }

    @WorkerThread
    private fun updatePreviousAndNextMessages(
        list: ArrayList<EventLogModel>,
        found: EventLogModel
    ) {
        val index = list.indexOf(found)
        if (found.model is MessageModel) {
            val messageModel = found.model
            if (messageModel.groupedWithNextMessage.value == true && messageModel.groupedWithPreviousMessage.value == true) {
                Log.i(
                    "$TAG Deleted message was grouped with both next and previous one; nothing to do"
                )
                // Nothing to do
            } else if (messageModel.groupedWithPreviousMessage.value == true) {
                Log.i("$TAG Deleted message was grouped with previous one")
                if (index > 0) {
                    val previous = list[index - 1]
                    if (previous.model is MessageModel) {
                        previous.model.groupedWithNextMessage.postValue(false)
                        Log.i("$TAG Previous message at [${index - 1}] was updated")
                    }
                }
            } else if (messageModel.groupedWithNextMessage.value == true) {
                Log.i("$TAG Deleted message was grouped with next one")
                if (index < list.size - 1) {
                    val next = list[index + 1]
                    if (next.model is MessageModel) {
                        next.model.groupedWithPreviousMessage.postValue(false)
                        Log.i("$TAG Next message at [${index + 1}] was updated")
                    }
                }
            }
        }
    }

    @WorkerThread
    private fun updateRepliesUpTo(chatMessage: ChatMessage) {
        for (model in eventsList.reversed()) {
            val messageModel = model.model as? MessageModel ?: continue
            if (messageModel.referencesOriginalMessage(chatMessage)) {
                messageModel.computeReplyInfo()
            }
        }
    }

    @WorkerThread
    private fun computeComposingLabel() {
        if (!isChatRoomInitialized()) return

        val callbackNames = remoteComposingState.participantNamesOrNull()
        val pair = if (callbackNames == null) {
            LinphoneUtils.getComposingIconAndText(chatRoom)
        } else if (callbackNames.isEmpty()) {
            Pair(0, "")
        } else {
            Pair(
                R.drawable.chat_teardrop_dots,
                AppUtils.getStringWithPlural(
                    R.plurals.conversation_composing_label,
                    callbackNames.size,
                    callbackNames.joinToString(", ")
                )
            )
        }
        val icon = pair.first
        composingIcon.postValue(icon)
        val label = pair.second
        composingLabel.postValue(label)
    }

    @WorkerThread
    private fun loadMessagesUpTo(targetEvent: EventLog) {
        loadMessagesUpToTarget(
            getSiblingChatRooms(), chatRoom, targetEvent,
            targetEvent.chatMessage?.let {
            CustomImdn.id(it) ?: it.messageId
        }
        )
    }

    @WorkerThread
    private fun loadMessagesUpToTarget(
        rooms: List<ChatRoom>,
        targetRoom: ChatRoom,
        targetEvent: EventLog,
        referenceId: String?
    ) {
        val firstStoredEvent = eventsList.firstOrNull()?.eventLog
        if (rooms.size <= 1 && targetRoom == chatRoom && firstStoredEvent != null) {
            val mask = HistoryFilter.ChatMessage.toInt() or HistoryFilter.InfoNoDevice.toInt()
            val historyToAdd = chatRoom.getHistoryRangeBetween(
                targetEvent,
                firstStoredEvent,
                mask
            )
            val previousMessages = chatRoom.getHistoryRangeNear(
                ITEMS_TO_LOAD_BEFORE_SEARCH_RESULT,
                0,
                targetEvent,
                mask
            )
            val toAdd = previousMessages.plus(historyToAdd)
            val combined = filterMessageEvents(toAdd, eventsList.mapNotNull { it.eventLog })
            val newList = getEventsListFromHistory(combined)
            val list = arrayListOf<EventLogModel>()
            val firstEvent = eventsList.firstOrNull()
            val lastNew = newList.lastOrNull()
            val firstEventLog = firstEvent?.eventLog
            val lastNewEventLog = lastNew?.eventLog
            if (
                lastNew != null && lastNew.model is MessageModel &&
                firstEvent != null && firstEvent.model is MessageModel &&
                firstEventLog != null && lastNewEventLog != null &&
                shouldWeGroupTwoEvents(firstEventLog, lastNewEventLog)
            ) {
                lastNew.model.groupedWithNextMessage.postValue(true)
                firstEvent.model.groupedWithPreviousMessage.postValue(true)
            }
            list.addAll(newList)
            list.addAll(eventsList)
            val targetIndex = if (referenceId != null) {
                list.indexOfFirst { (it.model as? MessageModel)?.matchesReplyReference(referenceId) == true }
            } else {
                (newList.size - 1).coerceAtLeast(0)
            }
            if (targetIndex >= 0) {
                eventsList = list
                itemToScrollTo.postValue(targetIndex)
                isEmpty.postValue(eventsList.isEmpty())
                updateEvents.postValue(Event(true))
                checkAndTranslateIncomingMessages()
                return
            }
            // getHistoryRangeBetween missed the endpoints or reference ID; fall through to
            // chronological targetRoomHistory window below so we don't jump to index 0.
            newList.forEach(EventLogModel::destroy)
        }

        val targetRoomHistory = getDisplayHistoryEventsForRoom(targetRoom, 0)
            .sortedBy(::eventTimestamp)
        val targetPos = targetRoomHistory.indexOfFirst {
            it == targetEvent ||
                (
                    referenceId != null &&
                        it.chatMessage?.let { msg -> msg.messageId == referenceId || CustomImdn.id(msg) == referenceId } == true
                    )
        }
        val targetTs = eventTimestamp(targetEvent)
        val windowStartTs = if (targetPos >= 0) {
            val contextIdx = (targetPos - ITEMS_TO_LOAD_BEFORE_SEARCH_RESULT).coerceAtLeast(0)
            eventTimestamp(targetRoomHistory[contextIdx])
        } else {
            targetTs
        }

        val existingRaw = eventsList.mapNotNull { it.eventLog }
        val roomEvents = rooms.flatMap { room ->
            getDisplayHistoryEventsForRoom(room, 0).filter { eventTimestamp(it) >= windowStartTs }
        }
        val allCandidateEvents = (roomEvents + existingRaw).toTypedArray()
        val list = getEventsListFromHistory(allCandidateEvents)
        optimisticMessages.values.forEach { message ->
            val msgKey = messageKey(message)
            val msgCustomId = extractCustomMessageId(message)
            val msgNativeId = message.messageId.takeIf(String::isNotBlank)
            val msgFingerprint = incomingFingerprint(message)
            if (LinphoneUtils.isDisplayableChatMessage(message) && list.none {
                val existingMsg = it.eventLog?.chatMessage
                val mm = it.model as? MessageModel
                (mm != null && (mm.id == message.messageId || (msgCustomId != null && mm.customDiyaId == msgCustomId))) ||
                    (
                        existingMsg != null && (
                        (msgKey != null && messageKey(existingMsg) == msgKey) ||
                            (msgNativeId != null && existingMsg.messageId == msgNativeId) ||
                            (msgFingerprint != null && incomingFingerprint(existingMsg) == msgFingerprint)
                        )
                    )
            }
            ) {
                list.add(createOptimisticSentMessageModel(message))
            }
        }
        list.sortWith(compareBy<EventLogModel> { (it.model as? MessageModel)?.timestampMillis ?: Long.MIN_VALUE })

        val targetIndex = referenceId?.let { ref ->
            list.indexOfFirst { (it.model as? MessageModel)?.matchesReplyReference(ref) == true }
        } ?: 0
        if (targetIndex < 0) {
            Log.e("$TAG Target message [$referenceId] still not in rebuilt list")
            searchInProgress.postValue(false)
            return
        }
        eventsList.forEach(EventLogModel::destroy)
        eventsList = list
        itemToScrollTo.postValue(targetIndex)
        isEmpty.postValue(eventsList.isEmpty())
        updateEvents.postValue(Event(true))
        checkAndTranslateIncomingMessages()
    }

    @WorkerThread
    private fun searchChatMessage(direction: SearchDirection) {
        if (!isChatRoomInitialized()) return
        searchInProgress.postValue(true)

        val textToSearch = searchFilter.value.orEmpty().trim()
        val match = chatRoom.searchChatMessageByText(textToSearch, latestMatch, direction)
        if (match == null) {
            Log.i(
                "$TAG No match found while looking up for message with text [$textToSearch] in direction [$direction] starting from message [${latestMatch?.chatMessage?.messageId}]"
            )
            searchInProgress.postValue(false)
            val message = if (latestMatch == null) {
                R.string.conversation_search_no_match_found
            } else {
                // Scroll to last matching event anyway, user may have scrolled away
                val found = eventsList.find {
                    it.eventLog == latestMatch
                }
                if (found != null) { // This should always be true
                    val index = eventsList.indexOf(found)
                    itemToScrollTo.postValue(index)
                }
                // Disable button as latest result has been reached
                if (direction == SearchDirection.Down) {
                    canSearchDown.postValue(false)
                } else {
                    canSearchUp.postValue(false)
                }
                R.string.conversation_search_no_more_match
            }
            showRedToast(message, R.drawable.magnifying_glass)
        } else {
            canSearchDown.postValue(true)
            canSearchUp.postValue(true)

            // Clear highlight from previous match
            latestMatchModel?.highlightText("")

            Log.i(
                "$TAG Found result [${match.chatMessage?.messageId}] while looking up for message with text [$textToSearch] in direction [$direction] starting from message [${latestMatch?.chatMessage?.messageId}]"
            )
            latestMatch = match
            val found = eventsList.find {
                it.eventLog == match
            }
            if (found == null) {
                Log.i("$TAG Found result isn't in currently loaded history, loading missing events")
                loadMessagesUpTo(match)
            } else {
                Log.i("$TAG Found result is already in history, no need to load more history")
                latestMatchModel = (found.model as? MessageModel)
                latestMatchModel?.highlightText(textToSearch)
                val index = eventsList.indexOf(found)
                itemToScrollTo.postValue(index)
                searchInProgress.postValue(false)
            }
        }
    }

    @UiThread
    fun copyFileToUri(filePath: String, dest: Uri) {
        val source = FileUtils.getProperFilePath(filePath).toUri()
        Log.i("$TAG Copying file URI [$source] to [$dest]")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val result = FileUtils.copyFile(source, dest)
                if (result) {
                    Log.i(
                        "$TAG File [$filePath] has been successfully exported to documents"
                    )
                    showGreenToast(R.string.file_successfully_exported_to_documents_toast, R.drawable.check)
                } else {
                    Log.e("$TAG Failed to export file [$filePath] to documents!")
                    showRedToast(R.string.export_file_to_documents_error_toast, R.drawable.warning_circle)
                }
            }
        }
    }

    @Volatile
    private var cachedTranslationConversationId: String? = null

    @Volatile
    private var cachedPeerUsername: String? = null

    @Volatile
    private var cachedLocalTranslationUsername: String? = null

    fun getTranslationConversationId(): String {
        return cachedTranslationConversationId ?: conversationId
    }

    fun resolveEffectiveLanguage(): ChatTranslationLanguage {
        val overrideCode = corePreferences.getContactTranslationOverride(getTranslationConversationId())
        if (!overrideCode.isNullOrEmpty() && !overrideCode.equals("USE_GLOBAL", ignoreCase = true)) {
            val contactLang = ChatTranslationLanguage.fromCode(overrideCode)
            if (contactLang != ChatTranslationLanguage.NONE) return contactLang
        }
        return corePreferences.receivedChatTranslationLanguage
    }

    fun setContactTranslationOverride(languageCode: String?) {
        corePreferences.setContactTranslationOverride(getTranslationConversationId(), languageCode)
        checkAndTranslateIncomingMessages()
    }

    fun checkAndTranslateIncomingMessages() {
        val peer = cachedPeerUsername
        if (peer == null && isChatRoomInitialized()) {
            coreContext.postOnCoreThread {
                if (isChatRoomInitialized()) {
                    cachedTranslationConversationId = LinphoneUtils.getConversationId(chatRoom)
                    cachedPeerUsername = chatRoom.peerAddress.username.orEmpty()
                    cachedLocalTranslationUsername = chatRoom.localAddress.username.orEmpty()
                } else {
                    cachedPeerUsername = ""
                    cachedLocalTranslationUsername = ""
                }
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    checkAndTranslateIncomingMessages()
                }
            }
            return
        }
        val effectiveLang = resolveEffectiveLanguage()
        // `to_number` is the receiving account whose translation preference is being used.
        val toNumber = cachedLocalTranslationUsername.orEmpty()

        viewModelScope.launch(Dispatchers.IO) {
            val currentEvents = ArrayList(eventsList)
            for (eventLogModel in currentEvents) {
                val messageModel = eventLogModel.model as? MessageModel ?: continue
                if (messageModel.isOutgoing) continue

                val originalText = messageModel.getCachedRawTextContent() ?: continue
                if (originalText.isBlank()) continue

                if (effectiveLang == com.naminfo.translation.ChatTranslationLanguage.NONE) {
                    coreContext.postOnMainThread {
                        messageModel.translatedText.value = null
                        messageModel.showOriginal.value = false
                    }
                    continue
                }

                val translationCacheId = messageModel.translationCacheId()
                val cached = com.naminfo.translation.TranslationRepository.getCachedTranslation(translationCacheId, effectiveLang)
                if (cached != null) {
                    coreContext.postOnMainThread {
                        messageModel.applyTranslation(cached, effectiveLang)
                    }
                    continue
                }

                messageModel.isTranslating.postValue(true)
                val result = com.naminfo.translation.TranslationRepository.translateMessage(
                    translationCacheId,
                    toNumber,
                    originalText,
                    effectiveLang
                )

                coreContext.postOnMainThread {
                    if (result.isSuccess) {
                        messageModel.applyTranslation(result.getOrNull().orEmpty(), effectiveLang)
                    } else {
                        messageModel.isTranslating.value = false
                        messageModel.translatedText.value = null
                    }
                }
            }
        }
    }
}
