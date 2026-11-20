package com.naminfo.core

import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.contacts.PhoneContactNumbers
import com.naminfo.utils.LinphoneUtils
import org.json.JSONObject
import org.linphone.core.ChatMessage
import org.linphone.core.ChatMessageListenerStub
import org.linphone.core.ChatRoom
import org.linphone.core.tools.Log
import java.util.UUID

/**
 * Initial implementation for one-to-one basic text chat.
 * Call all methods on the Linphone core thread.
 *
 * appdata is reserved for this helper. The inspected project
 * does not currently use it.
 */
object CustomImdn {
    private const val TAG = "[CustomImdn]"
    private const val HEADER = "X-Diya-Message-ID"
    // A Basic SIP ChatMessage created locally must be used to persist a Vercel queue
    // delivery.  The SDK consequently reports it as outgoing even after
    // receiveChatMessage().  This marker makes the UI treat that local persisted
    // representation as the incoming message it actually is.
    const val QUEUE_DELIVERY_HEADER = "X-Diya-Queue-Delivery"
    /** Original sender time retained when a durable offline item becomes a local SIP event. */
    const val ORIGINAL_TIMESTAMP_HEADER = "X-Diya-Original-Timestamp"
    private const val META_PREFIX = "diya-imdn-v1:"
    private const val RECEIPT_PREFIX = "DIYA_RECEIPT_V1\n"

    private val pending = mutableSetOf<String>()
    // Core history is normally the duplicate authority. Keep a short-lived
    // fallback for Basic-SIP devices where an event cannot be written to the
    // database (storage id -1), otherwise the direct SIP and queue transports
    // can render the same application message twice.
    private val claimedIncomingDeliveries = linkedMapOf<String, Long>()
    private const val INCOMING_DEDUPE_TTL_MS = 10 * 60 * 1000L
    // A displayed receipt can be accepted by FreeSWITCH while the sender is briefly
    // offline. Re-send recent receipts at most once a minute when the receiver opens
    // or scrolls the conversation, never flooding every 5s across old history.
    private const val DISPLAYED_RECEIPT_REFRESH_THROTTLE_MS = 60_000L
    private const val RECENT_DISPLAYED_REFRESH_WINDOW_MS = 24 * 60 * 60 * 1000L
    private const val MAX_FORCED_DISPLAYED_REFRESH = 12
    private const val ROOM_READ_HISTORY_SCAN_COOLDOWN_MS = 10_000L
    private const val BOUNDED_HISTORY_SCAN_LIMIT = 40
    private const val MAX_AWAITING_DISPLAYED = 500
    private val lastDisplayedReceiptRefresh = mutableMapOf<String, Long>()
    private val lastRoomReadScanAtMs = mutableMapOf<String, Long>()
    private val awaitingDisplayed = linkedMapOf<String, ChatMessage>()
    // A few Basic-SIP SDK builds can deliver a SIP MESSAGE while failing to
    // create its local history event (storage id -1). Keep the original native
    // object while the process is alive so its delivered/displayed receipt can
    // still update the visible optimistic bubble. Persisted history remains the
    // primary source after a normal restart.
    private val liveOutgoing = mutableMapOf<String, ChatMessage>()
    private val observers =
        mutableSetOf<(String, ChatMessage.State) -> Unit>()

    private const val RECEIPT_PACE_MS = 45L
    private const val MAX_RECEIPT_RETRIES = 2
    private const val RECEIPT_RETRY_BASE_DELAY_MS = 2_000L

    private var lastPreparedTimestampMs = 0L
    private var nextReceiptDispatchTimeMs = 0L

    private fun supported(room: ChatRoom): Boolean =
        room.hasCapability(ChatRoom.Capabilities.Basic.toInt()) &&
                !room.hasCapability(ChatRoom.Capabilities.Encrypted.toInt())

    private fun metadata(message: ChatMessage): JSONObject {
        val raw = message.appdata.orEmpty()

        if (raw.isEmpty() || !raw.startsWith(META_PREFIX)) return JSONObject()

        return runCatching {
            JSONObject(raw.removePrefix(META_PREFIX))
        }.getOrElse {
            Log.w("$TAG Ignoring invalid appdata metadata: ${it.message}")
            JSONObject()
        }
    }

    private fun save(message: ChatMessage, data: JSONObject) {
        val raw = message.appdata.orEmpty()
        if (raw.isNotEmpty() && !raw.startsWith(META_PREFIX)) {
            Log.w("$TAG Skipping appdata save because existing data belongs to another feature")
            return
        }
        message.appdata = META_PREFIX + data.toString()
    }

    private fun validId(value: String?): String? {
        val clean = value?.trim().orEmpty()
        return clean.takeIf { it.matches(Regex("[A-Za-z0-9._-]{8,200}")) }
    }

    /**
     * The SDK can expose a finalized file-transfer message as a new native object
     * after upload. Its appdata is occasionally absent on that object, while the
     * SIP header survives. Treat both as the same stable application message ID.
     */
    fun id(message: ChatMessage): String? =
        validId(metadata(message).optString("id"))
            ?: validId(message.getCustomHeader(HEADER))

    private fun key(room: ChatRoom, id: String): String {
        val roomKey = LinphoneUtils.getOneToOnePeerKey(room) ?: LinphoneUtils.getConversationId(room)
        return "$roomKey|$id"
    }

    fun key(message: ChatMessage): String? =
        id(message)?.let { key(message.chatRoom, it) }

