package com.naminfo.ui.main.chat

import androidx.annotation.WorkerThread
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.R
import com.naminfo.core.CustomImdn
import com.naminfo.core.FileTransferApiLogger
import com.naminfo.core.OfflineInboxRepository
import com.naminfo.core.OutgoingMessageRetryManager
import com.naminfo.ui.main.chat.model.MessageModel
import com.naminfo.utils.AppUtils
import com.naminfo.utils.FileUtils
import com.naminfo.utils.LinphoneUtils
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.ChatMessage
import org.linphone.core.ChatMessageListenerStub
import org.linphone.core.ChatRoom
import org.linphone.core.ChatRoomListenerStub
import org.linphone.core.Conference
import org.linphone.core.Content
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.tools.Log

/**
 * Shared snapshot, media preparation, and dispatch pipeline for single- and multi-contact message forwarding.
 */
internal object ChatForwardDispatcher {
    private const val TAG = "[Chat Forward Dispatcher]"

    sealed class AttachmentSource {
        data class StagedLocal(
            val stagedFile: File,
            val fileName: String,
            val mimeType: FileUtils.MimeType,
            val fileSize: Long
        ) : AttachmentSource()

        data class QueuedRemote(
            val media: OfflineInboxRepository.QueuedMedia,
            val mimeType: FileUtils.MimeType
        ) : AttachmentSource()

        data class NativeHftRemote(
            val message: ChatMessage,
            val content: Content,
            val fileName: String,
            val mimeType: FileUtils.MimeType,
            val fileSize: Long
        ) : AttachmentSource()
    }

    data class ForwardPayload(
        val rawText: String,
        val attachments: List<AttachmentSource>
    ) {
        fun isEmpty(): Boolean = rawText.isBlank() && attachments.isEmpty()

        fun cleanupStaging() {
            attachments.filterIsInstance<AttachmentSource.StagedLocal>().forEach { local ->
                runCatching { local.stagedFile.delete() }
            }
        }
    }

    data class ForwardDestination(
        val address: Address,
        val conversationId: String? = null
    )

    fun capturePayload(
        toForward: MessageModel,
        onCaptured: (ForwardPayload) -> Unit
    ) {
        coreContext.postOnCoreThread {
            val payload = capturePayloadOnCoreThread(toForward)
            onCaptured(payload)
        }
    }

    @WorkerThread
    fun capturePayloadOnCoreThread(toForward: MessageModel): ForwardPayload {
        val rawText = toForward.getRawTextContent().trim().let { text ->
            if (LinphoneUtils.isFileTransferMetadata(text)) "" else text
        }
        val fileModels = toForward.filesList.value.orEmpty()
        val fileContents = toForward.chatMessage.contents.filter {
            it.isFile || it.isFileTransfer || it.isVoiceRecording
        }
        val queuedMediaList = OfflineInboxRepository.queuedMedia(toForward.chatMessage)

        val attachments = arrayListOf<AttachmentSource>()
        if (fileContents.isNotEmpty()) {
            fileContents.forEachIndexed { index, content ->
                val model = fileModels.getOrNull(index)
                val mimeType = model?.mimeType ?: resolveMimeType(
                    content.type,
                    content.subtype,
                    model?.fileName ?: content.name
                )
                val baseName = model?.fileName?.takeIf { it.isNotBlank() }
                    ?: content.name.orEmpty().ifBlank { "attachment_${index + 1}" }

                val queuedItem = queuedMediaList.getOrNull(index)
                val existingLocal = findExistingFile(model?.path)
                    ?: findExistingFile(content.filePath)
                    ?: exportPlainFileSafe(content)
                    ?: findExistingFile(queuedItem?.localPath)

                when {
                    existingLocal != null -> {
                        val fileName = ensureExtension(baseName, mimeType, existingLocal.name)
                        stageFile(existingLocal, fileName, mimeType)?.let { attachments.add(it) }
                    }

                    queuedItem != null -> {
                        attachments.add(AttachmentSource.QueuedRemote(queuedItem, resolveQueuedMime(queuedItem)))
                    }

                    content.isFileTransfer -> {
                        attachments.add(
                            AttachmentSource.NativeHftRemote(
                                message = toForward.chatMessage,
                                content = content,
                                fileName = ensureExtension(baseName, mimeType, null),
                                mimeType = mimeType,
                                fileSize = content.fileSize.toLong().coerceAtLeast(0L)
                            )
                        )
                    }
                }
            }
        } else if (queuedMediaList.isNotEmpty()) {
            queuedMediaList.forEachIndexed { index, media ->
                val mimeType = resolveQueuedMime(media)
                val localPath = fileModels.getOrNull(index)?.path ?: media.localPath
                val existingLocal = findExistingFile(localPath)
                if (existingLocal != null) {
                    val fileName = ensureExtension(media.name, mimeType, existingLocal.name)
                    stageFile(existingLocal, fileName, mimeType)?.let { attachments.add(it) }
                } else if (media.url.isNotBlank()) {
                    attachments.add(AttachmentSource.QueuedRemote(media, mimeType))
                }
            }
        }

        if (attachments.isEmpty() && fileModels.isNotEmpty()) {
            fileModels.forEach { model ->
                val existingLocal = findExistingFile(model.path) ?: return@forEach
                val fileName = ensureExtension(model.fileName, model.mimeType, existingLocal.name)
                stageFile(existingLocal, fileName, model.mimeType)?.let { attachments.add(it) }
            }
        }

        Log.i(
            "$TAG Captured forward payload: textLen=${rawText.length}, attachments=${attachments.size}"
        )
        return ForwardPayload(rawText, attachments)
    }

