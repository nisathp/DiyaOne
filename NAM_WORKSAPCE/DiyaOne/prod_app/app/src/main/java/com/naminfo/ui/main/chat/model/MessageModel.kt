package com.naminfo.ui.main.chat.model

import android.graphics.Typeface
import android.os.CountDownTimer
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.media.AudioFocusRequestCompat
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.core.OfflineInboxRepository
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.R
import com.naminfo.core.CustomImdn
import com.naminfo.core.OutgoingMessageRetryManager
import com.naminfo.translation.ChatTranslationLanguage
import com.naminfo.ui.main.chat.DIYA_REPLY_MESSAGE_ID_HEADER
import com.naminfo.ui.main.chat.decodeReplyPreviewEnvelope
import com.naminfo.ui.main.chat.hasReplyTarget
import com.naminfo.ui.main.chat.isForwardedMessage
import com.naminfo.ui.main.chat.resolveReplyMessageId
import org.linphone.core.Address
import org.linphone.core.ChatMessage
import org.linphone.core.ChatMessageListenerStub
import org.linphone.core.ChatMessageReaction
import org.linphone.core.ChatRoom
import org.linphone.core.ConferenceInfo
import org.linphone.core.Content
import org.linphone.core.Factory
import org.linphone.core.Player
import org.linphone.core.PlayerListener
import org.linphone.core.tools.Log
import com.naminfo.ui.main.contacts.model.ContactAvatarModel
import com.naminfo.utils.AppUtils
import com.naminfo.utils.AudioUtils
import com.naminfo.utils.Event
import com.naminfo.utils.FileUtils
import com.naminfo.utils.LinphoneUtils
import com.naminfo.utils.PatternClickableSpan
import com.naminfo.utils.SpannableClickedListener
import com.naminfo.utils.TimestampUtils
import com.naminfo.utils.VoiceDurationUtils

