package com.naminfo.ui.main.chat

import java.nio.charset.StandardCharsets
import java.util.Base64
import com.naminfo.core.CustomImdn
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom

internal const val DIYA_REPLY_MESSAGE_ID_HEADER = "X-Diya-Reply-ID"
internal const val DIYA_REPLY_PREVIEW_HEADER = "X-Diya-Reply-Preview"
internal const val DIYA_FORWARDED_HEADER = "X-Diya-Forwarded"
private const val DIYA_REPLY_BODY_PREFIX = "\u2063DIYAREPLY1:"
private const val DIYA_REPLY_BODY_SUFFIX = "\u2063"
private const val DIYA_FORWARD_BODY_PREFIX = "\u2063DIYAFORWARD1:"
private const val DIYA_FORWARD_BODY_SUFFIX = "\u2063"
private const val DIYA_REPLY_HEADER_PREFIX = "v1."
private const val DIYA_REPLY_HEADER_PREVIEW_MAX_CHARS = 120
private const val DIYA_REPLY_HEADER_CAPTION_MAX_CHARS = 160

internal data class ReplyPreviewEnvelope(
    val messageId: String,
    val sender: String,
    val text: String,
    val isImage: Boolean = false
)

/**
 * SIP proxies may remove private headers. Carry a compact reply preview in the text body as
 * a fallback so another DiyaOne client can still render a reply when its local history cannot
 * resolve the referenced message ID.
 */
internal fun encodeReplyPreviewEnvelope(
    messageId: String,
    sender: String,
    previewText: String,
    messageText: String
): String {
    val fields = listOf(messageId, sender, previewText).joinToString(".") {
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(it.toByteArray(StandardCharsets.UTF_8))
    }
    val separator = if (messageText.isEmpty()) "" else "\n"
    return "$DIYA_REPLY_BODY_PREFIX$fields$DIYA_REPLY_BODY_SUFFIX$separator$messageText"
}

internal fun decodeReplyPreviewEnvelope(messageText: String): Pair<ReplyPreviewEnvelope, String>? {
    if (!messageText.startsWith(DIYA_REPLY_BODY_PREFIX)) return null
    val suffixIndex = messageText.indexOf(DIYA_REPLY_BODY_SUFFIX, DIYA_REPLY_BODY_PREFIX.length)
    if (suffixIndex < 0) return null
    val fields = messageText.substring(DIYA_REPLY_BODY_PREFIX.length, suffixIndex).split('.')
    if (fields.size != 3) return null
    return runCatching {
        val decoded = fields.map {
            String(Base64.getUrlDecoder().decode(it), StandardCharsets.UTF_8)
        }
        ReplyPreviewEnvelope(decoded[0], decoded[1], decoded[2]) to
        messageText.substring(suffixIndex + DIYA_REPLY_BODY_SUFFIX.length).removePrefix("\n")
    }.getOrNull()
}

/**
 * Basic SIP relays can drop custom headers. Keep a zero-width forward marker in the text
 * content as a fallback so both sender and receiver render the forwarded label consistently.
 */
internal fun encodeForwardEnvelope(messageText: String): String {
    val separator = if (messageText.isEmpty()) "" else "\n"
    return "$DIYA_FORWARD_BODY_PREFIX$DIYA_FORWARD_BODY_SUFFIX$separator$messageText"
}

/** Returns the visible body when the text was created by DiyaOne's forward flow. */
internal fun decodeForwardEnvelope(messageText: String): String? {
    if (!messageText.startsWith(DIYA_FORWARD_BODY_PREFIX)) return null
    val suffixIndex = messageText.indexOf(DIYA_FORWARD_BODY_SUFFIX, DIYA_FORWARD_BODY_PREFIX.length)
    if (suffixIndex < 0) return null
    return messageText.substring(suffixIndex + DIYA_FORWARD_BODY_SUFFIX.length).removePrefix("\n")
}

internal fun isForwardedMessage(message: ChatMessage): Boolean =
    message.isForward ||
        com.naminfo.core.CustomImdn.isForwarded(message) ||
        message.getCustomHeader(DIYA_FORWARDED_HEADER) == "1" ||
        message.contents.any { content ->
            content.isText && !content.isFile &&
                decodeForwardEnvelope(content.utf8Text.orEmpty().trim()) != null
        }

internal fun resolveReplyMessageId(nativeReplyId: String?, customReplyId: String?): String? =
    nativeReplyId?.takeIf { it.isNotBlank() }
        ?: customReplyId?.takeIf { it.isNotBlank() }

internal fun hasReplyTarget(nativeIsReply: Boolean, replyMessageId: String?): Boolean =
    nativeIsReply || !replyMessageId.isNullOrBlank()

internal fun createOutgoingTextMessage(
    chatRoom: ChatRoom,
    replyTo: ChatMessage?,
    edit: ChatMessage?,
    text: String
): ChatMessage {
    val message = when {
        replyTo != null -> chatRoom.createReplyMessage(replyTo)
        edit != null -> chatRoom.createReplacesMessage(edit)
        else -> chatRoom.createEmptyMessage()
    }
    if (text.isNotEmpty() || replyTo != null) {
        if (replyTo != null) {
            addReplyMetadataAndFallback(message, replyTo, text)
        } else {
            message.addUtf8TextContent(text)
        }
    }
    return message
}