    /**
     * Resolves any remote queued/HFT attachments into staged local files on a worker thread,
     * never blocking the Linphone Core or Main threads.
     */
    fun prepareLocalAttachments(
        payload: ForwardPayload,
        onReady: (ForwardPayload) -> Unit
    ) {
        val hasRemote = payload.attachments.any {
            it !is AttachmentSource.StagedLocal
        }
        if (!hasRemote) {
            onReady(payload)
            return
        }

        Thread({
            val resolved = payload.attachments.mapNotNull { source ->
                when (source) {
                    is AttachmentSource.StagedLocal -> source
                    is AttachmentSource.QueuedRemote -> resolveQueuedAttachmentBlocking(source)
                    is AttachmentSource.NativeHftRemote -> resolveNativeHftBlocking(source)
                }
            }
            onReady(ForwardPayload(payload.rawText, resolved))
        }, "diya-forward-prepare").start()
    }

    @WorkerThread
    fun dispatchPayloadToChatRoom(
        chatRoom: ChatRoom,
        payload: ForwardPayload,
        onMessageSent: ((ChatMessage) -> Unit)? = null
    ): Boolean {
        val localFiles = payload.attachments.filterIsInstance<AttachmentSource.StagedLocal>()
            .filter { it.stagedFile.isFile && it.stagedFile.length() > 0L }
        if (payload.rawText.isBlank() && localFiles.isEmpty()) {
            Log.w("$TAG Refusing to send empty forwarded payload to [${LinphoneUtils.getConversationId(chatRoom)}]")
            return false
        }

        val isBasicChatRoom = chatRoom.hasCapability(ChatRoom.Capabilities.Basic.toInt())
        if (isBasicChatRoom && localFiles.isNotEmpty()) {
            localFiles.forEach { staged ->
                val copy = createPerSendCopy(staged) ?: return@forEach
                val forwardedMessage = chatRoom.createEmptyMessage().apply {
                    addCustomHeader(DIYA_FORWARDED_HEADER, "1")
                    addFileContent(createForwardFileContent(staged, copy))
                }
                watchForwardedBasicFileTransfer(forwardedMessage, staged.fileName, copy.length())
                sendForwardedMessage(forwardedMessage, onMessageSent)
            }
            if (payload.rawText.isNotBlank()) {
                val textMessage = createForwardTextMessage(chatRoom, payload.rawText)
                sendForwardedMessage(textMessage, onMessageSent)
            }
        } else {
            val forwardedMessage = createForwardTextMessage(chatRoom, payload.rawText)
            var attachedFileCount = 0
            var totalBytes = 0L
            localFiles.forEach { staged ->
                val copy = createPerSendCopy(staged) ?: return@forEach
                forwardedMessage.addFileContent(createForwardFileContent(staged, copy))
                attachedFileCount += 1
                totalBytes += copy.length()
            }
            if (payload.rawText.isBlank() && attachedFileCount == 0) {
                Log.w("$TAG Failed to stage attachment copies for forward")
                return false
            }
            if (attachedFileCount > 0) {
                watchForwardedBasicFileTransfer(
                    forwardedMessage,
                    localFiles.first().fileName,
                    totalBytes
                )
            }
            sendForwardedMessage(forwardedMessage, onMessageSent)
        }
        return true
    }