    /**
     * Reserves one incoming app-message ID for either the native SIP or the
     * durable queue path. Returns false when the other path already owns it
     * unless replaceExisting is true (e.g. upgrading an incomplete SIP ghost).
     * Call only on the Core thread.
     */
    fun claimIncomingDelivery(
        room: ChatRoom,
        messageId: String,
        replaceExisting: Boolean = false
    ): Boolean {
        val now = System.currentTimeMillis()
        val iterator = claimedIncomingDeliveries.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > INCOMING_DEDUPE_TTL_MS) iterator.remove()
        }
        val deliveryKey = key(room, messageId)
        if (!replaceExisting && claimedIncomingDeliveries.containsKey(deliveryKey)) return false
        claimedIncomingDeliveries[deliveryKey] = now
        return true
    }

    fun isReceiverDeliveryConfirmed(message: ChatMessage): Boolean =
        metadata(message).optString("receivedStatus") in setOf("delivered", "displayed")

    fun isReceipt(message: ChatMessage?): Boolean =
        message?.utf8Text?.startsWith(RECEIPT_PREFIX) == true

    fun isHiddenControlMessage(message: ChatMessage?): Boolean =
        isReceipt(message) || CustomComposing.isControlMessage(message)

    private const val KEY_QUEUE_DELIVERED = "queueDelivered"
    // Locally injected queue events are already marked read by some Basic-SIP SDK builds.
    // Preserve their actual user-visible read state separately so the list and navigation
    // badges stay accurate until the recipient opens the conversation.
    private const val KEY_QUEUE_UNREAD = "queueUnread"
    private const val KEY_QUEUED_MEDIA = "queuedMedia"
    private const val KEY_IS_FORWARDED = "isForwarded"

    fun isQueueDeliveredIncoming(message: ChatMessage?): Boolean {
        if (message == null) return false
        return metadata(message).optBoolean(KEY_QUEUE_DELIVERED) ||
            message.getCustomHeader(QUEUE_DELIVERY_HEADER) == "1"
    }

    fun isForwarded(message: ChatMessage?): Boolean =
        message != null && metadata(message).optBoolean(KEY_IS_FORWARDED, false)

    fun recordQueueIncomingMetadata(
        message: ChatMessage,
        id: String,
        originalTimestampMs: Long,
        queuedMediaJson: String?,
        isForward: Boolean = false
    ) {
        val data = metadata(message)
        if (validId(id) != null) {
            data.put("id", id)
        }
        data.put(KEY_QUEUE_DELIVERED, true)
        data.put(KEY_QUEUE_UNREAD, true)
        if (isForward) {
            data.put(KEY_IS_FORWARDED, true)
        }
        if (originalTimestampMs > 0L) {
            data.put("sentAtMs", normalizeTimestampMillis(originalTimestampMs) ?: originalTimestampMs)
        }
        if (!queuedMediaJson.isNullOrBlank()) {
            data.put(KEY_QUEUED_MEDIA, queuedMediaJson)
        }
        save(message, data)
    }

    fun queuedMediaJson(message: ChatMessage?): String? {
        if (message == null) return null
        val raw: String? = metadata(message).optString(KEY_QUEUED_MEDIA)
        return raw?.takeIf { it.isNotBlank() }
    }

    internal fun normalizeTimestampMillis(raw: Long?): Long? {
        val value = raw?.takeIf { it > 0L } ?: return null
        return if (value >= 100_000_000_000L) value else value * 1000L
    }

    @Synchronized
    internal fun allocateOutgoingTimestampMs(nowMs: Long = System.currentTimeMillis()): Long {
        val next = if (nowMs > lastPreparedTimestampMs) nowMs else lastPreparedTimestampMs + 1L
        lastPreparedTimestampMs = next
        return next
    }

    /**
     * Returns the sender's message timestamp in milliseconds when known (falling back to
     * [ChatMessage.getTime] * 1000). Queue injection creates a native event at login time, so
     * preserving a monotonic sender timestamp keeps batch text and image order intact.
     */
    fun timestampMillis(message: ChatMessage): Long {
        val fromMeta = normalizeTimestampMillis(
            runCatching { metadata(message).optLong("sentAtMs") }.getOrNull()
        )
        if (fromMeta != null) return fromMeta

        val fromHeader = normalizeTimestampMillis(
            message.getCustomHeader(ORIGINAL_TIMESTAMP_HEADER)?.toLongOrNull()
        )
        if (fromHeader != null) return fromHeader

        return message.time * 1000L
    }

    /**
     * Returns the sender's message timestamp in seconds. Queue injection creates a native
     * event at login time, so its SDK timestamp is not the time the sender actually sent it.
     */
    fun timestamp(message: ChatMessage): Long = timestampMillis(message) / 1000L

    /**
     * FreeSWITCH can occasionally deliver a remote SIP MESSAGE with an outgoing native
     * wrapper. Notifications still see that event, but a strict isOutgoing check then
     * hides it from every unread badge. Trust a different sender identity as incoming;
     * own messages retain the same sender and local identities and remain excluded.
     */
    fun isSemanticallyIncoming(message: ChatMessage): Boolean {
        if (!message.isOutgoing || isQueueDeliveredIncoming(message)) return true

        val sender = normalizedPeer(message.fromAddress.username)
        val local = normalizedPeer(message.chatRoom.localAddress.username)
        return sender.isNotEmpty() && local.isNotEmpty() && sender != local
    }

    fun visibleUnreadMessagesCount(room: ChatRoom): Int =
        visibleUnreadMessagesCount(listOf(room))

    fun visibleUnreadMessagesCount(rooms: Iterable<ChatRoom>): Int {
        val candidates = rooms.asSequence().flatMap { r ->
            val unread = r.unreadHistory.asSequence()
            val events = r.getHistoryMessageEvents(0).asSequence().mapNotNull { it.chatMessage }
                .filter { !it.isRead || metadata(it).optBoolean(KEY_QUEUE_UNREAD, false) }
            unread + events
        }.asIterable()
        return visibleUnreadMessagesCount(candidates)
    }

    /**
     * Stable IDs already represented by native unread history.  The durable inbox uses the
     * same application IDs, so callers can merge its rows with native history without turning
     * one received message into two badges.  This is especially important on Vivo builds where
     * a locally injected Basic-SIP event is exposed as read by the SDK even while the durable
     * inbox row remains unread.
     */
    fun visibleUnreadMessageIds(rooms: Iterable<ChatRoom>): Set<String> {
        val ids = mutableSetOf<String>()
        for (room in rooms) {
            val candidates = room.unreadHistory.asSequence() +
                room.getHistoryMessageEvents(0).asSequence().mapNotNull { it.chatMessage }
                    .filter { !it.isRead || metadata(it).optBoolean(KEY_QUEUE_UNREAD, false) }
            for (message in candidates) {
                if (!isUnreadIncoming(message) || isHiddenControlMessage(message)) continue
                stableMessageId(message)?.let(ids::add)
            }
        }
        return ids
    }

    internal fun visibleUnreadMessagesCount(messages: Iterable<ChatMessage?>): Int {
        val seenIds = mutableSetOf<String>()
        return messages.count { message ->
            if (message == null || !isUnreadIncoming(message) ||
                isHiddenControlMessage(message)
            ) {
                false
            } else {
                val id = stableMessageId(message) ?: message.messageId
                id == null || seenIds.add(id)
            }
        }
    }

    private fun stableMessageId(message: ChatMessage): String? =
        validId(metadata(message).optString("id"))
            ?: validId(message.getCustomHeader(HEADER))

    private fun isUnreadIncoming(message: ChatMessage): Boolean =
        isSemanticallyIncoming(message) &&
            (!message.isRead || metadata(message).optBoolean(KEY_QUEUE_UNREAD, false))

    /**
     * Marks queue-injected messages as read when their conversation becomes visible.
     * This complements [ChatRoom.markAsRead], which cannot change the native read flag on
     * SDK versions that create local queue events as already read.
     */
    fun markQueueDeliveredMessagesRead(rooms: Iterable<ChatRoom>) {
        for (room in rooms) {
            for (message in room.getHistoryMessageEvents(0).asSequence().mapNotNull { it.chatMessage }) {
                if (!metadata(message).optBoolean(KEY_QUEUE_UNREAD, false)) {
                    continue
                }
                val data = metadata(message)
                data.put(KEY_QUEUE_UNREAD, false)
                save(message, data)
                sendReceipt(message, "displayed")
            }
        }
    }

    /**
     * FreeSWITCH can fan one SIP MESSAGE out as multiple requests with distinct
     * Call-IDs. Keep one incoming history entry for the stable app message ID.
     * Call on the core thread, before processing receipts for a new message.
     */
    fun removeDuplicateIncomingMessages(room: ChatRoom) {
        if (!supported(room)) return

        val byStableId = room.getHistoryMessageEvents(0)
            .asSequence()
            .mapNotNull { it.chatMessage }
            .filter { isSemanticallyIncoming(it) && !isReceipt(it) }
            .mapNotNull { message -> stableMessageId(message)?.let { it to message } }
            .groupBy({ it.first }, { it.second })

        for ((stableId, messages) in byStableId) {
            if (messages.size < 2) continue

            val canonical = messages.minWithOrNull(
                compareBy<ChatMessage> { it.time }.thenBy { it.messageId }
            ) ?: continue
            for (duplicate in messages) {
                if (duplicate.messageId != canonical.messageId) {
                    Log.w(
                        "[Custom IMDN] Removing duplicate incoming message " +
                            "id=$stableId sipId=${duplicate.messageId}"
                    )
                    room.deleteMessage(duplicate)
                }
            }
        }
    }

    /** Returns true when the message being received is an already stored duplicate. */
    private fun removeDuplicateIncomingMessage(
        room: ChatRoom,
        incoming: ChatMessage,
        stableId: String
    ): Boolean {
        val messages = room.getHistoryMessageEvents(0)
            .asSequence()
            .mapNotNull { it.chatMessage }
            .filter { isSemanticallyIncoming(it) && !isReceipt(it) && stableMessageId(it) == stableId }
            .toList()

        val incomingIsStored = messages.any { it.messageId == incoming.messageId }
        if (!incomingIsStored) {
            // The current callback can race history persistence. If another SIP
            // event with this stable ID is already stored, this callback is a duplicate.
            return messages.isNotEmpty()
        }

        if (messages.size < 2) return false

        val canonical = messages.minWithOrNull(
            compareBy<ChatMessage> { it.time }.thenBy { it.messageId }
        ) ?: return false

        for (duplicate in messages) {
            if (duplicate.messageId != canonical.messageId) {
                Log.w(
                    "[Custom IMDN] Removing duplicate incoming message " +
                        "id=$stableId sipId=${duplicate.messageId}"
                )
                room.deleteMessage(duplicate)
            }
        }
        return incoming.messageId != canonical.messageId
    }

    /**
     * Call before sending an original message.
     * Reuses its ID when the same message is retried.
     */
    fun prepareOutgoing(message: ChatMessage) {
        if (!supported(message.chatRoom) || isReceipt(message)) return

        val data = metadata(message)
        val messageId = validId(data.optString("id"))
            ?: UUID.randomUUID().toString()
        val sentAtMs = normalizeTimestampMillis(data.optLong("sentAtMs"))
            ?: normalizeTimestampMillis(message.getCustomHeader(ORIGINAL_TIMESTAMP_HEADER)?.toLongOrNull())
            ?: allocateOutgoingTimestampMs()

        data.put("id", messageId)
        data.put("sentAtMs", sentAtMs)
        if (com.naminfo.ui.main.chat.isForwardedMessage(message)) {
            data.put(KEY_IS_FORWARDED, true)
        }
        save(message, data)

        message.removeCustomHeader(HEADER)
        message.addCustomHeader(HEADER, messageId)
        message.removeCustomHeader(ORIGINAL_TIMESTAMP_HEADER)
        message.addCustomHeader(ORIGINAL_TIMESTAMP_HEADER, sentAtMs.toString())
        liveOutgoing[key(message.chatRoom, messageId)] = message

        Log.i("[Custom IMDN] Sending original id=$messageId sentAtMs=$sentAtMs")
    }

    private fun findMatchingOutgoings(
        room: ChatRoom?,
        messageId: String,
        expectedRecipient: String = ""
    ): List<ChatMessage> {
        fun isOriginal(message: ChatMessage): Boolean =
            !isSemanticallyIncoming(message) && !isReceipt(message) && id(message) == messageId

        val peer = expectedRecipient.ifEmpty {
            normalizedPeer(room?.peerAddress?.username)
        }
        val matches = mutableListOf<ChatMessage>()
        val seenInstances = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<ChatMessage, Boolean>()
        )

        fun collect(candidate: ChatMessage?) {
            if (candidate != null && isOriginal(candidate) && seenInstances.add(candidate)) {
                matches.add(candidate)
            }
        }

        if (room != null) {
            collect(liveOutgoing[key(room, messageId)])
            if (messageId.isNotBlank()) {
                collect(room.findMessage(messageId))
            }
        }

        // A receipt replayed after a push wake-up can carry B's temporary public contact
        // (IP, port and pn-* parameters) instead of the saved Basic-SIP room address. Use the
        // normalized SIP username as the stable peer identity, not Address.weakEqual().
        liveOutgoing.values.forEach { candidate ->
            val candidatePeer = normalizedPeer(candidate.peerAddress.username)
            if (peer.isEmpty() || candidatePeer == peer) {
                collect(candidate)
            }
        }

        val siblingRooms = coreContext.core.chatRooms.filter { candidateRoom ->
            val candidatePeer = normalizedPeer(candidateRoom.peerAddress.username)
            peer.isEmpty() || candidatePeer == peer
        }
        // Scan a bounded recent tail first instead of allocating the full EventLog[] history
        // array on every receipt; only fall back to full history when an old receipt misses.
        siblingRooms.forEach { candidateRoom ->
            candidateRoom.getHistoryMessageEvents(BOUNDED_HISTORY_SCAN_LIMIT).forEach {
                collect(it.chatMessage)
            }
        }
        if (matches.isEmpty()) {
            siblingRooms.forEach { candidateRoom ->
                candidateRoom.getHistoryMessageEvents(0).forEach { collect(it.chatMessage) }
            }
        }

        return matches
    }

    private fun findOutgoing(room: ChatRoom, messageId: String): ChatMessage? =
        findMatchingOutgoings(room, messageId).firstOrNull()

    fun addObserver(observer: (String, ChatMessage.State) -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: (String, ChatMessage.State) -> Unit) {
        observers.remove(observer)
    }

    fun effectiveState(message: ChatMessage): ChatMessage.State {
        val nativeState = message.state
        if (isSemanticallyIncoming(message) || nativeState == ChatMessage.State.Displayed) return nativeState

        val receipt = metadata(message).optString("receivedStatus")
        val saved = OfflineInboxRepository.outgoingHistoryStatus(message)
        return when {
            receipt == "displayed" || saved == "displayed" -> ChatMessage.State.Displayed
            receipt == "delivered" || saved == "delivered" -> ChatMessage.State.Delivered
            saved == "sent" && nativeState in setOf(
                ChatMessage.State.Idle,
                ChatMessage.State.InProgress,
                ChatMessage.State.FileTransferInProgress,
                ChatMessage.State.PendingDelivery,
                ChatMessage.State.Queued
            ) -> ChatMessage.State.Delivered
            else -> nativeState
        }
    }

    fun recordLocalSendCompletion(message: ChatMessage, state: ChatMessage.State) {
        val messageId = id(message) ?: return
        val room = message.chatRoom
        val status = when (state) {
            ChatMessage.State.Displayed -> "displayed"
            ChatMessage.State.DeliveredToUser -> "delivered"
            else -> "sent"
        }
        OfflineInboxRepository.updateLocalOutgoingStatus(
            messageId, room.localAddress.username, room.peerAddress.username, status, message.messageId
        )
    }

    /** Restores the receipt state on a display-only outgoing fallback message. */
    fun restoreOutgoingReceiptState(message: ChatMessage, status: String?) {
        if (status != "delivered" && status != "displayed") return
        val data = metadata(message)
        data.put("receivedStatus", status)
        save(message, data)
    }

    /**
     * Process receipts before processing ordinary incoming messages.
     * Receipt messages never generate another receipt.
     */
    fun receive(room: ChatRoom, message: ChatMessage) {
        if (!isSemanticallyIncoming(message) || !supported(room)) return

        if (isReceipt(message)) {
            try {
                consumeReceipt(room, message)
            } catch (e: Exception) {
                Log.w("[Custom IMDN] Invalid receipt: ${e.message}")
            } finally {
                // Delete only the control message, not the original.
                room.deleteMessage(message)
            }
            return
        }

        val messageId = stableMessageId(message)
        if (messageId == null) {
            Log.w("[Custom IMDN] Original message has no valid custom ID")
            return
        }

        val isDisplayedNow = coreContext.notificationsManager.isChatRoomCurrentlyDisplayed(room)

        if (isQueueDeliveredIncoming(message)) {
            Log.i("[Custom IMDN] Preserving injected queue incoming message id=$messageId isDisplayedNow=$isDisplayedNow")
            trackIncomingForDisplayed(message)
            if (isDisplayedNow) {
                val data = metadata(message)
                data.put(KEY_QUEUE_UNREAD, false)
                save(message, data)
                sendReceipt(message, "displayed")
            }
            return
        }

        // The history callback may contain multiple SIP transactions created
        // by one app send. Dedupe before storing metadata or acknowledging it.
        // The in-memory claim also protects devices whose local DB rejected the
        // first event and consequently cannot answer the history query.
        if (!claimIncomingDelivery(room, messageId) ||
            removeDuplicateIncomingMessage(room, message, messageId)
        ) {
            Log.i(
                "[Custom IMDN] Duplicate incoming message id=$messageId received. Resending delivery receipt with force=true to stop sender retries."
            )
            sendReceipt(message, "delivered", force = true)
            if (isDisplayedNow) {
                sendReceipt(message, "displayed", force = true)
            }
            room.deleteMessage(message)
            return
        }

        val data = metadata(message)
        data.put("id", messageId)
        if (!isDisplayedNow) {
            data.put(KEY_QUEUE_UNREAD, true)
        }
        save(message, data)
        trackIncomingForDisplayed(message)

        sendReceipt(message, "delivered")

        if (isDisplayedNow) {
            sendReceipt(message, "displayed")
        }
    }

    private fun consumeReceipt(room: ChatRoom, receipt: ChatMessage) {
        // A receipt sent after an FCM/push wake-up can use a temporary SIP
        // contact (different IP, port and pn-* parameters). Address.weakEqual()
        // then rejects a receipt that was actually sent by this room's peer.
        // Fall back only to the normalized numeric subscriber identity; do not
        // accept a receipt from an unrelated SIP address.
        val receiptPeer = normalizedPeer(receipt.fromAddress.username)
        val roomPeer = normalizedPeer(room.peerAddress.username)
        if (
            !receipt.fromAddress.weakEqual(room.peerAddress) &&
            (receiptPeer.isEmpty() || receiptPeer != roomPeer)
        ) {
            Log.w("[Custom IMDN] Ignoring receipt from a different peer")
            return
        }

        val text = receipt.utf8Text.orEmpty()
        require(text.length <= 2048) { "Receipt too large" }

        val body = JSONObject(text.removePrefix(RECEIPT_PREFIX))
        require(body.optInt("version") == 1)

        val messageId = validId(body.optString("messageId")) ?: return
        val status = body.optString("status")
        if (status != "delivered" && status != "displayed") return

        // Match all outgoing native wrappers in this account/peer chat (including sibling rooms).
        val targets = findMatchingOutgoings(room, messageId)

        if (targets.isEmpty()) {
            recordSnapshotReceipt(room.localAddress.username, room.peerAddress.username, messageId, status, room)
            return
        }

        val updatedState = applyOutgoingStatus(targets, messageId, status, extraRoom = room)
            ?: return

        // SIP's `Delivered` means only that FreeSWITCH accepted the message. This receipt is
        // the first safe signal that B has actually received it, so cancel any proactive queue.
        targets.forEach { OfflineInboxRepository.removeQueuedOutgoing(it) }

        if (status == "displayed") {
            liveOutgoing.remove(key(room, messageId))
            targets.forEach { liveOutgoing.remove(key(it.chatRoom, messageId)) }
        }

        Log.i("[Custom IMDN] Matched id=$messageId status=$status state=$updatedState targets=${targets.size}")
    }

    /**
     * Applies [status] ("delivered" or "displayed") across every matching native wrapper without
     * regressing displayed -> delivered, and notifies observers using each wrapper's real room key.
     */
    private fun applyOutgoingStatus(
        targets: List<ChatMessage>,
        messageId: String,
        status: String,
        extraRoom: ChatRoom? = null
    ): ChatMessage.State? {
        var latestState: ChatMessage.State? = null
        val keysToNotify = linkedMapOf<String, ChatMessage.State>()

        for (target in targets) {
            val room = target.chatRoom
            OfflineInboxRepository.updateLocalOutgoingStatus(
                messageId, room.localAddress.username, room.peerAddress.username, status, target.messageId
            )
            val data = metadata(target)
            val previous = data.optString("receivedStatus")
            // Ignore duplicates and prevent displayed -> delivered regression.
            if (previous == "displayed" || previous == status) continue

            data.put("receivedStatus", status)
            save(target, data)

            val newState = effectiveState(target)
            latestState = newState
            keysToNotify[key(target.chatRoom, messageId)] = newState
        }

        val resolvedState = latestState ?: return null
        if (extraRoom != null) {
            keysToNotify.putIfAbsent(key(extraRoom, messageId), resolvedState)
        }

        val snapshot = observers.toList()
        for ((messageKey, state) in keysToNotify) {
            snapshot.forEach { observer -> observer(messageKey, state) }
        }
        return resolvedState
    }

    private fun recordSnapshotReceipt(local: String?, peer: String?, messageId: String, status: String, room: ChatRoom?) {
        if (!OfflineInboxRepository.updateLocalOutgoingStatus(messageId, local, peer, status)) return
        if (room != null) {
            val state = if (status == "displayed") ChatMessage.State.Displayed else ChatMessage.State.Delivered
            val messageKey = key(room, messageId)
            observers.toList().forEach { it(messageKey, state) }
        }
    }

    internal fun normalizedPeer(raw: String?): String {
        val trimmed = raw.orEmpty().trim()
        val normalized = PhoneContactNumbers.normalize(trimmed)
        return normalized.ifEmpty { trimmed.lowercase() }
    }

    /**
     * Called after a conversation is marked read.
     * Scans sibling 1:1 rooms and relies on persisted IDs so read receipts survive restarts.
     */

    /**
     * Remembers an incoming message that has not yet sent a "displayed" receipt so [prepareRoomRead]
     * and [roomRead] can emit IMDN receipts without scanning native [ChatRoom.getHistoryMessageEvents].
     */
    fun trackIncomingForDisplayed(message: ChatMessage) {
        val room = message.chatRoom
        if (!supported(room) || !isSemanticallyIncoming(message) || isReceipt(message)) return
        val messageKey = key(message) ?: return
        if (metadata(message).optBoolean("sent_displayed")) {
            awaitingDisplayed.remove(messageKey)
            return
        }
        awaitingDisplayed[messageKey] = message
        while (awaitingDisplayed.size > MAX_AWAITING_DISPLAYED) {
            val oldestKey = awaitingDisplayed.keys.firstOrNull() ?: break
            awaitingDisplayed.remove(oldestKey)
        }
    }

    /**
     * Captures unread messages BEFORE [ChatRoom.markAsRead] clears [ChatRoom.getUnreadHistory] and
     * dispatches "displayed" receipts without churning [ChatRoom.getHistoryMessageEvents].
     */
    fun prepareRoomRead(rooms: Iterable<ChatRoom>) {
        val now = System.currentTimeMillis()
        for (room in rooms) {
            if (!supported(room)) continue
            lastRoomReadScanAtMs[LinphoneUtils.getConversationId(room)] = now
            val unreadMessages = room.unreadHistory.asSequence() +
                room.getHistoryMessageEvents(0).asSequence().mapNotNull { it.chatMessage }
                    .filter { !it.isRead || metadata(it).optBoolean(KEY_QUEUE_UNREAD, false) }
            for (message in unreadMessages) {
                if (isSemanticallyIncoming(message) && !isReceipt(message) && id(message) != null) {
                    trackIncomingForDisplayed(message)
                    sendReceipt(message, "displayed")
                }
            }
            flushAwaitingDisplayedForPeer(normalizedPeer(room.peerAddress.username))
        }
    }

    private fun flushAwaitingDisplayedForPeer(peer: String) {
        if (awaitingDisplayed.isEmpty()) return
        val candidates = awaitingDisplayed.values.toList()
        for (message in candidates) {
            val msgPeer = normalizedPeer(message.chatRoom.peerAddress.username)
            if (peer.isNotEmpty() && msgPeer != peer) continue
            if (metadata(message).optBoolean("sent_displayed")) {
                key(message)?.let(awaitingDisplayed::remove)
                continue
            }
            if (message.isRead && id(message) != null) {
                sendReceipt(message, "displayed")
            }
        }
    }

    fun roomRead(room: ChatRoom) {
        if (!supported(room)) return

        val peer = normalizedPeer(room.peerAddress.username)
        flushAwaitingDisplayedForPeer(peer)

        val now = System.currentTimeMillis()
        for (candidateRoom in LinphoneUtils.findSiblingOneToOneChatRooms(coreContext.core, room)) {
            if (!supported(candidateRoom)) continue
            val convKey = LinphoneUtils.getConversationId(candidateRoom)
            val lastScan = lastRoomReadScanAtMs[convKey] ?: 0L
            // Liblinphone's onChatRoomRead fires synchronously inside markAsRead(), and scrolling
            // triggers repeated markAsRead() calls within milliseconds. Avoid churning unbounded
            // native EventLog[] arrays in tight loops since FinalizerDaemon unrefs race NewLocalRef.
            if (now - lastScan < ROOM_READ_HISTORY_SCAN_COOLDOWN_MS) continue
            lastRoomReadScanAtMs[convKey] = now

            for (event in candidateRoom.getHistoryMessageEvents(BOUNDED_HISTORY_SCAN_LIMIT)) {
                val message = event.chatMessage ?: continue
                if (
                    isSemanticallyIncoming(message) &&
                    !isReceipt(message) &&
                    message.isRead &&
                    id(message) != null
                ) {
                    sendReceipt(message, "displayed")
                }
            }
        }
    }

    /**
     * Ensures read incoming messages transmit their displayed receipt. Unconfirmed receipts
     * are sent immediately; recently confirmed receipts are refreshed at most once per minute
     * for up to [MAX_FORCED_DISPLAYED_REFRESH] newest messages so SIP never floods old history.
     *
     * Call on the Linphone core thread.
     */
    fun refreshDisplayedReceipts(room: ChatRoom, visibleMessages: Iterable<ChatMessage>) {
        if (!supported(room)) return

        val now = System.currentTimeMillis()
        val refreshIterator = lastDisplayedReceiptRefresh.entries.iterator()
        while (refreshIterator.hasNext()) {
            if (now - refreshIterator.next().value > INCOMING_DEDUPE_TTL_MS) {
                refreshIterator.remove()
            }
        }

        var refreshed = 0
        var forcedCount = 0
        for (message in visibleMessages.toList().asReversed()) {
            if (
                !supported(message.chatRoom) ||
                !isSemanticallyIncoming(message) ||
                isReceipt(message) ||
                !message.isRead ||
                id(message) == null
            ) {
                continue
            }

            if (!metadata(message).optBoolean("sent_displayed")) {
                sendReceipt(message, "displayed", force = false)
                refreshed += 1
                continue
            }

            if (now - timestampMillis(message) > RECENT_DISPLAYED_REFRESH_WINDOW_MS) continue
            if (forcedCount >= MAX_FORCED_DISPLAYED_REFRESH) continue

            val refreshKey = "${key(message)}|displayed"
            val lastRefresh = lastDisplayedReceiptRefresh[refreshKey] ?: 0L
            if (now - lastRefresh < DISPLAYED_RECEIPT_REFRESH_THROTTLE_MS) continue

            lastDisplayedReceiptRefresh[refreshKey] = now
            sendReceipt(message, "displayed", force = true)
            forcedCount += 1
            refreshed += 1
        }

        if (refreshed > 0) {
            Log.i("[Custom IMDN] Refreshed displayed receipts for $refreshed visible message(s)")
        }
    }

    /**
     * Flushes any delivery/read receipts whose prior SIP send failed while offline.
     * Call on the Linphone core thread after SIP registration transitions to Ok.
     */
    fun onSipRegistrationRestored() {
        var flushed = 0
        for (room in coreContext.core.chatRooms) {
            if (!supported(room)) continue
            val seen = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<ChatMessage, Boolean>()
            )
            val messages = mutableListOf<ChatMessage>()
            room.getHistoryMessageEvents(0).forEach { event ->
                val msg = event.chatMessage ?: return@forEach
                if (seen.add(msg)) messages.add(msg)
            }
            room.unreadHistory.forEach { msg ->
                if (seen.add(msg)) messages.add(msg)
            }
            for (message in messages) {
                if (!isSemanticallyIncoming(message) || isReceipt(message) || id(message) == null) {
                    continue
                }
                val data = metadata(message)
                if (!data.optBoolean("sent_delivered")) {
                    sendReceipt(message, "delivered")
                    flushed += 1
                }
                if (message.isRead && !data.optBoolean("sent_displayed")) {
                    sendReceipt(message, "displayed")
                    flushed += 1
                }
            }
        }
        if (flushed > 0) {
            Log.i("[Custom IMDN] Queued [$flushed] unsent receipt(s) after SIP registration restored")
        }
    }

    fun messageRead(message: ChatMessage) {
        if (metadata(message).optBoolean(KEY_QUEUE_UNREAD, false)) {
            val data = metadata(message)
            data.put(KEY_QUEUE_UNREAD, false)
            save(message, data)
        }
        if (
            isSemanticallyIncoming(message) &&
            !isReceipt(message) &&
            message.isRead &&
            supported(message.chatRoom)
        ) {
            sendReceipt(message, "displayed")
        }
    }

    /**
     * Applies the durable queue's receiver acknowledgement to the original outgoing message.
     * The acknowledgement is delivered to A through authenticated FCM after B has persisted
     * the item and scheduled its HFT downloads, so it has the same meaning as B's SIP receipt.
     * Call on the Linphone core thread.
     */
    fun markDeliveredFromOfflineQueue(messageId: String?, recipient: String?) =
        applyOfflineQueueReceipt(messageId, recipient, "delivered")

    fun markDisplayedFromOfflineQueue(messageId: String?, recipient: String?) =
        applyOfflineQueueReceipt(messageId, recipient, "displayed")

    private fun applyOfflineQueueReceipt(messageId: String?, recipient: String?, status: String) {
        val stableId = validId(messageId) ?: return
        val expectedRecipient = normalizedPeer(recipient)
        if (expectedRecipient.isEmpty()) return
        val targets = findMatchingOutgoings(
            room = null,
            messageId = stableId,
            expectedRecipient = expectedRecipient
        )

        if (targets.isEmpty()) {
            val core = coreContext.core
            val local = core.defaultAccount?.params?.identityAddress?.username
            val room = (core.chatRooms.asSequence() + OfflineInboxRepository.restoredLocalFallbackRooms().asSequence())
                .firstOrNull {
                    normalizedPeer(it.localAddress.username) == normalizedPeer(local) &&
                        normalizedPeer(it.peerAddress.username) == expectedRecipient
                }
            recordSnapshotReceipt(local, recipient, stableId, status, room)
            return
        }

        if (applyOutgoingStatus(targets, stableId, status) != null) {
            Log.i("[Custom IMDN] Offline queue $status id=$stableId targets=${targets.size}")
        }
    }

    /**
     * Confirms a durable-queue delivery through SIP as well as the queue API.
     *
     * A queue item is materialized locally with [ChatRoom.receiveChatMessage], so it does not
     * pass through [receive] and would otherwise skip the normal receiver-delivered receipt.
     * The backend FCM acknowledgement remains useful when SIP is unavailable, but it can be
     * delayed or coalesced when a batch of media is restored at once. Sending the same
     * per-message receipt here makes every outgoing bubble converge to the delivered state.
     */
    fun acknowledgeQueueDelivery(message: ChatMessage) {
        if (!isQueueDeliveredIncoming(message) || !supported(message.chatRoom)) return
        if (id(message) == null) {
            Log.w("[Custom IMDN] Queued message has no valid ID; can't send delivery receipt")
            return
        }
        trackIncomingForDisplayed(message)
        sendReceipt(message, "delivered")
    }

    private fun sendReceipt(
        original: ChatMessage,
        status: String,
        force: Boolean = false,
        retryAttempt: Int = 0
    ) {
        val messageId = id(original) ?: return
        val flag = "sent_$status"

        if (!force && metadata(original).optBoolean(flag)) {
            if (status == "displayed") {
                key(original)?.let(awaitingDisplayed::remove)
            }
            return
        }

        if (status == "displayed") {
            key(original)?.let(awaitingDisplayed::remove)
            // receiveChatMessage() materializes a durable queue item as a local Basic-SIP
            // event. On affected SDKs its fromAddress is consequently the receiver itself;
            // the room peer is the real original sender and must receive the FCM read ACK.
            val sender = if (isQueueDeliveredIncoming(original)) {
                original.chatRoom.peerAddress.username
            } else {
                original.fromAddress.username?.takeIf { it.isNotBlank() }
                    ?: LinphoneUtils.getConversationId(original.chatRoom).ifEmpty {
                        original.chatRoom.peerAddress.username
                    }
            }
            val recipient = original.chatRoom.localAddress.username
            OfflineInboxRepository.notifyRead(sender, recipient, listOf(messageId))
        }
        val pendingKey = "${key(original)}|$status"
        if (!pending.add(pendingKey)) return

        if (retryAttempt == 0) {
            val now = System.currentTimeMillis()
            val delayMs = (nextReceiptDispatchTimeMs - now).coerceAtLeast(0L)
            nextReceiptDispatchTimeMs = maxOf(now, nextReceiptDispatchTimeMs) + RECEIPT_PACE_MS
            if (delayMs > 0L) {
                coreContext.postOnCoreThreadDelayed(
                    { dispatchReceipt(original, messageId, status, flag, pendingKey, force, retryAttempt) },
                    delayMs
                )
                return
            }
        }

        dispatchReceipt(original, messageId, status, flag, pendingKey, force, retryAttempt)
    }

    private fun dispatchReceipt(
        original: ChatMessage,
        messageId: String,
        status: String,
        flag: String,
        pendingKey: String,
        force: Boolean,
        retryAttempt: Int
    ) {
        if (!supported(original.chatRoom) || (!force && metadata(original).optBoolean(flag))) {
            pending.remove(pendingKey)
            return
        }

        val body = JSONObject()
            .put("version", 1)
            .put("messageId", messageId)
            .put("status", status)
            .toString()

        val receipt = runCatching {
            original.chatRoom.createMessageFromUtf8(RECEIPT_PREFIX + body)
        }.getOrNull()
        if (receipt == null) {
            pending.remove(pendingKey)
            return
        }

        // No original-message header: receipts cannot request receipts.
        receipt.setToBeStored(false)

        val listener = object : ChatMessageListenerStub() {
            override fun onMsgStateChanged(
                message: ChatMessage,
                state: ChatMessage.State?
            ) {
                when (state) {
                    ChatMessage.State.Delivered -> {
                        // Means FS accepted this outgoing SIP MESSAGE.
                        val data = metadata(original)
                        data.put(flag, true)
                        save(original, data)

                        pending.remove(pendingKey)
                        message.removeListener(this)
                        // This SDK may still store the outgoing control message.
                        message.chatRoom.deleteMessage(message)
                        Log.i(
                            "[Custom IMDN] FS accepted " +
                                    "id=$messageId status=$status"
                        )
                    }

                    ChatMessage.State.NotDelivered -> {
                        pending.remove(pendingKey)
                        message.removeListener(this)
                        message.chatRoom.deleteMessage(message)
                        if (retryAttempt < MAX_RECEIPT_RETRIES &&
                            (force || !metadata(original).optBoolean(flag))
                        ) {
                            val retryDelay = RECEIPT_RETRY_BASE_DELAY_MS * (retryAttempt + 1)
                            Log.w(
                                "[Custom IMDN] Receipt send failed id=$messageId status=$status; " +
                                    "retrying (${retryAttempt + 1}/$MAX_RECEIPT_RETRIES) in ${retryDelay}ms"
                            )
                            coreContext.postOnCoreThreadDelayed(
                                {
                                    sendReceipt(
                                        original,
                                        status,
                                        force = force,
                                        retryAttempt = retryAttempt + 1
                                    )
                                },
                                retryDelay
                            )
                        } else {
                            Log.w(
                                "[Custom IMDN] Receipt send failed " +
                                        "id=$messageId status=$status"
                            )
                        }
                    }

                    else -> {}
                }
            }
        }

        receipt.addListener(listener)

        try {
            Log.i("[Custom IMDN] Sending id=$messageId status=$status attempt=${retryAttempt + 1}")
            receipt.send()
        } catch (e: Exception) {
            pending.remove(pendingKey)
            receipt.removeListener(listener)
            Log.w("[Custom IMDN] Receipt send failed: ${e.message}")
        }
    }
}