/**
 * Basic-SIP attachments are sent as their own ChatMessage. Attach the reply identity and
 * body fallback to that file message too, otherwise a photo reply reaches the other phone as
 * a plain, unrelated media bubble.
 */
internal fun isMessageImageAttachment(message: ChatMessage): Boolean {
    val hasContentImage = message.contents.any { content ->
        (content.isFile || content.isFileTransfer) &&
            (
                content.type.equals("image", ignoreCase = true) ||
                    com.naminfo.utils.FileUtils.isExtensionImage(content.name.orEmpty())
                )
    }
    if (hasContentImage) return true
    return com.naminfo.core.OfflineInboxRepository.queuedMedia(message).any { media ->
        media.mimeType.startsWith("image/", ignoreCase = true) ||
            com.naminfo.utils.FileUtils.isExtensionImage(media.name)
    }
}

internal fun encodeReplyPreviewHeader(
    sender: String,
    previewText: String,
    isImage: Boolean,
    caption: String = ""
): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val safeSender = sender.trim().take(60)
    val safePreview = previewText.trim().let {
        if (it.length > DIYA_REPLY_HEADER_PREVIEW_MAX_CHARS) {
            it.take(DIYA_REPLY_HEADER_PREVIEW_MAX_CHARS) + "..."
        } else {
            it
        }
    }
    val safeCaption = caption.trim().takeIf { it.length <= DIYA_REPLY_HEADER_CAPTION_MAX_CHARS }.orEmpty()
    val parts = arrayListOf(
        encoder.encodeToString(safeSender.toByteArray(StandardCharsets.UTF_8)),
        encoder.encodeToString(safePreview.toByteArray(StandardCharsets.UTF_8)),
        if (isImage) "1" else "0"
    )
    if (safeCaption.isNotEmpty()) {
        parts.add(encoder.encodeToString(safeCaption.toByteArray(StandardCharsets.UTF_8)))
    }
    return DIYA_REPLY_HEADER_PREFIX + parts.joinToString(".")
}

internal fun decodeReplyPreviewHeader(
    messageId: String?,
    headerValue: String?
): Pair<ReplyPreviewEnvelope, String?>? {
    val id = messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val raw = headerValue?.trim()?.takeIf { it.startsWith(DIYA_REPLY_HEADER_PREFIX) } ?: return null
    val fields = raw.removePrefix(DIYA_REPLY_HEADER_PREFIX).split('.')
    if (fields.size < 3) return null
    return try {
        val decoder = Base64.getUrlDecoder()
        val sender = String(decoder.decode(fields[0]), StandardCharsets.UTF_8)
        val preview = String(decoder.decode(fields[1]), StandardCharsets.UTF_8)
        val isImage = fields[2] == "1"
        val caption = fields.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let {
            String(decoder.decode(it), StandardCharsets.UTF_8)
        }
        Pair(ReplyPreviewEnvelope(id, sender, preview, isImage), caption)
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun addReplyMetadataAndFallback(
    message: ChatMessage,
    replyTo: ChatMessage,
    messageText: String,
    includeBodyFallback: Boolean = true
) {
    // The native SIP message ID can differ between sender and receiver. The Diya IMDN UUID is
    // carried with the original message and is stable across both phones, while old history can
    // still fall back to the native ID.
    val replyId = CustomImdn.id(replyTo)?.takeIf { it.isNotBlank() }
        ?: replyTo.messageId.takeIf { it.isNotBlank() }
        ?: return
    message.addCustomHeader(DIYA_REPLY_MESSAGE_ID_HEADER, replyId)

    val isImageTarget = isMessageImageAttachment(replyTo)
    val rawPreviewText = replyTo.contents.firstOrNull { it.isText && !it.isFile }
        ?.utf8Text.orEmpty().trim()
        .let { if (com.naminfo.utils.LinphoneUtils.isFileTransferMetadata(it)) "" else it }
    // Do not nest prior reply or forward envelopes. For media without a text part, retain a
    // useful filename preview rather than sending an empty quote bubble.
    val previewText = com.naminfo.utils.LinphoneUtils.stripMessageEnvelopes(rawPreviewText)
        .ifBlank {
            replyTo.contents.firstOrNull { it.isFile || it.isFileTransfer }
                ?.name.orEmpty()
        }
        .ifBlank {
            com.naminfo.core.OfflineInboxRepository.queuedMedia(replyTo)
                .firstOrNull()?.name.orEmpty()
        }
        .ifBlank {
            if (isImageTarget) "Photo" else ""
        }
    val sender = replyTo.fromAddress.asStringUriOnly()
        .ifBlank {
            replyTo.fromAddress.displayName
                ?.takeIf { it.isNotBlank() }
                ?: replyTo.fromAddress.username.orEmpty()
        }
    message.addCustomHeader(
        DIYA_REPLY_PREVIEW_HEADER,
        encodeReplyPreviewHeader(sender, previewText, isImageTarget, messageText)
    )
    if (includeBodyFallback) {
        message.addUtf8TextContent(
            encodeReplyPreviewEnvelope(replyId, sender, previewText, messageText)
        )
    }
}