    /**
     * Resolves or creates all target rooms on the Core thread and forwards [payload] to each one.
     * Invokes [onFinished] on the Core thread once every room creation and send has completed.
     */
    fun forwardToDestinations(
        destinations: List<ForwardDestination>,
        payload: ForwardPayload,
        onErrorToast: (Int) -> Unit,
        onFinished: (List<ChatRoom>) -> Unit
    ) {
        if (destinations.isEmpty()) {
            onFinished(emptyList())
            return
        }
        prepareLocalAttachments(payload) { prepared ->
            coreContext.postOnCoreThread { core ->
                if (prepared.isEmpty()) {
                    Log.w("$TAG Forward aborted after media resolution: payload empty")
                    onErrorToast(R.string.conversation_message_forward_cancelled_toast)
                    prepared.cleanupStaging()
                    onFinished(emptyList())
                    return@postOnCoreThread
                }

                val account = core.defaultAccount
                if (account == null) {
                    Log.e("$TAG No default account for forward")
                    onErrorToast(R.string.conversation_failed_to_create_toast)
                    prepared.cleanupStaging()
                    onFinished(emptyList())
                    return@postOnCoreThread
                }

                val remaining = AtomicInteger(destinations.size)
                val resolvedRooms = ArrayList<ChatRoom>(destinations.size)

                destinations.forEach { destination ->
                    resolveOrCreateDestinationRoom(core, account, destination, onErrorToast) { room ->
                        if (room != null) {
                            val sent = dispatchPayloadToChatRoom(room, prepared)
                            if (sent) {
                                synchronized(resolvedRooms) { resolvedRooms.add(room) }
                            }
                        }
                        if (remaining.decrementAndGet() == 0) {
                            prepared.cleanupStaging()
                            onFinished(resolvedRooms.toList())
                        }
                    }
                }
            }
        }
    }

