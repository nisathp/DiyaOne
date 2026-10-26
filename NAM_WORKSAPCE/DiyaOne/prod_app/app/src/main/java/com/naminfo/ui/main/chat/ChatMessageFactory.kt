package com.naminfo.ui.main.chat

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom

internal const val DIYA_REPLY_MESSAGE_ID_HEADER = "X-Diya-Reply-ID"
private const val DIYA_REPLY_BODY_PREFIX = "\u2063DIYAREPLY1:"
private const val DIYA_REPLY_BODY_SUFFIX = "\u2063"

internal data class ReplyPreviewEnvelope(
    val messageId: String,
    val sender: String,
    val text: String
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
    if (replyTo != null && replyTo.messageId.isNotBlank()) {
        message.addCustomHeader(DIYA_REPLY_MESSAGE_ID_HEADER, replyTo.messageId)
    }
    if (text.isNotEmpty() || replyTo != null) {
        val outgoingText = if (replyTo != null && replyTo.messageId.isNotBlank()) {
            val previewText = replyTo.contents.firstOrNull { it.isText && !it.isFile }
                ?.utf8Text.orEmpty().trim()
            val sender = replyTo.fromAddress.asStringUriOnly()
                .ifBlank {
                    replyTo.fromAddress.displayName
                        ?.takeIf { it.isNotBlank() }
                        ?: replyTo.fromAddress.username.orEmpty()
                }
            encodeReplyPreviewEnvelope(replyTo.messageId, sender, previewText, text)
        } else {
            text
        }
        message.addUtf8TextContent(outgoingText)
    }
    return message
}