class MessageModel
    @WorkerThread
    constructor(
    val chatMessage: ChatMessage,
    val isFromGroup: Boolean,
    isGroupedWithPreviousOne: Boolean,
    isGroupedWithNextOne: Boolean,
    private val currentFilter: String = "",
    private val onContentClicked: ((fileModel: FileModel) -> Unit)? = null,
    private val onSipUriClicked: ((uri: String) -> Unit)? = null,
    private val onJoinConferenceClicked: ((uri: String) -> Unit)? = null,
    private val onWebUrlClicked: ((url: String) -> Unit)? = null,
    private val onContactClicked: ((friendRefKey: String) -> Unit)? = null,
    private val onRedToastToShow: ((pair: Pair<Int, Int>) -> Unit)? = null,
    private val onVoiceRecordingPlaybackEnded: ((id: String) -> Unit)? = null,
    private val onFileToExportToNativeGallery: ((path: String) -> Unit)? = null,
    private val onFileTransferCompleted: ((message: ChatMessage) -> Unit)? = null
) {
    companion object {
        private const val TAG = "[Message Model]"

        private const val SIP_URI_REGEXP = "(<?sips?:)[a-zA-Z0-9+_.\\-]+(?:@([a-zA-Z0-9+_.\\-;=~]+))+(>)?"
        private const val HTTP_LINK_REGEXP = "https?://(www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_+.~#?&/=]*)"
        private const val MENTION_REGEXP = "@([A-Za-z0-9._-]+)"

        // Core-thread-confined set of target file paths currently reserved by in-flight downloads
        // so concurrent downloads of identically named attachments never collide on one EVFS file.
        private val activeDownloadPaths = hashSetOf<String>()
    }

    val id = chatMessage.messageId

    var isRead = chatMessage.isRead

    // Queue-delivered media is created locally only because the Basic SIP SDK has no
    // public incoming-message factory.  It is nevertheless received media, so never
    // render it as an outgoing bubble or show sender delivery controls on it.
    val isOutgoing = chatMessage.isOutgoing && !CustomImdn.isQueueDeliveredIncoming(chatMessage)

    init {
        if (!isOutgoing) {
            CustomImdn.trackIncomingForDisplayed(chatMessage)
        }
    }

    val isInError = MutableLiveData<Boolean>()

    /**
     * File-transfer errors don't always turn into NotDelivered: the SIP text part can be
     * accepted while the HTTP upload fails. Keep a separate state so the outgoing media
     * bubble still exposes a small retry action beside its timestamp.
     */
    val isFileUploadFailed = MutableLiveData<Boolean>()

    val hasOutgoingFileAttachment = isOutgoing && chatMessage.contents.any {
        it.isFile || it.isFileTransfer || it.isVoiceRecording
    }

    // Queue-restored messages use the sender timestamp preserved in a custom header,
    // rather than the moment this handset happened to log back in.
    val timestampMillis = CustomImdn.timestampMillis(chatMessage)

    val timestamp = timestampMillis / 1000L

    val time = TimestampUtils.toString(timestamp)

    val hideDeliveryStatus = !isOutgoing && coreContext.core.imdnToEverybodyThreshold == 1

    val chatRoomIsReadOnly = chatMessage.chatRoom.isReadOnly ||
        (
            !chatMessage.chatRoom.hasCapability(ChatRoom.Capabilities.Encrypted.toInt()) && LinphoneUtils.getAccountForAddress(
                chatMessage.chatRoom.localAddress
            )?.params?.instantMessagingEncryptionMandatory == true
            )

    val isReply = MutableLiveData<Boolean>()

    private val replyPreviewEnvelope = chatMessage.contents
        .firstOrNull { it.isText && !it.isFile }
        ?.utf8Text
        ?.trim()
        ?.let(::decodeReplyPreviewEnvelope)
        ?.first

    val customDiyaId = CustomImdn.id(chatMessage)

    // A native reply id is generated independently on each SIP client and can occasionally
    // resolve to a different local message.  Prefer Diya's stable IMDN reference whenever it
    // is present, otherwise a text reply can inherit a thumbnail from an unrelated image.
    private val diyaReplyMessageId = chatMessage.getCustomHeader(DIYA_REPLY_MESSAGE_ID_HEADER)
        ?.takeIf { it.isNotBlank() }

    private val diyaReplyHeaderDecoded = com.naminfo.ui.main.chat.decodeReplyPreviewHeader(
        diyaReplyMessageId ?: chatMessage.replyMessageId,
        chatMessage.getCustomHeader(com.naminfo.ui.main.chat.DIYA_REPLY_PREVIEW_HEADER)
    )

    private val resolvedReplyPreviewEnvelope: com.naminfo.ui.main.chat.ReplyPreviewEnvelope? =
        replyPreviewEnvelope?.let { bodyEnv ->
            val headerEnv = diyaReplyHeaderDecoded?.first
            if (headerEnv != null && headerEnv.isImage && !bodyEnv.isImage) {
                bodyEnv.copy(isImage = true)
            } else {
                bodyEnv
            }
        } ?: diyaReplyHeaderDecoded?.first

    val replyToMessageId = diyaReplyMessageId
        ?: resolvedReplyPreviewEnvelope?.messageId
        ?: resolveReplyMessageId(chatMessage.replyMessageId, null)

    val isForward = isForwardedMessage(chatMessage)

    val replyTo = MutableLiveData<String>()

    val replyText = MutableLiveData<Spannable>()

    /** Small thumbnail shown in the quote area when the replied-to message is an image. */
    val isReplyImage = MutableLiveData<Boolean>()

    val replyImagePath = MutableLiveData<String?>()

    val avatarModel = MutableLiveData<ContactAvatarModel>()

    val groupedWithNextMessage = MutableLiveData<Boolean>()

    val groupedWithPreviousMessage = MutableLiveData<Boolean>()

    val statusIcon = MutableLiveData<Int>()

    val isEphemeral = MutableLiveData<Boolean>()

    val ephemeralLifetime = MutableLiveData<String>()

    val text = MutableLiveData<Spannable>()

    val isTextEmoji = MutableLiveData<Boolean>()

    val reactions = MutableLiveData<String>()

    val ourReactionIndex = MutableLiveData<Int>()

    val filesList = MutableLiveData<ArrayList<FileModel>>()

    val firstFileModel = MediatorLiveData<FileModel>()

    val hasBeenEdited = MutableLiveData<Boolean>()

    val hasBeenRetracted = MutableLiveData<Boolean>()

    val isSelected = MutableLiveData<Boolean>()

    @Volatile
    private var rawTextContent: String = ""

    @Volatile
    private var rawTextComputed: Boolean = false

    // Below are for conferences info
    val meetingFound = MutableLiveData<Boolean>()

    val meetingUpdated = MutableLiveData<Boolean>()

    val meetingCancelled = MutableLiveData<Boolean>()

    val meetingDay = MutableLiveData<String>()

    val meetingDayNumber = MutableLiveData<String>()

    val meetingSubject = MutableLiveData<String>()

    val meetingDate = MutableLiveData<String>()

    val meetingTime = MutableLiveData<String>()

    val meetingDescription = MutableLiveData<String>()

    val meetingParticipants = MutableLiveData<String>()

    private lateinit var meetingConferenceUri: Address
    // End of conference info related fields

    // Voice record related fields
    val isVoiceRecord = MutableLiveData<Boolean>()

    val isPlayingVoiceRecord = MutableLiveData<Boolean>()

    val voiceRecordPlayerPosition = MutableLiveData<Int>()

    val voiceRecordingDuration = MutableLiveData<Int>()

    val formattedVoiceRecordingDuration = MutableLiveData<String>()

    val dismissLongPressMenuEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    var isTextHighlighted = false

    val translatedText = MutableLiveData<Spannable?>()
    val showOriginal = MutableLiveData<Boolean>()
    val isTranslating = MutableLiveData<Boolean>()
    val effectiveTargetLanguage = MutableLiveData<ChatTranslationLanguage>()

    private var voiceRecordAudioFocusRequest: AudioFocusRequestCompat? = null

    private lateinit var voiceRecordPath: String

    private lateinit var voiceRecordPlayer: Player

    private val playerListener = PlayerListener {
        Log.i("$TAG End of file reached")
        stopVoiceRecordPlayer()
        onVoiceRecordingPlaybackEnded?.invoke(id)
    }
    // End of voice record related fields

    private lateinit var countDownTimer: CountDownTimer

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var transferringFileModel: FileModel? = null
    private var activeDownloadPath: String? = null

    @Volatile
    private var hasChatMessageListener = false

    @Volatile
    private var isDestroyed = false

    private var allFilesDownloaded = true
    private var customReceiptState: ChatMessage.State? = null
    private var fileUploadCompleted = hasOutgoingFileAttachment && (
        chatMessage.state == ChatMessage.State.FileTransferDone ||
            chatMessage.state == ChatMessage.State.Delivered ||
            chatMessage.state == ChatMessage.State.DeliveredToUser ||
            chatMessage.state == ChatMessage.State.Displayed ||
            CustomImdn.isReceiverDeliveryConfirmed(chatMessage)
        )

    private val customReceiptObserver:
                (String, ChatMessage.State) -> Unit = { messageKey, state ->
        if (isOutgoing && CustomImdn.key(chatMessage) == messageKey) {
            customReceiptState = state
            statusIcon.postValue(LinphoneUtils.getChatIconResId(state))
            isInError.postValue(false)
            if (hasOutgoingFileAttachment) {
                fileUploadCompleted = true
                isFileUploadFailed.postValue(false)
            }
        }
    }

    private fun deliveryState(): ChatMessage.State =
        customReceiptState ?: CustomImdn.effectiveState(chatMessage)

    private val chatMessageListener = object : ChatMessageListenerStub() {
        @WorkerThread
        override fun onMsgStateChanged(message: ChatMessage, messageState: ChatMessage.State?) {
            Log.i("$TAG Chat message [${message.messageId}] state changed to [$messageState]")
            if (messageState != ChatMessage.State.FileTransferDone && messageState != ChatMessage.State.FileTransferInProgress) {
                // statusIcon.postValue(LinphoneUtils.getChatIconResId(chatMessage.state))
                statusIcon.postValue(LinphoneUtils.getChatIconResId(deliveryState()))
                if (messageState == ChatMessage.State.Displayed) {
                    isRead = chatMessage.isRead
                }
            }
            // isInError.postValue(messageState == ChatMessage.State.NotDelivered)
            isInError.postValue(deliveryState() == ChatMessage.State.NotDelivered)
            if (hasOutgoingFileAttachment) {
                when (messageState) {
                    ChatMessage.State.FileTransferInProgress -> transferringFileModel?.setTransferCancelable(true)
                    ChatMessage.State.FileTransferDone,
                    ChatMessage.State.Delivered,
                    ChatMessage.State.DeliveredToUser,
                    ChatMessage.State.Displayed -> {
                        fileUploadCompleted = true
                        transferringFileModel?.setTransferCancelable(false)
                        isFileUploadFailed.postValue(false)
                    }
                    ChatMessage.State.FileTransferError -> {
                        transferringFileModel?.setTransferCancelable(false)
                        isFileUploadFailed.postValue(true)
                    }
                    ChatMessage.State.NotDelivered -> {
                        transferringFileModel?.setTransferCancelable(false)
                        isFileUploadFailed.postValue(
                            !fileUploadCompleted && deliveryState() == ChatMessage.State.NotDelivered
                        )
                    }
                    else -> Unit
                }
            }
            if (messageState == ChatMessage.State.FileTransferDone ||
                messageState == ChatMessage.State.FileTransferError ||
                messageState == ChatMessage.State.NotDelivered
            ) {
                transferringFileModel?.setTransferCancelable(false)
                if (messageState != ChatMessage.State.FileTransferDone && !message.isFileTransferInProgress) {
                    releaseActiveDownloadPath()
                    transferringFileModel?.updateTransferProgress(-1)
                    transferringFileModel = null
                }
            }
        }

        @WorkerThread
        override fun onFileTransferTerminated(message: ChatMessage, content: Content) {
            Log.i("$TAG File [${content.name}] from message [${message.messageId}] transfer terminated")
            fileTransferTerminated(message, content)
        }

        @WorkerThread
        override fun onNewMessageReaction(message: ChatMessage, reaction: ChatMessageReaction) {
            Log.i(
                "$TAG New reaction [${reaction.body}] from [${reaction.fromAddress.asStringUriOnly()}] for message with ID [$id]"
            )
            updateReactionsList()
        }

        @WorkerThread
        override fun onReactionRemoved(message: ChatMessage, address: Address) {
            Log.i("$TAG A reaction was removed for message with ID [$id]")
            updateReactionsList()
        }

        @WorkerThread
        override fun onFileTransferProgressIndication(
            message: ChatMessage,
            content: Content,
            offset: Int,
            total: Int
        ) {
            val percent = ((offset * 100.0) / total).toInt() // Conversion from int to double and back to int is required

            if (transferringFileModel == null) {
                Log.w("$TAG A file is being uploaded/downloaded but no transferringFileModel set!")
                val found = filesList.value.orEmpty().find {
                    it.fileName == content.name
                }
                if (found != null) {
                    transferringFileModel = found
                    Log.i("$TAG Found matching FileModel in files list using content name")
                } else {
                    Log.w(
                        "$TAG Failed to find a matching FileModel in files list with content name [${content.name}]"
                    )
                }
            }
            transferringFileModel?.updateTransferProgress(percent)
            transferringFileModel?.setTransferCancelable(isOutgoing && percent in 0..99)
        }

        @WorkerThread
        override fun onEphemeralMessageTimerStarted(message: ChatMessage) {
            Log.d("$TAG Ephemeral timer started")
            updateEphemeralTimer()
        }

        @WorkerThread
        override fun onContentEdited(message: ChatMessage) {
            Log.i("$TAG Message [${message.messageId}] has been edited")
            hasBeenEdited.postValue(true)
            computeContentsList()
        }

        @WorkerThread
        override fun onRetracted(message: ChatMessage) {
            Log.i("$TAG Content(s) of the message have been deleted by it's sender")
            hasBeenEdited.postValue(false)
            hasBeenRetracted.postValue(true)
            computeContentsList()
        }
    }

    init {
        updateAvatarModel()

        // // isInError.postValue(chatMessage.state == ChatMessage.State.NotDelivered)
        isInError.postValue(deliveryState() == ChatMessage.State.NotDelivered)
        isFileUploadFailed.postValue(
            hasOutgoingFileAttachment &&
                (
                    chatMessage.state == ChatMessage.State.FileTransferError ||
                        (!fileUploadCompleted && deliveryState() == ChatMessage.State.NotDelivered)
                    )
        )
        groupedWithNextMessage.postValue(isGroupedWithNextOne)
        groupedWithPreviousMessage.postValue(isGroupedWithPreviousOne)
        isPlayingVoiceRecord.postValue(false)
        isEphemeral.postValue(chatMessage.isEphemeral)
        updateEphemeralTimer()

        chatMessage.addListener(chatMessageListener)
        hasChatMessageListener = true
        // statusIcon.postValue(LinphoneUtils.getChatIconResId(chatMessage.state))
        statusIcon.postValue(LinphoneUtils.getChatIconResId(deliveryState()))
        updateReactionsList()

        hasBeenEdited.postValue(chatMessage.isEdited && !chatMessage.isRetracted)
        hasBeenRetracted.postValue(chatMessage.isRetracted)
        computeContentsList()
        if (hasReplyTarget(chatMessage.isReply, replyToMessageId)) {
            // Wait to see if original message is found before setting isReply to true
            computeReplyInfo()
        } else {
            isReply.postValue(false)
        }

        coreContext.postOnMainThread {
            firstFileModel.addSource(filesList) {
                val first = it.firstOrNull()
                if (first != null) {
                    firstFileModel.value = first!!
                }
            }
        }

        CustomImdn.addObserver(customReceiptObserver)
    }

    @Volatile
    private var highlightClearToken: Runnable? = null

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        highlightClearToken?.let(mainHandler::removeCallbacks)
        highlightClearToken = null
        transferringFileModel = null
        releaseActiveDownloadPath()
        scope.cancel()

        filesList.value.orEmpty().forEach(FileModel::destroy)

        if (::voiceRecordPlayer.isInitialized) {
            stopVoiceRecordPlayer()
            voiceRecordPlayer.removeListener(playerListener)
        }

        if (hasChatMessageListener) {
            chatMessage.removeListener(chatMessageListener)
            hasChatMessageListener = false
        }
        CustomImdn.removeObserver(customReceiptObserver)
    }

    @UiThread
    fun sendReaction(emoji: String) {
        coreContext.postOnCoreThread {
            if (chatMessage.ownReaction?.body == emoji) {
                Log.i("$TAG Removing our existing reaction [$emoji] to message with ID [$id]")
                val reaction = chatMessage.createReaction("")
                reaction.send()
            } else {
                Log.i("$TAG Sending reaction [$emoji] to message with ID [$id]")
                val reaction = chatMessage.createReaction(emoji)
                reaction.send()
            }
            dismissLongPressMenuEvent.postValue(Event(true))
        }
    }

    @UiThread
    fun resend() {
        coreContext.postOnCoreThread {
            Log.i("$TAG Re-sending message with ID [$id]")
            isFileUploadFailed.postValue(false)
            CustomImdn.prepareOutgoing(chatMessage)
            OutgoingMessageRetryManager.watch(chatMessage)
            chatMessage.send()
        }
    }

    @UiThread
    fun joinConference() {
        coreContext.postOnCoreThread {
            if (::meetingConferenceUri.isInitialized) {
                val uri = meetingConferenceUri.asStringUriOnly()
                coreContext.postOnMainThread {
                    onJoinConferenceClicked?.invoke(uri)
                }
            }
        }
    }

    @UiThread
    fun togglePlayPauseVoiceRecord() {
        coreContext.postOnCoreThread {
            if (isPlayingVoiceRecord.value == false) {
                startVoiceRecordPlayer()
            } else {
                pauseVoiceRecordPlayer()
            }
        }
    }

    @UiThread
    fun markAsRead() {
        isRead = true
        coreContext.postOnCoreThread {
            Log.i("$TAG Marking chat message with ID [$id] as read")
            chatMessage.markAsRead()
            CustomImdn.messageRead(chatMessage)
        }
    }

    @WorkerThread
    fun updateAvatarModel() {
        val avatar = coreContext.contactsManager.getContactAvatarModelForAddress(
            chatMessage.fromAddress
        )
        avatarModel.postValue(avatar)
    }

    @WorkerThread
    private fun extractRawTextFromContents(): String {
        val textContent = chatMessage.contents.find { it.isText && !it.isFile }
        val content = textContent?.utf8Text.orEmpty().trim()
        val decoded = LinphoneUtils.stripMessageEnvelopes(content)
        if (decoded.isNotEmpty() && !LinphoneUtils.isFileTransferMetadata(decoded)) {
            return decoded
        }
        return diyaReplyHeaderDecoded?.second.orEmpty().trim()
    }

    @AnyThread
    fun getRawTextContent(): String {
        if (rawTextComputed) return rawTextContent
        val computed = extractRawTextFromContents()
        rawTextContent = computed
        rawTextComputed = true
        return computed
    }

    @AnyThread
    fun getCachedRawTextContent(): String? = if (rawTextComputed) rawTextContent else null

    @WorkerThread
    private fun computeContentsList() {
        Log.d("$TAG Computing message contents list")
        rawTextContent = extractRawTextFromContents()
        rawTextComputed = true
        text.postValue(Spannable.Factory.getInstance().newSpannable(""))
        filesList.value.orEmpty().forEach(FileModel::destroy)
        var textContentComputed = false

        if (chatMessage.isRetracted) {
            meetingFound.postValue(false)
            isVoiceRecord.postValue(false)
            isTextEmoji.postValue(false)
        }

        var displayableContentFound = false
        var contentIndex = 0
        val filesPath = arrayListOf<FileModel>()

        val contents = chatMessage.contents
        allFilesDownloaded = true
        val exactly4Contents = contents.count {
            it.isFile || it.isFileTransfer
        } == 4

        for (content in contents) {
            val isFileEncrypted = content.isFileEncrypted

            if (content.isIcalendar) {
                Log.d("$TAG Found iCal content")
                parseConferenceInvite(content)

                displayableContentFound = true
            } else if (content.isText && !content.isFile) {
                Log.d("$TAG Found plain text content")
                computeTextContent(content, currentFilter)
                textContentComputed = true

                displayableContentFound = true
            } else if (content.isVoiceRecording) {
                Log.d("$TAG Found voice recording content")
                isVoiceRecord.postValue(true)
                computeVoiceRecordContent(content)

                displayableContentFound = true
            } else {
                val wrapBefore = if (exactly4Contents) {
                    contentIndex == 2 // To have a 2x2 grid
                } else {
                    contentIndex % 3 == 0 // To have at most 3 columns
                }
                if (content.isFile) {
                    Log.d("$TAG Found file content with type [${content.type}/${content.subtype}]")
                    contentIndex += 1

                    checkAndRepairFilePathIfNeeded(content)

                    val originalPath = content.filePath.orEmpty()
                    val path = if (isFileEncrypted) {
                        Log.d(
                            "$TAG [VFS] Content is encrypted, requesting plain file path for file [${content.filePath}]"
                        )
                        content.exportPlainFile()
                    } else {
                        originalPath
                    }
                    var name = content.name.orEmpty()
                    if (name.isEmpty()) {
                        val rawText = chatMessage.contents.find { it.isText }?.utf8Text.orEmpty()
                        name = LinphoneUtils.extractFileNameFromPayload(rawText).orEmpty()
                    }
                    if (name.isEmpty() && path.isNotEmpty()) {
                        name = FileUtils.getNameFromFilePath(path)
                    }
                    if (path.isNotEmpty()) {
                        Log.d(
                            "$TAG Found file ready to be displayed [$path] with MIME [${content.type}/${content.subtype}] for message [${chatMessage.messageId}]"
                        )
                        val fileSize = if (content.fileSize.toLong() > 0) {
                            content.fileSize.toLong()
                        } else {
                            FileUtils.getFileSize(path)
                        }
                        val timestamp = content.creationTimestamp
                        val fileModel = FileModel(
                            path,
                            name,
                            fileSize,
                            timestamp,
                            isFileEncrypted,
                            originalPath,
                            chatMessage.isEphemeral,
                            flexboxLayoutWrapBefore = wrapBefore,
                            onTransferCancelled = { cancelOutgoingFileTransfer() }
                        ) { model ->
                            onContentClicked?.invoke(model)
                        }
                        filesPath.add(fileModel)

                        displayableContentFound = true
                    } else {
                        Log.e("$TAG No path found for File Content!")
                    }
                } else if (content.isFileTransfer) {
                    Log.d(
                        "$TAG Found file content (not downloaded yet) with type [${content.type}/${content.subtype}] and name [${content.name}]"
                    )
                    allFilesDownloaded = false
                    contentIndex += 1
                    var name = content.name.orEmpty()
                    if (name.isEmpty()) {
                        val rawText = chatMessage.contents.find { it.isText }?.utf8Text.orEmpty()
                        name = LinphoneUtils.extractFileNameFromPayload(rawText).orEmpty()
                    }
                    if (name.isEmpty()) {
                        name = "Attachment"
                    }
                    val timestamp = content.creationTimestamp
                    if (name.isNotEmpty()) {
                        val fileModel = if (isOutgoing && chatMessage.isFileTransferInProgress) {
                            val path = content.filePath.orEmpty()
                            val fileSize = if (content.fileSize.toLong() > 0) {
                                content.fileSize.toLong()
                            } else {
                                FileUtils.getFileSize(path)
                            }
                            FileModel(
                                path,
                                name,
                                fileSize,
                                timestamp,
                                isFileEncrypted,
                                path,
                                chatMessage.isEphemeral,
                                flexboxLayoutWrapBefore = wrapBefore
                            ) { model ->
                                onContentClicked?.invoke(model)
                            }
                        } else {
                            FileModel(
                                name,
                                name,
                                content.fileSize.toLong(),
                                timestamp,
                                isFileEncrypted,
                                name,
                                chatMessage.isEphemeral,
                                isWaitingToBeDownloaded = true,
                                flexboxLayoutWrapBefore = wrapBefore
                            ) { model ->
                                downloadContent(model, content)
                            }
                        }
                        filesPath.add(fileModel)

                        displayableContentFound = true
                    } else {
                        Log.e("$TAG No name found for FileTransfer Content!")
                    }
                } else {
                    Log.w("$TAG Content [${content.name}] is not a File")
                }
            }
        }

        // Queue-delivered messages retain only trusted HFT metadata in their local event. Build
        // attachment placeholders here, so their file bytes are downloaded only on a user tap.
        val queuedMedia = OfflineInboxRepository.queuedMedia(chatMessage)
        if (queuedMedia.isNotEmpty()) {
            allFilesDownloaded = queuedMedia.all { it.localPath != null }
            val exactly4QueuedMedia = queuedMedia.size == 4
            queuedMedia.forEachIndexed { index, media ->
                val localPath = media.localPath
                val fileModel = FileModel(
                    localPath ?: media.name,
                    media.name,
                    media.size,
                    timestamp,
                    false,
                    localPath ?: media.name,
                    chatMessage.isEphemeral,
                    isWaitingToBeDownloaded = localPath == null,
                    flexboxLayoutWrapBefore = if (exactly4QueuedMedia) index == 2 else index % 3 == 0
                ) { model ->
                    val downloaded = media.localPath ?: model.path.takeIf { it.isNotBlank() && File(it).isFile }
                    if (downloaded != null) {
                        model.markDownloaded(downloaded)
                        onContentClicked?.invoke(model)
                    } else {
                        if (!model.tryStartDownload()) return@FileModel
                        model.updateTransferProgress(0)
                        OfflineInboxRepository.downloadQueuedMedia(media) { path ->
                            if (path != null) {
                                model.markDownloaded(path)
                                coreContext.postOnCoreThread {
                                    onFileTransferCompleted?.invoke(chatMessage)
                                }
                            } else {
                                model.updateTransferProgress(-1)
                            }
                        }
                    }
                }
                filesPath.add(fileModel)
                displayableContentFound = true
            }
        }

        filesList.postValue(filesPath)

        val headerCaption = diyaReplyHeaderDecoded?.second.orEmpty().trim()
        if (!textContentComputed && headerCaption.isNotEmpty()) {
            applyResolvedTextContent(headerCaption, currentFilter)
            displayableContentFound = true
        }

        if (!displayableContentFound) { // Temporary workaround to prevent empty bubbles
            val describe = LinphoneUtils.getFormattedTextDescribingMessage(chatMessage)
            Log.w(
                "$TAG No displayable content found, generating text based description [$describe]"
            )
            text.postValue(describe)
        }
    }

    @WorkerThread
    private fun releaseActiveDownloadPath() {
        activeDownloadPath?.let { path ->
            activeDownloadPaths.remove(path)
            activeDownloadPath = null
        }
    }

    @AnyThread
    private fun downloadContent(model: FileModel, content: Content) {
        if (!model.tryStartDownload()) {
            Log.w("$TAG Download already in progress for [${model.fileName}]")
            return
        }

        Log.i("$TAG Start downloading content for file [${model.fileName}]")
        model.updateTransferProgress(0)

        coreContext.postOnCoreThread {
            if (isDestroyed) {
                model.updateTransferProgress(-1)
                return@postOnCoreThread
            }
            if (content.isFile) {
                Log.i("$TAG Content [${content.name}] already completed at [${content.filePath}]")
                model.updateTransferProgress(-1)
                computeContentsList()
                return@postOnCoreThread
            }
            if (chatMessage.isFileTransferInProgress) {
                if (transferringFileModel == null && !isOutgoing && content.isFileTransfer) {
                    Log.i("$TAG Attaching active download to [${model.fileName}] for message [${chatMessage.messageId}]")
                    transferringFileModel = model
                } else {
                    Log.w("$TAG File transfer already in progress for message [${chatMessage.messageId}], ignoring duplicate download request")
                    if (transferringFileModel !== model) {
                        model.updateTransferProgress(-1)
                    }
                }
                return@postOnCoreThread
            }

            val contentName = content.name
            if (contentName == null) {
                Log.e("$TAG Content name is null, can't download it!")
                model.updateTransferProgress(-1)
                return@postOnCoreThread
            }

            val currentPath = content.filePath.orEmpty()
            val currentFile = currentPath.takeIf { it.isNotBlank() }?.let(::File)
            if (currentFile?.exists() == true && !activeDownloadPaths.contains(currentFile.absolutePath)) {
                // Clean up any stale or partial leftover from an aborted transfer so EVFS never
                // attempts AES-GCM chunk decryption on incomplete bytes.
                currentFile.delete()
            }

            // Parsed HFT XML may set a destination before any bytes are fetched. That is a
            // pending transfer, not a completed download; reserve a non-colliding writable path.
            val file = FileUtils.getFileStoragePath(
                contentName,
                isImage = FileUtils.isExtensionImage(contentName),
                excludedPaths = activeDownloadPaths
            )
            if (file.exists()) {
                file.delete()
            }
            activeDownloadPaths.add(file.absolutePath)
            activeDownloadPath = file.absolutePath
            content.filePath = file.path
            Log.i("$TAG File [$contentName] will be downloaded at [${content.filePath}]")

            transferringFileModel = model
            try {
                chatMessage.downloadContent(content)
            } catch (exception: Exception) {
                Log.e("$TAG Failed to start download for [$contentName]: ${exception.message}")
                releaseActiveDownloadPath()
                transferringFileModel = null
                model.updateTransferProgress(-1)
            }
        }
    }

    @AnyThread
    private fun cancelOutgoingFileTransfer() {
        coreContext.postOnCoreThread {
            if (!isOutgoing || !chatMessage.isFileTransferInProgress) return@postOnCoreThread
            try {
                Log.i("$TAG Cancelling upload for message [${chatMessage.messageId}]")
                chatMessage.cancelFileTransfer()
                transferringFileModel?.setTransferCancelable(false)
                transferringFileModel?.updateTransferProgress(-1)
            } catch (exception: Exception) {
                Log.w("$TAG Couldn't cancel file upload: ${exception.message}")
            }
        }
    }

    @WorkerThread
    private fun updateReactionsList() {
        var reactionsList = ""
        val allReactions = chatMessage.reactions

        var sameReactionTwiceOrMore = false
        if (allReactions.isNotEmpty()) {
            for (reaction in allReactions) {
                val body = reaction.body
                if (!reactionsList.contains(body)) {
                    reactionsList += body
                } else {
                    sameReactionTwiceOrMore = true
                }
            }

            if (sameReactionTwiceOrMore) {
                val count = allReactions.size.toString()
                reactionsList += " $count"
            }
        }
        Log.d("$TAG Reactions for message [$id] are [$reactionsList]")

        val ourOwnReaction = chatMessage.ownReaction
        if (ourOwnReaction != null) {
            val index = when (ourOwnReaction.body) {
                AppUtils.getString(R.string.emoji_thumbs_up) -> 0
                AppUtils.getString(R.string.emoji_love) -> 1
                AppUtils.getString(R.string.emoji_laughing) -> 2
                AppUtils.getString(R.string.emoji_surprised) -> 3
                AppUtils.getString(R.string.emoji_tear) -> 4
                else -> -1
            }
            ourReactionIndex.postValue(index)
        } else {
            ourReactionIndex.postValue(-1)
        }

        reactions.postValue(reactionsList)
    }

    @WorkerThread
    fun highlightText(highlight: String) {
        if (isTextHighlighted && highlight.isEmpty()) {
            isTextHighlighted = false
        }

        val textContent = chatMessage.contents.find {
            it.isText
        }
        if (textContent != null) {
            computeTextContent(textContent, highlight)
        }
        isSelected.postValue(highlight.isNotEmpty())
    }

    @UiThread
    fun flashHighlight(durationMs: Long = 1800L) {
        highlightClearToken?.let(mainHandler::removeCallbacks)
        isSelected.value = true
        val token = Runnable {
            if (!isTextHighlighted) {
                isSelected.value = false
            }
            highlightClearToken = null
        }
        highlightClearToken = token
        mainHandler.postDelayed(token, durationMs)
    }

    @WorkerThread
    fun computeReplyInfo() {
        val envelope = resolvedReplyPreviewEnvelope
        val hasStableDiyaReference = diyaReplyMessageId != null || envelope != null
        val candidateReferences = listOfNotNull(
            replyToMessageId,
            diyaReplyMessageId,
            envelope?.messageId,
            chatMessage.replyMessageId
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val replyMessage = candidateReferences.firstNotNullOfOrNull(::findReplyMessage)
            ?: chatMessage.replyMessage?.takeIf { !hasStableDiyaReference || envelope?.isImage == true }
        if (replyMessage != null) {
            val from = replyMessage.fromAddress
            replyTo.postValue(coreContext.contactsManager.findDisplayName(from))
            val described = LinphoneUtils.getFormattedTextDescribingMessage(replyMessage)
            replyText.postValue(
                if (described.isBlank() && !envelope?.text.isNullOrBlank()) {
                    android.text.SpannableString(
                        LinphoneUtils.stripMessageEnvelopes(envelope.text)
                    )
                } else {
                    described
                }
            )
            updateReplyImagePreview(replyMessage)
            isReply.postValue(true)
        } else {
            if (envelope != null) {
                replyTo.postValue(resolveReplyPreviewSenderName(envelope.sender))
                val visibleEnvelopeText = LinphoneUtils.stripMessageEnvelopes(envelope.text)
                replyText.postValue(SpannableString(visibleEnvelopeText))
                val envelopeIsImage = envelope.isImage || FileUtils.isExtensionImage(visibleEnvelopeText)
                isReplyImage.postValue(envelopeIsImage)
                replyImagePath.postValue(null)
                isReply.postValue(true)
            } else {
                Log.w("$TAG Failed to find the reply message from ID [${chatMessage.replyMessageId}]")
                isReplyImage.postValue(false)
                replyImagePath.postValue(null)
                isReply.postValue(false)
            }
        }
    }

    @WorkerThread
    private fun updateReplyImagePreview(replyMessage: ChatMessage) {
        val image = replyMessage.contents.firstOrNull { content ->
            (content.isFile || content.isFileTransfer) &&
                (
                    content.type.equals("image", ignoreCase = true) ||
                        FileUtils.isExtensionImage(content.name.orEmpty()) ||
                        FileUtils.isExtensionImage(content.filePath.orEmpty())
                    )
        }
        val contentImagePath = image?.let(::resolveReadableReplyImagePath)
        if (contentImagePath != null) {
            isReplyImage.postValue(true)
            replyImagePath.postValue(contentImagePath)
            return
        }

        val queuedImage = OfflineInboxRepository.queuedMedia(replyMessage).firstOrNull { media ->
            media.mimeType.startsWith("image/", ignoreCase = true) ||
                FileUtils.isExtensionImage(media.name)
        }
        val queuedLocalPath = queuedImage?.localPath?.takeIf { File(it).isFile && File(it).length() > 0L }
            ?: OfflineInboxRepository.findDownloadedMediaPath(
                CustomImdn.id(replyMessage),
                replyMessage.messageId
            )?.takeIf { FileUtils.isExtensionImage(it) }
        if (queuedLocalPath != null) {
            isReplyImage.postValue(true)
            replyImagePath.postValue(queuedLocalPath)
            return
        }

        if (image != null || queuedImage != null) {
            isReplyImage.postValue(true)
            replyImagePath.postValue(null)
            return
        }

        val envelope = resolvedReplyPreviewEnvelope
        val fallbackIsImage = envelope?.isImage == true ||
            FileUtils.isExtensionImage(envelope?.text.orEmpty())
        isReplyImage.postValue(fallbackIsImage)
        replyImagePath.postValue(null)
    }

    @WorkerThread
    private fun resolveReadableReplyImagePath(image: Content): String? {
        val rawPath = image.filePath?.takeIf { it.isNotBlank() } ?: return null
        val rawFile = File(rawPath)
        if (!rawFile.isFile || rawFile.length() <= 0L) return null
        if (!image.isFileEncrypted) return rawPath

        return try {
            val plainPath = image.exportPlainFile()?.takeIf { it.isNotBlank() && File(it).isFile }
                ?: return null
            // Cache a copy outside the VFS plain-file temp path so FileModel.destroy() on scroll
            // or reload cannot erase the reply quote's thumbnail underneath Coil.
            val previewDir = File(coreContext.context.cacheDir, "reply_previews").apply { mkdirs() }
            val ext = FileUtils.getExtensionFromFileName(image.name.orEmpty().ifBlank { rawFile.name })
                .ifBlank { "jpg" }
            val key = Integer.toHexString(rawPath.hashCode())
            val cachedPreview = File(previewDir, "rp_$key.$ext")
            if (!cachedPreview.isFile || cachedPreview.length() == 0L) {
                File(plainPath).inputStream().use { input ->
                    cachedPreview.outputStream().use { output -> input.copyTo(output) }
                }
            }
            cachedPreview.takeIf { it.isFile && it.length() > 0L }?.absolutePath ?: plainPath
        } catch (e: Exception) {
            Log.w("$TAG Failed to export encrypted reply image preview [$rawPath]: ${e.message}")
            null
        }
    }

    /**
     * Native SIP message IDs are local to a handset. New Diya messages carry the stable IMDN
     * UUID in the reply header, so resolve both forms across sibling 1-to-1 Basic rooms when
     * rendering a reply on either party.
     */
    @WorkerThread
    private fun findReplyMessage(reference: String): ChatMessage? {
        val searchRooms = LinphoneUtils.findSiblingOneToOneChatRooms(
            coreContext.core,
            chatMessage.chatRoom
        )
        for (room in searchRooms) {
            room.findMessage(reference)?.let { return it }
        }
        // Check a bounded recent tail before falling back to full history so computing
        // replies on conversation load does not churn unbounded native EventLog[] arrays.
        for (limit in intArrayOf(40, 0)) {
            for (room in searchRooms) {
                val historyEvents = if (room.hasCapability(ChatRoom.Capabilities.Basic.toInt())) {
                    room.getHistoryMessageEvents(limit)
                } else {
                    room.getHistoryEvents(limit)
                }
                historyEvents
                    .asSequence()
                    .mapNotNull { it.chatMessage }
                    .lastOrNull { candidate ->
                        candidate.messageId == reference || CustomImdn.id(candidate) == reference
                    }
                    ?.let { return it }
            }
        }
        return null
    }

    @UiThread
    fun candidateReplyReferences(): List<String> = listOfNotNull(
        replyToMessageId,
        diyaReplyMessageId,
        resolvedReplyPreviewEnvelope?.messageId,
        chatMessage.replyMessageId
    ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    @UiThread
    fun matchesReplyReference(reference: String): Boolean =
        id == reference || customDiyaId == reference

    @WorkerThread
    fun referencesOriginalMessage(original: ChatMessage): Boolean {
        val originalSipId = original.messageId.takeIf { it.isNotBlank() }
        val originalDiyaId = CustomImdn.id(original)?.takeIf { it.isNotBlank() }
        val targets = listOfNotNull(
            replyToMessageId,
            diyaReplyMessageId,
            resolvedReplyPreviewEnvelope?.messageId,
            chatMessage.replyMessageId
        ).filter { it.isNotBlank() }
        return (originalSipId != null && originalSipId in targets) ||
            (originalDiyaId != null && originalDiyaId in targets)
    }

    @WorkerThread
    private fun resolveReplyPreviewSenderName(sender: String): String {
        val room = chatMessage.chatRoom
        val peerAddress = room.peerAddress
        val localAddress = room.localAddress
        val address = when {
            sender.equals(peerAddress.asStringUriOnly(), ignoreCase = true) ||
                sender.equals(peerAddress.username, ignoreCase = true) -> peerAddress
            sender.equals(localAddress.asStringUriOnly(), ignoreCase = true) ||
                sender.equals(localAddress.username, ignoreCase = true) -> localAddress
            else -> coreContext.core.interpretUrl(sender, false)
        } ?: return sender

        return coreContext.contactsManager.findDisplayName(address)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: sender
    }

    @WorkerThread
    private fun computeTextContent(content: Content, highlight: String) {
        val messageText = content.utf8Text.orEmpty().trim()
        val decodedText = LinphoneUtils.stripMessageEnvelopes(messageText)
        if (LinphoneUtils.isFileTransferMetadata(decodedText)) {
            val headerCaption = diyaReplyHeaderDecoded?.second.orEmpty().trim()
            if (headerCaption.isEmpty()) {
                rawTextContent = ""
                text.postValue(Spannable.Factory.getInstance().newSpannable(""))
                return
            }
            applyResolvedTextContent(headerCaption, highlight)
            return
        }
        applyResolvedTextContent(decodedText, highlight)
    }

    @WorkerThread
    private fun applyResolvedTextContent(decodedText: String, highlight: String) {
        rawTextContent = decodedText
        val spannableBuilder = SpannableStringBuilder(rawTextContent)

        val emojiOnly = AppUtils.isTextOnlyContainsEmoji(rawTextContent)
        isTextEmoji.postValue(emojiOnly)
        if (emojiOnly) {
            text.postValue(spannableBuilder)
            return
        }

        // Check for mentions
        val chatRoom = chatMessage.chatRoom
        val matcher = Pattern.compile(MENTION_REGEXP).matcher(rawTextContent)
        var offset = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val source = rawTextContent.subSequence(start + 1, end) // +1 to remove @
            Log.d("$TAG Found mention [$source]")

            // Find address matching username
            val address = if (chatRoom.localAddress.username == source) {
                coreContext.core.accountList.find {
                    it.params.identityAddress?.username == source
                }?.params?.identityAddress
            } else if (chatRoom.peerAddress.username == source) {
                chatRoom.peerAddress
            } else {
                chatRoom.participants.find {
                    it.address.username == source
                }?.address
            }
            // Find display name for address
            if (address != null) {
                val avatarModel = coreContext.contactsManager.getContactAvatarModelForAddress(
                    address
                )
                val friend = avatarModel.friend
                val displayName = friend.name ?: LinphoneUtils.getDisplayName(address)
                Log.i(
                    "$TAG Using display name [$displayName] instead of mention username [$source]"
                )

                spannableBuilder.replace(start + offset, end + offset, "@$displayName")
                val span = PatternClickableSpan.StyledClickableSpan(
                    object : SpannableClickedListener {
                        @UiThread
                        override fun onSpanClicked(text: String) {
                            val friendRefKey = friend.refKey ?: ""
                            Log.i(
                                "$TAG Clicked on [$text] span, matching friend ref key is [$friendRefKey]"
                            )
                            if (friendRefKey.isNotEmpty()) {
                                onContactClicked?.invoke(friendRefKey)
                            }
                        }
                    }
                )
                spannableBuilder.setSpan(
                    span,
                    start + offset,
                    start + offset + displayName.length + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // Change color
                spannableBuilder.setSpan(
                    ForegroundColorSpan(AppUtils.getColorInt(R.color.mineral_blue_main_500)),
                    start + offset,
                    start + offset + displayName.length + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                offset += displayName.length - source.length
            }
        }

        // Add clickable span for SIP URIs
        text.postValue(
            PatternClickableSpan()
                .add(
                    Pattern.compile(
                        SIP_URI_REGEXP
                    ),
                    object : SpannableClickedListener {
                        @UiThread
                        override fun onSpanClicked(text: String) {
                            coreContext.postOnCoreThread {
                                Log.i("$TAG Clicked on SIP URI: $text")
                                onSipUriClicked?.invoke(text)
                            }
                        }
                    }
                )
                .add(
                    Pattern.compile(
                        HTTP_LINK_REGEXP
                    ),
                    object : SpannableClickedListener {
                        @UiThread
                        override fun onSpanClicked(text: String) {
                            Log.i("$TAG Clicked on web URL: $text")
                            onWebUrlClicked?.invoke(text)
                        }
                    }
                )
                .build(spannableBuilder)
        )

        // Check for search
        if (highlight.isNotEmpty()) {
            val indexStart = rawTextContent.indexOf(highlight, 0, ignoreCase = true)
            if (indexStart >= 0) {
                isTextHighlighted = true
                val indexEnd = indexStart + highlight.length
                spannableBuilder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    indexStart,
                    indexEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    @WorkerThread
    private fun parseConferenceInvite(content: Content) {
        val conferenceInfo = Factory.instance().createConferenceInfoFromIcalendarContent(content)
        val conferenceAddress = conferenceInfo?.uri
        val conferenceUri = conferenceAddress?.asStringUriOnly()
        if (conferenceInfo != null && conferenceAddress != null) {
            Log.i(
                "$TAG Found conference info with URI [$conferenceUri] and subject [${conferenceInfo.subject}]"
            )
            meetingConferenceUri = conferenceAddress

            meetingSubject.postValue(conferenceInfo.subject)
            meetingDescription.postValue(conferenceInfo.description)

            meetingUpdated.postValue(conferenceInfo.state == ConferenceInfo.State.Updated)
            meetingCancelled.postValue(conferenceInfo.state == ConferenceInfo.State.Cancelled)

            val timestamp = conferenceInfo.dateTime
            val duration = conferenceInfo.duration
            val date = TimestampUtils.toString(
                timestamp,
                onlyDate = true,
                shortDate = false,
                hideYear = false
            )
            val startTime = TimestampUtils.timeToString(timestamp)
            val end = timestamp + (duration * 60)
            val endTime = TimestampUtils.timeToString(end)
            meetingDate.postValue(date)
            meetingTime.postValue("$startTime - $endTime")

            meetingDay.postValue(TimestampUtils.dayOfWeek(timestamp))
            meetingDayNumber.postValue(TimestampUtils.dayOfMonth(timestamp))

            val count = conferenceInfo.participantInfos.size
            meetingParticipants.postValue(
                AppUtils.getStringWithPlural(
                    R.plurals.conference_participants_list_title,
                    count,
                    "$count"
                )
            )

            meetingFound.postValue(true)
        }
    }

    @WorkerThread
    private fun initVoiceRecordPlayer() {
        if (!::voiceRecordPath.isInitialized) {
            Log.e("$TAG No voice record path was set!")
            return
        }

        Log.i("$TAG Creating player for voice record")

        val playbackSoundCard = AudioUtils.getAudioPlaybackDeviceIdForCallRecordingOrVoiceMessage()
        Log.i(
            "$TAG Using device $playbackSoundCard to make the voice message playback"
        )

        val localPlayer = coreContext.core.createLocalPlayer(playbackSoundCard, null, null)
        if (localPlayer != null) {
            voiceRecordPlayer = localPlayer
        } else {
            Log.e("$TAG Couldn't create local player!")
            return
        }
        voiceRecordPlayer.addListener(playerListener)
        Log.i("$TAG Voice record player created")

        val path = voiceRecordPath
        Log.i("$TAG Opening voice record file [$path]")
        if (voiceRecordPlayer.open(path) == 0) {
            val duration = voiceRecordPlayer.duration
            voiceRecordingDuration.postValue(duration)
            val formattedDuration = VoiceDurationUtils.format(duration)
            formattedVoiceRecordingDuration.postValue(formattedDuration)
        } else {
            Log.e("$TAG Player failed to open file at [$path]")
        }
    }

    @WorkerThread
    private fun startVoiceRecordPlayer() {
        if (voiceRecordAudioFocusRequest == null) {
            voiceRecordAudioFocusRequest = AudioUtils.acquireAudioFocusForVoiceRecordingOrPlayback(
                coreContext.context
            )
        }

        if (isPlayerClosed()) {
            Log.w("$TAG Player closed, let's open it first")
            initVoiceRecordPlayer()

            if (voiceRecordPlayer.state == Player.State.Closed) {
                Log.e("$TAG It seems the player fails to open the file, abort playback")
                onRedToastToShow?.invoke(
                    Pair(
                        R.string.conversation_failed_to_play_voice_recording_message,
                        R.drawable.warning_circle
                    )
                )
                return
            }
        }

        val lowMediaVolume = AudioUtils.isMediaVolumeLow(coreContext.context)
        if (lowMediaVolume) {
            Log.w("$TAG Media volume is low, notifying user as they may not hear voice message")
            onRedToastToShow?.invoke(
                Pair(R.string.media_playback_low_volume_warning_toast, R.drawable.speaker_slash)
            )
        }

        Log.i("$TAG Playing voice record")
        isPlayingVoiceRecord.postValue(true)
        voiceRecordPlayer.start()

        playerTickerFlow().onEach {
            coreContext.postOnCoreThread {
                voiceRecordPlayerPosition.postValue(voiceRecordPlayer.currentPosition)
            }
        }.launchIn(scope)
    }

    @WorkerThread
    private fun pauseVoiceRecordPlayer() {
        if (!isPlayerClosed()) {
            Log.i("$TAG Pausing voice record")
            voiceRecordPlayer.pause()
        }

        val request = voiceRecordAudioFocusRequest
        if (request != null) {
            AudioUtils.releaseAudioFocusForVoiceRecordingOrPlayback(
                coreContext.context,
                request
            )
            voiceRecordAudioFocusRequest = null
        }

        isPlayingVoiceRecord.postValue(false)
    }

    @WorkerThread
    private fun isPlayerClosed(): Boolean {
        return !::voiceRecordPlayer.isInitialized || voiceRecordPlayer.state == Player.State.Closed
    }

    @WorkerThread
    private fun stopVoiceRecordPlayer() {
        if (!isPlayerClosed()) {
            Log.i("$TAG Stopping voice record")
            voiceRecordPlayer.pause()
            voiceRecordPlayer.seek(0)
            voiceRecordPlayerPosition.postValue(0)
            voiceRecordPlayer.close()
        }

        voiceRecordPlayerPosition.postValue(0)
        isPlayingVoiceRecord.postValue(false)

        val request = voiceRecordAudioFocusRequest
        if (request != null) {
            AudioUtils.releaseAudioFocusForVoiceRecordingOrPlayback(
                coreContext.context,
                request
            )
            voiceRecordAudioFocusRequest = null
        }

        isPlayingVoiceRecord.postValue(false)
    }

    private fun playerTickerFlow() = flow {
        while (isPlayingVoiceRecord.value == true) {
            emit(Unit)
            delay(10)
        }
    }

    @WorkerThread
    private fun checkAndRepairFilePathIfNeeded(content: Content): String {
        val path = content.filePath ?: ""
        if (path.isEmpty()) return ""
        val name = content.name ?: ""
        if (name.isEmpty()) return ""

        val extension = FileUtils.getExtensionFromFileName(path)
        if (extension.contains("/")) {
            Log.w(
                "$TAG Weird extension [$extension] found for file [$path], trying with file name [$name]"
            )
            val fileExtension = FileUtils.getExtensionFromFileName(name)
            if (!fileExtension.contains("/")) {
                Log.w("$TAG File extension [$fileExtension] seems better, renaming file")
                val newPath = FileUtils.renameFile(path, name)
                if (newPath.isNotEmpty()) {
                    content.filePath = newPath
                    Log.w("$TAG File [$path] has been renamed [${content.filePath}]")
                    return newPath
                } else {
                    Log.e("$TAG Failed to rename file!")
                }
            }
        }

        return ""
    }

    @WorkerThread
    private fun updateEphemeralTimer() {
        if (chatMessage.isEphemeral) {
            if (chatMessage.ephemeralExpireTime == 0L) {
                // This means the message hasn't been read by all participants yet, so the countdown hasn't started
                // In this case we simply display the configured value for lifetime
                ephemeralLifetime.postValue(
                    TimestampUtils.formatLifetime(chatMessage.ephemeralLifetime)
                )
            } else {
                // Countdown has started, display remaining time
                val remaining = chatMessage.ephemeralExpireTime - (System.currentTimeMillis() / 1000)
                ephemeralLifetime.postValue(TimestampUtils.formatLifetime(remaining))
                if (!::countDownTimer.isInitialized) {
                    countDownTimer = object : CountDownTimer(remaining * 1000, 1000) {
                        override fun onFinish() {}

                        override fun onTick(millisUntilFinished: Long) {
                            ephemeralLifetime.postValue(
                                TimestampUtils.formatLifetime(millisUntilFinished / 1000)
                            )
                        }
                    }
                    countDownTimer.start()
                }
            }
        }
    }

    @WorkerThread
    private fun computeVoiceRecordContent(content: Content) {
        voiceRecordPath = content.filePath ?: ""

        val duration = content.fileDuration
        voiceRecordingDuration.postValue(duration)

        val formattedDuration = VoiceDurationUtils.format(duration)
        formattedVoiceRecordingDuration.postValue(formattedDuration)
        Log.i(
            "$TAG Found voice record with path [$voiceRecordPath] and duration [$formattedDuration]"
        )
    }

    @WorkerThread
    private fun fileTransferTerminated(message: ChatMessage, content: Content) {
        releaseActiveDownloadPath()
        // Never do auto media export for ephemeral messages!
        if (corePreferences.makePublicMediaFilesDownloaded && !message.isEphemeral) {
            val path = content.filePath
            if (!path.isNullOrEmpty()) {
                val mime = "${content.type}/${content.subtype}"
                val mimeType = FileUtils.getMimeType(mime)
                when (mimeType) {
                    FileUtils.MimeType.Image, FileUtils.MimeType.Video, FileUtils.MimeType.Audio -> {
                        Log.i("$TAG Exporting file path [$path] to the native media gallery")
                        onFileToExportToNativeGallery?.invoke(path)
                    }
                    else -> {}
                }
            }
        }

        transferringFileModel?.updateTransferProgress(-1)
        transferringFileModel = null
        if (!allFilesDownloaded) {
            computeContentsList()
        } else {
            for (content in message.contents) {
                if (content.isVoiceRecording) {
                    Log.i("$TAG File transfer done, updating voice record info")
                    computeVoiceRecordContent(content)
                    break
                }
            }
        }
        onFileTransferCompleted?.invoke(message)
    }

    @UiThread
    fun toggleShowOriginal() {
        val current = showOriginal.value ?: false
        showOriginal.postValue(!current)
    }

    @UiThread
    fun applyTranslation(translated: String, targetLanguage: ChatTranslationLanguage) {
        effectiveTargetLanguage.value = targetLanguage
        translatedText.value = Spannable.Factory.getInstance().newSpannable(translated)
        isTranslating.value = false
        showOriginal.value = false
    }
}