    @WorkerThread
    private fun resolveOrCreateDestinationRoom(
        core: Core,
        account: Account,
        destination: ForwardDestination,
        onErrorToast: (Int) -> Unit,
        onResolved: (ChatRoom?) -> Unit
    ) {
        val convId = destination.conversationId
        if (!convId.isNullOrBlank()) {
            val existingById = LinphoneUtils.findChatRoomByConversationId(core, convId)
            if (existingById != null) {
                onResolved(existingById)
                return
            }
        }

        val remote = destination.address
        val params = core.createConferenceParams(null).apply {
            isChatEnabled = true
            isGroupEnabled = false
            subject = AppUtils.getString(R.string.conversation_one_to_one_hidden_subject)
            this.account = account
        }
        val chatParams = params.chatParams ?: run {
            onResolved(null)
            return
        }
        chatParams.ephemeralLifetime = 0

        val sameDomain = remote.domain == corePreferences.defaultDomain && remote.domain == account.params.domain
        if (account.params.instantMessagingEncryptionMandatory && sameDomain) {
            chatParams.backend = ChatRoom.Backend.FlexisipChat
            params.securityLevel = Conference.SecurityLevel.EndToEnd
        } else if (!account.params.instantMessagingEncryptionMandatory) {
            if (LinphoneUtils.isEndToEndEncryptedChatAvailable(core)) {
                chatParams.backend = ChatRoom.Backend.FlexisipChat
                params.securityLevel = Conference.SecurityLevel.EndToEnd
            } else {
                chatParams.backend = ChatRoom.Backend.Basic
                params.securityLevel = Conference.SecurityLevel.None
            }
        } else {
            onErrorToast(R.string.conversation_invalid_participant_due_to_security_mode_toast)
            onResolved(null)
            return
        }

        val localAddress = account.params.identityAddress
        val canonicalRemote = LinphoneUtils.canonicalizeBasicChatAddress(remote, account)
        val existing = LinphoneUtils.findExistingOneToOneChatRoom(core, params, localAddress, canonicalRemote)
        if (existing != null) {
            onResolved(existing)
            return
        }

        val created = core.createChatRoom(params, arrayOf(canonicalRemote))
        if (created == null) {
            Log.e("$TAG Failed to create chat room for [${canonicalRemote.asStringUriOnly()}]")
            onErrorToast(R.string.conversation_failed_to_create_toast)
            onResolved(null)
            return
        }

        if (chatParams.backend != ChatRoom.Backend.FlexisipChat || created.state == ChatRoom.State.Created) {
            onResolved(created)
            return
        }

        val listener = object : ChatRoomListenerStub() {
            override fun onStateChanged(chatRoom: ChatRoom, newState: ChatRoom.State?) {
                when (chatRoom.state) {
                    ChatRoom.State.Created -> {
                        chatRoom.removeListener(this)
                        onResolved(chatRoom)
                    }

                    ChatRoom.State.CreationFailed,
                    ChatRoom.State.Terminated -> {
                        chatRoom.removeListener(this)
                        onErrorToast(R.string.conversation_failed_to_create_toast)
                        onResolved(null)
                    }

                    else -> Unit
                }
            }
        }
        created.addListener(listener)
    }

    @WorkerThread
    private fun createForwardTextMessage(chatRoom: ChatRoom, originalText: String): ChatMessage =
        chatRoom.createEmptyMessage().apply {
            addCustomHeader(DIYA_FORWARDED_HEADER, "1")
            if (originalText.isNotBlank()) {
                addUtf8TextContent(encodeForwardEnvelope(originalText))
            }
        }

    @WorkerThread
    private fun createForwardFileContent(
        source: AttachmentSource.StagedLocal,
        sendFile: File
    ): Content {
        val subtypeValue = resolveSubtype(source.mimeType, source.fileName)
        return Factory.instance().createContent().apply {
            type = when (source.mimeType) {
                FileUtils.MimeType.Image -> "image"
                FileUtils.MimeType.Audio -> "audio"
                FileUtils.MimeType.Video -> "video"
                FileUtils.MimeType.Pdf -> "application"
                FileUtils.MimeType.PlainText -> "text"
                else -> "file"
            }
            subtype = subtypeValue
            name = source.fileName
            filePath = sendFile.absolutePath
        }
    }

    @WorkerThread
    private fun watchForwardedBasicFileTransfer(
        message: ChatMessage,
        fileName: String,
        fileSize: Long
    ) {
        val transferLogId = FileTransferApiLogger.beginUpload(
            coreContext.core.fileTransferServer.orEmpty(),
            fileName,
            fileSize
        )
        message.addListener(object : ChatMessageListenerStub() {
            override fun onMsgStateChanged(changedMessage: ChatMessage, state: ChatMessage.State?) {
                when (state) {
                    ChatMessage.State.FileTransferDone -> {
                        FileTransferApiLogger.completeUpload(transferLogId)
                        OfflineInboxRepository.enqueueUploadedMedia(
                            changedMessage,
                            FileTransferApiLogger.uploadedMedia(fileName)
                        )
                    }

                    ChatMessage.State.FileTransferError,
                    ChatMessage.State.NotDelivered -> FileTransferApiLogger.failUpload(
                        transferLogId,
                        "Forwarded file transfer ended with state $state"
                    )

                    else -> Unit
                }
            }
        })
    }

