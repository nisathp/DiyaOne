package com.naminfo.ui.main.chat.model

import androidx.annotation.WorkerThread
import org.linphone.core.ChatMessage
import org.linphone.core.EventLog

class EventLogModel private constructor(
    /**
     * A message normally has a durable Core event. It is nullable only for an optimistic
     * outgoing Basic-SIP message while the native database is temporarily unavailable.
     */
    val eventLog: EventLog?,
    private val directChatMessage: ChatMessage?,
    isFromGroup: Boolean = false,
    isGroupedWithPreviousOne: Boolean = false,
    isGroupedWithNextOne: Boolean = false,
    currentFilter: String = "",
    onContentClicked: ((fileModel: FileModel) -> Unit)? = null,
    onSipUriClicked: ((uri: String) -> Unit)? = null,
    onJoinConferenceClicked: ((uri: String) -> Unit)? = null,
    onWebUrlClicked: ((url: String) -> Unit)? = null,
    onContactClicked: ((friendRefKey: String) -> Unit)? = null,
    onRedToastToShow: ((pair: Pair<Int, Int>) -> Unit)? = null,
    onVoiceRecordingPlaybackEnded: ((id: String) -> Unit)? = null,
    onFileToExportToNativeGallery: ((path: String) -> Unit)? = null,
    onFileTransferCompleted: ((message: ChatMessage) -> Unit)? = null
) {
    @WorkerThread
    constructor(
        eventLog: EventLog,
        isFromGroup: Boolean = false,
        isGroupedWithPreviousOne: Boolean = false,
        isGroupedWithNextOne: Boolean = false,
        currentFilter: String = "",
        onContentClicked: ((fileModel: FileModel) -> Unit)? = null,
        onSipUriClicked: ((uri: String) -> Unit)? = null,
        onJoinConferenceClicked: ((uri: String) -> Unit)? = null,
        onWebUrlClicked: ((url: String) -> Unit)? = null,
        onContactClicked: ((friendRefKey: String) -> Unit)? = null,
        onRedToastToShow: ((pair: Pair<Int, Int>) -> Unit)? = null,
        onVoiceRecordingPlaybackEnded: ((id: String) -> Unit)? = null,
        onFileToExportToNativeGallery: ((path: String) -> Unit)? = null,
        onFileTransferCompleted: ((message: ChatMessage) -> Unit)? = null
    ) : this(
        eventLog,
        null,
        isFromGroup,
        isGroupedWithPreviousOne,
        isGroupedWithNextOne,
        currentFilter,
        onContentClicked,
        onSipUriClicked,
        onJoinConferenceClicked,
        onWebUrlClicked,
        onContactClicked,
        onRedToastToShow,
        onVoiceRecordingPlaybackEnded,
        onFileToExportToNativeGallery,
        onFileTransferCompleted
    )

    @WorkerThread
    constructor(
        chatMessage: ChatMessage,
        isFromGroup: Boolean = false,
        isGroupedWithPreviousOne: Boolean = false,
        isGroupedWithNextOne: Boolean = false,
        currentFilter: String = "",
        onContentClicked: ((fileModel: FileModel) -> Unit)? = null,
        onSipUriClicked: ((uri: String) -> Unit)? = null,
        onJoinConferenceClicked: ((uri: String) -> Unit)? = null,
        onWebUrlClicked: ((url: String) -> Unit)? = null,
        onContactClicked: ((friendRefKey: String) -> Unit)? = null,
        onRedToastToShow: ((pair: Pair<Int, Int>) -> Unit)? = null,
        onVoiceRecordingPlaybackEnded: ((id: String) -> Unit)? = null,
        onFileToExportToNativeGallery: ((path: String) -> Unit)? = null,
        onFileTransferCompleted: ((message: ChatMessage) -> Unit)? = null
    ) : this(
        null,
        chatMessage,
        isFromGroup,
        isGroupedWithPreviousOne,
        isGroupedWithNextOne,
        currentFilter,
        onContentClicked,
        onSipUriClicked,
        onJoinConferenceClicked,
        onWebUrlClicked,
        onContactClicked,
        onRedToastToShow,
        onVoiceRecordingPlaybackEnded,
        onFileToExportToNativeGallery,
        onFileTransferCompleted
    )

    companion object {
        private const val TAG = "[Event Log Model]"
    }

    val type: EventLog.Type = eventLog?.type ?: EventLog.Type.ConferenceChatMessage

    val isEvent = eventLog != null && type != EventLog.Type.ConferenceChatMessage

    val model: Any = if (isEvent) {
        EventModel(eventLog!!)
    } else {
        val chatMessage = eventLog?.chatMessage ?: directChatMessage!!
        MessageModel(
            chatMessage,
            isFromGroup,
            isGroupedWithPreviousOne,
            isGroupedWithNextOne,
            currentFilter,
            onContentClicked,
            onSipUriClicked,
            onJoinConferenceClicked,
            onWebUrlClicked,
            onContactClicked,
            onRedToastToShow,
            onVoiceRecordingPlaybackEnded,
            onFileToExportToNativeGallery,
            onFileTransferCompleted
        )
    }

    // The adapter uses this only for non-message event rows. Keep a stable placeholder for
    // a transient outgoing message, whose identity is handled by MessageModel.messageId.
    val notifyId = eventLog?.notifyId ?: -1L

    @WorkerThread
    fun destroy() {
        (model as? MessageModel)?.destroy()
    }
}