    @WorkerThread
    private fun sendForwardedMessage(
        message: ChatMessage,
        onMessageSent: ((ChatMessage) -> Unit)?
    ) {
        CustomImdn.prepareOutgoing(message)
        OutgoingMessageRetryManager.watch(message)
        Log.i("$TAG Sending forwarded message customId=${CustomImdn.id(message)}")
        message.send()
        onMessageSent?.invoke(message)
    }

    private fun resolveQueuedAttachmentBlocking(
        source: AttachmentSource.QueuedRemote
    ): AttachmentSource.StagedLocal? {
        val downloadedPath = OfflineInboxRepository.downloadQueuedMediaSync(source.media)
            ?: return null
        val downloadedFile = findExistingFile(downloadedPath) ?: return null
        val fileName = ensureExtension(source.media.name, source.mimeType, downloadedFile.name)
        return stageFile(downloadedFile, fileName, source.mimeType)
    }

    private fun resolveNativeHftBlocking(
        source: AttachmentSource.NativeHftRemote
    ): AttachmentSource.StagedLocal? {
        val downloadedOrExported = arrayOfNulls<File>(1)
        val latch = CountDownLatch(1)
        var listenerRef: ChatMessageListenerStub? = null

        coreContext.postOnCoreThread {
            val content = source.content
            val existing = findExistingFile(content.filePath) ?: exportPlainFileSafe(content)
            if (existing != null) {
                downloadedOrExported[0] = existing
                latch.countDown()
                return@postOnCoreThread
            }
            if (!content.isFileTransfer) {
                latch.countDown()
                return@postOnCoreThread
            }

            val downloadTarget = FileUtils.getFileStoragePath(
                source.fileName,
                isImage = source.mimeType == FileUtils.MimeType.Image
            )
            if (downloadTarget.exists()) downloadTarget.delete()
            downloadTarget.parentFile?.mkdirs()
            content.filePath = downloadTarget.absolutePath

            val listener = object : ChatMessageListenerStub() {
                override fun onFileTransferTerminated(msg: ChatMessage, terminatedContent: Content) {
                    if (terminatedContent.name == content.name || terminatedContent.filePath == content.filePath) {
                        downloadedOrExported[0] =
                            findExistingFile(terminatedContent.filePath) ?: exportPlainFileSafe(terminatedContent)
                        msg.removeListener(this)
                        latch.countDown()
                    }
                }

                override fun onMsgStateChanged(msg: ChatMessage, state: ChatMessage.State?) {
                    if (state == ChatMessage.State.FileTransferError || state == ChatMessage.State.NotDelivered) {
                        msg.removeListener(this)
                        latch.countDown()
                    }
                }
            }
            listenerRef = listener
            source.message.addListener(listener)
            try {
                if (!source.message.isFileTransferInProgress) {
                    source.message.downloadContent(content)
                }
            } catch (e: Exception) {
                Log.w("$TAG Failed triggering HFT download before forward: ${e.message}")
                source.message.removeListener(listener)
                latch.countDown()
            }
        }

        latch.await(45, TimeUnit.SECONDS)
        listenerRef?.let { listener ->
            coreContext.postOnCoreThread {
                runCatching { source.message.removeListener(listener) }
            }
        }

        val readyFile = downloadedOrExported[0] ?: return null
        val fileName = ensureExtension(source.fileName, source.mimeType, readyFile.name)
        return stageFile(readyFile, fileName, source.mimeType)
    }

    private fun stageFile(
        sourceFile: File,
        fileName: String,
        mimeType: FileUtils.MimeType
    ): AttachmentSource.StagedLocal? = runCatching {
        val stagingDir = File(coreContext.context.cacheDir, "forward_staging").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" }
        val stagedFile = File(stagingDir, "${UUID.randomUUID()}_$safeName")
        sourceFile.inputStream().use { input ->
            stagedFile.outputStream().use { output -> input.copyTo(output) }
        }
        if (!stagedFile.isFile || stagedFile.length() == 0L) {
            stagedFile.delete()
            return null
        }
        AttachmentSource.StagedLocal(
            stagedFile = stagedFile,
            fileName = fileName,
            mimeType = mimeType,
            fileSize = stagedFile.length()
        )
    }.onFailure {
        Log.w("$TAG Failed staging file [${sourceFile.absolutePath}]: ${it.message}")
    }.getOrNull()

    private fun createPerSendCopy(staged: AttachmentSource.StagedLocal): File? = runCatching {
        val destination = FileUtils.getFileStoragePath(
            staged.fileName,
            isImage = staged.mimeType == FileUtils.MimeType.Image
        )
        destination.parentFile?.mkdirs()
        staged.stagedFile.inputStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        destination.takeIf { it.isFile && it.length() > 0L }
    }.onFailure {
        Log.w("$TAG Failed copying staged forward file [${staged.fileName}]: ${it.message}")
    }.getOrNull()

    private fun findExistingFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    private fun exportPlainFileSafe(content: Content): File? {
        if (!content.isFileEncrypted) return null
        return runCatching {
            val plainPath = content.exportPlainFile()
            findExistingFile(plainPath)
        }.getOrNull()
    }

    internal fun resolveSubtype(mimeType: FileUtils.MimeType, fileName: String): String {
        if (mimeType == FileUtils.MimeType.PlainText) return "plain"
        val ext = FileUtils.getExtensionFromFileName(fileName)
            .lowercase(Locale.US)
            .takeWhile { it.isLetterOrDigit() }
        if (ext.isNotEmpty()) return ext
        return when (mimeType) {
            FileUtils.MimeType.Image -> "jpeg"
            FileUtils.MimeType.Audio -> "m4a"
            FileUtils.MimeType.Video -> "mp4"
            FileUtils.MimeType.Pdf -> "pdf"
            else -> "octet-stream"
        }
    }

    private fun ensureExtension(
        rawName: String,
        mimeType: FileUtils.MimeType,
        fallbackSourceName: String?
    ): String {
        val trimmed = rawName.ifBlank { fallbackSourceName.orEmpty().ifBlank { "attachment" } }
        if (FileUtils.getExtensionFromFileName(trimmed).isNotEmpty()) return trimmed
        val sourceExt = fallbackSourceName?.let { FileUtils.getExtensionFromFileName(it) }.orEmpty()
        val ext = sourceExt.ifEmpty { resolveSubtype(mimeType, trimmed) }
        return "$trimmed.$ext"
    }

    private fun resolveMimeType(
        type: String?,
        subtype: String?,
        name: String?
    ): FileUtils.MimeType {
        val t = type.orEmpty().lowercase(Locale.US)
        val s = subtype.orEmpty().lowercase(Locale.US)
        val mime = when {
            t.isNotEmpty() && s.isNotEmpty() -> "$t/$s"
            t.isNotEmpty() -> "$t/*"
            !name.isNullOrBlank() -> FileUtils.getMimeTypeFromExtension(FileUtils.getExtensionFromFileName(name))
            else -> ""
        }
        val detected = FileUtils.getMimeType(mime)
        if (detected != FileUtils.MimeType.Unknown) return detected
        val ext = FileUtils.getExtensionFromFileName(name.orEmpty())
        if (ext.isNotEmpty()) {
            return FileUtils.getMimeType(FileUtils.getMimeTypeFromExtension(ext))
        }
        return FileUtils.MimeType.Unknown
    }

    private fun resolveQueuedMime(media: OfflineInboxRepository.QueuedMedia): FileUtils.MimeType {
        val detected = FileUtils.getMimeType(media.mimeType)
        if (detected != FileUtils.MimeType.Unknown) return detected
        if (FileUtils.isExtensionImage(media.name)) return FileUtils.MimeType.Image
        val ext = FileUtils.getExtensionFromFileName(media.name)
        return FileUtils.getMimeType(FileUtils.getMimeTypeFromExtension(ext))
    }
}
