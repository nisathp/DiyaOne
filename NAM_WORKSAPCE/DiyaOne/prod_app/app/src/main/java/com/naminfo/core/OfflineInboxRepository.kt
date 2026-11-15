package com.naminfo.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.R
import com.naminfo.utils.FileUtils
import com.naminfo.utils.LinphoneUtils
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom
import org.linphone.core.Conference
import org.linphone.core.tools.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Durable inbox for items delivered by the Vercel offline queue.
 *
 * The HFT server owns file bytes and Vercel owns delivery metadata. When B reconnects, it
 * downloads signed media URLs itself and injects a persisted incoming message into the existing
 * local SIP chat room. A therefore does not need to remain online for B to receive media.
 */
object OfflineInboxRepository {
    private const val TAG = "[Offline Inbox]"
    private const val ENDPOINT = "https://androidpushnotification.vercel.app/api/offline-queue"
    private const val SIGNATURE = "015a3e11e51129f1e74326b993b508103506093f884b4f66e94404d334473820"
    private const val PREFS = "offline_inbox"
    private const val ITEMS_KEY = "items"
    private const val DOWNLOADED_MEDIA_KEY = "downloaded_media"
    private const val PENDING_OUTBOX_KEY = "pending_outbox"
    private const val CACHED_FCM_TOKEN_KEY = "cached_fcm_token"
    private const val MAX_STORED_ITEMS = 200
    private const val NOTIFICATION_ID = 7412
    const val QUEUE_MEDIA_HEADER = "X-Diya-Queue-Media"

    private val pulling = ConcurrentHashMap.newKeySet<String>()
    private val pendingRepull = ConcurrentHashMap.newKeySet<String>()
    private val cancelledOutgoingIds = ConcurrentHashMap.newKeySet<String>()
    private val queuedOutgoingIds = ConcurrentHashMap.newKeySet<String>()
    private val queuedMediaOutgoingIds = ConcurrentHashMap.newKeySet<String>()
    private val downloadingMediaKeys = ConcurrentHashMap.newKeySet<String>()
    private val outboxExecutor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    @Volatile
    private var cachedHftHost: String? = null

    @Volatile
    private var cachedFcmToken: String? = null

    fun cacheConfiguredHftHost(core: org.linphone.core.Core) {
        cachedHftHost = Uri.parse(core.fileTransferServer.orEmpty()).host?.takeIf { it.isNotBlank() }
    }

    private fun configuredHftHost(): String? = cachedHftHost

    fun updateCachedFcmToken(token: String?) {
        val clean = token?.trim()?.takeIf { it.isNotBlank() } ?: return
        cachedFcmToken = clean
        try {
            coreContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(CACHED_FCM_TOKEN_KEY, clean)
                .apply()
        } catch (_: Throwable) {
        }
        flushPendingOutboxAsync(clean)
    }

    private fun resolveCachedFcmToken(): String? {
        cachedFcmToken?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val saved = coreContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CACHED_FCM_TOKEN_KEY, null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (saved != null) cachedFcmToken = saved
            saved
        } catch (_: Throwable) {
            null
        }
    }

    fun pullForRegisteredAccount(phoneNumber: String?) {
        val number = normalize(phoneNumber) ?: return
        if (!pulling.add(number)) {
            pendingRepull.add(number)
            return
        }
        val configuredHftHost = configuredHftHost()

        fun startPullWithToken(token: String?) {
            if (token == null) {
                pulling.remove(number)
                Log.w("$TAG Can't pull queue: FCM token isn't available")
                return
            }
            updateCachedFcmToken(token)
            Thread {
                try {
                    pull(number, token, configuredHftHost)
                } finally {
                    pulling.remove(number)
                    if (pendingRepull.remove(number)) {
                        pullForRegisteredAccount(number)
                    }
                }
            }.start()
        }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = task.result?.takeIf { task.isSuccessful && it.isNotBlank() }
                    ?: resolveCachedFcmToken()
                startPullWithToken(token)
            }
        } catch (exception: Exception) {
            val fallback = resolveCachedFcmToken()
            if (fallback != null) {
                startPullWithToken(fallback)
            } else {
                pulling.remove(number)
                Log.w("$TAG Can't request FCM token: ${exception.message}")
            }
        }
    }

    /** Triggered by the data-only FCM wake-up and by a successful SIP registration. */
    fun pullForCurrentAccount() {
        coreContext.postOnCoreThread { core ->
            val number = core.defaultAccount?.params?.identityAddress?.username
            pullForRegisteredAccount(number)
        }
    }

    data class InboxItem(
        val sender: String,
        val text: String,
        val attachmentCount: Int,
        val receivedAt: Long
    )

    data class QueuedMedia(
        val messageId: String,
        val index: Int,
        val name: String,
        val url: String,
        val mimeType: String,
        val size: Long,
        val localPath: String?
    )

    fun storedItems(): List<InboxItem> = synchronized(lock) {
        val items = readStoredItems()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(
                    InboxItem(
                        sender = item.optString("sender", "Unknown"),
                        text = item.optString("text"),
                        attachmentCount = item.optJSONArray("media")?.length() ?: 0,
                        receivedAt = item.optLong("receivedAt", item.optLong("createdAt", 0L))
                    )
                )
            }
        }.asReversed()
    }

    /**
     * Vercel is the durable offline authority for every chat payload: text, image, video and
     * files. Text can be queued immediately. A media item is queued only when its HFT URL is
     * available, so B never restores a caption without its attachment metadata.
     */
    fun enqueueOutgoingAsSourceOfTruth(message: ChatMessage) {
        enqueueOutgoing(message, requireMedia = hasMediaContent(message))
    }

    /** Re-queues a message after a direct SIP delivery failure. */
    fun enqueueOutgoingFallback(message: ChatMessage) {
        enqueueOutgoingAsSourceOfTruth(message)
    }

    /** Queues a completed HFT upload even when SIP immediately reports `Delivered`. */
    fun enqueueUploadedMedia(message: ChatMessage) {
        enqueueOutgoing(message, requireMedia = true)
    }

    private fun enqueueOutgoing(message: ChatMessage, requireMedia: Boolean) {
        val id = CustomImdn.id(message)
        if (id == null) {
            Log.w("$TAG Completed outgoing message has no stable ID; it cannot be queued")
            return
        }
        Log.i("$TAG Evaluating completed outgoing message [$id] for durable queue")
        val sender = normalize(coreContext.core.defaultAccount?.params?.identityAddress?.username) ?: return
        val recipient = normalize(message.peerAddress.username) ?: return
        val plainText = message.contents
            .firstOrNull {
                it.isText && !it.isFile && !com.naminfo.utils.LinphoneUtils.isFileTransferMetadata(it.utf8Text.orEmpty())
            }
            ?.utf8Text.orEmpty().trim()
            .ifBlank {
                message.utf8Text.orEmpty().trim().let { raw ->
                    if (com.naminfo.utils.LinphoneUtils.isFileTransferMetadata(raw)) "" else raw
                }
            }
        val replyToId = message.getCustomHeader(com.naminfo.ui.main.chat.DIYA_REPLY_MESSAGE_ID_HEADER)
            ?.takeIf { it.isNotBlank() }
        val replyPreviewHeader = message.getCustomHeader(com.naminfo.ui.main.chat.DIYA_REPLY_PREVIEW_HEADER)
            ?.takeIf { it.isNotBlank() }
        val isForward = com.naminfo.ui.main.chat.isForwardedMessage(message)
        val headerDecoded = com.naminfo.ui.main.chat.decodeReplyPreviewHeader(replyToId, replyPreviewHeader)
        val baseText = if (
            plainText.isBlank() &&
            replyToId != null &&
            headerDecoded != null
        ) {
            com.naminfo.ui.main.chat.encodeReplyPreviewEnvelope(
                messageId = headerDecoded.first.messageId,
                sender = headerDecoded.first.sender,
                previewText = headerDecoded.first.text,
                messageText = headerDecoded.second.orEmpty()
            )
        } else {
            plainText
        }
        val text = if (
            isForward &&
            replyToId == null &&
            com.naminfo.ui.main.chat.decodeForwardEnvelope(baseText) == null
        ) {
            com.naminfo.ui.main.chat.encodeForwardEnvelope(baseText)
        } else {
            baseText
        }
        val media = extractHftMedia(message)
        val hasMedia = media.length() > 0
        if ((requireMedia && !hasMedia) || (baseText.isBlank() && !hasMedia)) {
            Log.w("$TAG No reusable HFT URL was found for completed message [$id]; it won't be queued")
            return
        }
        val isNewMediaUpgrade = hasMedia && queuedMediaOutgoingIds.add(id)
        val isNewMessage = queuedOutgoingIds.add(id)
        if (!isNewMessage && !isNewMediaUpgrade) return

        // Extract all native ChatMessage fields on the Core Thread before hopping to a background thread,
        // avoiding AB-BA deadlocks between ChatMessageImpl and CoreImpl JNI locks.
        val timestamp = CustomImdn.timestampMillis(message)
        val payload = JSONObject().apply {
            put("action", "enqueue")
            put("id", id)
            put("sender", sender)
            put("recipient", recipient)
            put("text", text)
            put("media", media)
            // Preserve A's send time. B may materialize this message much later after
            // reconnecting, but its bubble must stay in chronological order.
            put("sentAt", timestamp)
            if (!replyToId.isNullOrBlank()) put("replyToId", replyToId)
            if (!replyPreviewHeader.isNullOrBlank()) put("replyPreview", replyPreviewHeader)
            if (isForward) put("isForward", true)
        }
        savePendingOutboxItem(payload)
        resolveCachedFcmToken()?.let { flushPendingOutboxAsync(it) }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = task.result?.takeIf { task.isSuccessful && it.isNotBlank() }
                    ?: resolveCachedFcmToken()
                if (token == null) {
                    queuedOutgoingIds.remove(id)
                    if (hasMedia) queuedMediaOutgoingIds.remove(id)
                    return@addOnCompleteListener
                }
                updateCachedFcmToken(token)
            }
        } catch (exception: Exception) {
            val fallback = resolveCachedFcmToken()
            if (fallback != null) {
                flushPendingOutboxAsync(fallback)
            } else {
                queuedOutgoingIds.remove(id)
                if (hasMedia) queuedMediaOutgoingIds.remove(id)
                Log.w("$TAG Can't queue failed message [$id]: ${exception.message}")
            }
        }
    }

    fun removeQueuedOutgoing(message: ChatMessage) {
        val id = CustomImdn.id(message) ?: return
        cancelledOutgoingIds.add(id)
        queuedOutgoingIds.remove(id)
        queuedMediaOutgoingIds.remove(id)
        removePendingOutboxItem(id)
        val sender = normalize(coreContext.core.defaultAccount?.params?.identityAddress?.username) ?: return
        val recipient = normalize(message.peerAddress.username) ?: return
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = task.result?.takeIf { task.isSuccessful && it.isNotBlank() }
                    ?: resolveCachedFcmToken()
                    ?: return@addOnCompleteListener
                Thread { cancelOutgoing(id, sender, recipient, token) }.start()
            }
        } catch (exception: Exception) {
            val fallback = resolveCachedFcmToken()
            if (fallback != null) {
                Thread { cancelOutgoing(id, sender, recipient, fallback) }.start()
            } else {
                Log.w("$TAG Can't cancel fallback [$id]: ${exception.message}")
            }
        }
    }

    private fun savePendingOutboxItem(payload: JSONObject) {
        val id = payload.optString("id").takeIf { it.isNotBlank() } ?: return
        if (id in cancelledOutgoingIds) return
        synchronized(lock) {
            val current = loadPendingOutboxLocked()
            val updated = JSONArray()
            var replaced = false
            for (i in 0 until current.length()) {
                val existing = current.optJSONObject(i) ?: continue
                if (existing.optString("id") == id) {
                    updated.put(payload)
                    replaced = true
                } else {
                    updated.put(existing)
                }
            }
            if (!replaced) updated.put(payload)
            savePendingOutboxLocked(updated)
        }
    }

    private fun removePendingOutboxItem(id: String) {
        synchronized(lock) {
            val current = loadPendingOutboxLocked()
            val updated = JSONArray()
            var changed = false
            for (i in 0 until current.length()) {
                val existing = current.optJSONObject(i) ?: continue
                if (existing.optString("id") == id) {
                    changed = true
                } else {
                    updated.put(existing)
                }
            }
            if (changed) savePendingOutboxLocked(updated)
        }
    }

    private fun loadPendingOutboxLocked(): JSONArray {
        return try {
            val raw = coreContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PENDING_OUTBOX_KEY, null)
            if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun savePendingOutboxLocked(array: JSONArray) {
        try {
            coreContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PENDING_OUTBOX_KEY, array.toString())
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun flushPendingOutboxAsync(senderToken: String? = null) {
        outboxExecutor.execute {
            val token = senderToken?.takeIf { it.isNotBlank() } ?: resolveCachedFcmToken() ?: return@execute
            val snapshot = synchronized(lock) {
                val arr = loadPendingOutboxLocked()
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(JSONObject(it.toString())) }
                    }
                }
            }.sortedWith(compareBy<JSONObject>({ itemTimestampMillis(it) }, { it.optString("id") }))
            for (item in snapshot) {
                val id = item.optString("id")
                val sender = item.optString("sender")
                val recipient = item.optString("recipient")
                if (id.isBlank()) continue
                if (id in cancelledOutgoingIds) {
                    removePendingOutboxItem(id)
                    continue
                }
                item.put("senderToken", token)
                var delivered = false
                for (attempt in 0 until 3) {
                    val status = postJson(item)
                    if (status in 200..299) {
                        delivered = true
                        break
                    }
                    Log.w("$TAG Failed to queue outgoing [$id] attempt=${attempt + 1}, HTTP $status")
                    if (attempt < 2) {
                        try {
                            Thread.sleep(600L * (attempt + 1))
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                if (delivered) {
                    removePendingOutboxItem(id)
                    Log.i("$TAG Queued durable media/message [$id] for $recipient")
                    if (id in cancelledOutgoingIds) cancelOutgoing(id, sender, recipient, token)
                } else {
                    queuedOutgoingIds.remove(id)
                    queuedMediaOutgoingIds.remove(id)
                }
            }
        }
    }

    private fun cancelOutgoing(id: String, sender: String, recipient: String, senderToken: String) {
        val status = postJson(
            JSONObject().apply {
            put("action", "cancel")
            put("id", id)
            put("sender", sender)
            put("recipient", recipient)
            put("senderToken", senderToken)
        }
        )
        if (status !in 200..299) Log.w("$TAG Failed to remove delivered queue item [$id], HTTP $status")
    }

    private fun pull(phoneNumber: String, fcmToken: String, configuredHost: String?) {
        var connection: HttpURLConnection? = null
        try {
            val query = "phoneNumber=${encode(phoneNumber)}&fcmToken=${encode(fcmToken)}"
            connection = (URL("$ENDPOINT?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("x-signature", SIGNATURE)
                connectTimeout = 8_000
                readTimeout = 15_000
            }
            if (connection.responseCode !in 200..299) {
                Log.w("$TAG Queue pull failed with HTTP ${connection.responseCode}")
                return
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val items = JSONObject(response).optJSONArray("items") ?: return
            if (items.length() == 0) return

            // Vercel is the single durable offline store for text and media. Validate all queue
            // entries and materialize them by sender time before entering the Core thread. Media
            // bytes remain on HFT until the recipient explicitly taps its attachment.
            val prepared = buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    if (item.optString("id").matches(Regex("[A-Za-z0-9._-]{8,200}"))) {
                        prepareMediaMetadata(item, configuredHost)?.let(::add)
                    }
                }
            }.sortedWith(compareBy<JSONObject>({ itemTimestampMillis(it) }, { it.optString("id") }))
            if (prepared.isEmpty()) return

            coreContext.postOnCoreThread {
                val delivered = prepared.filter(::injectIncomingMessage)
                if (delivered.isEmpty()) return@postOnCoreThread

                // Record only after it has been inserted into the local chat history. If the
                // process dies before this point, the id remains on Vercel and is retried.
                val deliveredJson = JSONArray(delivered)
                val newIds = persistNewItems(deliveredJson)
                if (newIds.isNotEmpty()) {
                    val newItems = delivered.filter { it.optString("id") in newIds }
                    showNotification(newItems)
                }

                // A prior pull may have stored an item locally but failed before its ACK
                // reached Vercel. In that case it is not new on the next pull, yet it must
                // still be acknowledged or it will be delivered again after every login.
                val acknowledgedIds = delivered.mapNotNull { item ->
                    item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9._-]{8,200}")) }
                }.distinct()
                if (acknowledgedIds.isNotEmpty()) {
                    Thread { acknowledge(phoneNumber, fcmToken, acknowledgedIds) }.start()
                }
            }
        } catch (exception: Exception) {
            Log.w("$TAG Queue pull failed: ${exception.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun persistNewItems(items: JSONArray): List<String> = synchronized(lock) {
        val stored = readStoredItems()
        val existingIds = buildSet {
            for (index in 0 until stored.length()) add(stored.optJSONObject(index)?.optString("id").orEmpty())
        }
        val accepted = mutableListOf<String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (!id.matches(Regex("[A-Za-z0-9._-]{8,200}")) || id in existingIds) continue
            item.put("receivedAt", System.currentTimeMillis())
            stored.put(item)
            accepted += id
        }
        while (stored.length() > MAX_STORED_ITEMS) stored.remove(0)
        preferences().edit().putString(ITEMS_KEY, stored.toString()).apply()
        accepted
    }

    /** Validates HFT URLs for an incoming queue item without downloading their file bytes. */
    private fun prepareMediaMetadata(source: JSONObject, configuredHost: String?): JSONObject? {
        val item = JSONObject(source.toString())
        val media = item.optJSONArray("media") ?: return item
        for (index in 0 until media.length()) {
            val mediaItem = media.optJSONObject(index) ?: return null
            val url = mediaItem.optString("url")
            val name = safeFileName(mediaItem.optString("name", "attachment"))
            if (!isTrustedHftUrl(url, configuredHost)) {
                Log.w("$TAG Refusing untrusted queued media URL")
                return null
            }
            mediaItem.put("name", name)
        }
        return item
    }

    /** Returns queue attachment metadata stored with a locally injected incoming message. */
    fun queuedMedia(message: ChatMessage): List<QueuedMedia> {
        val stableId = CustomImdn.id(message)
        val sipMessageId = message.messageId?.takeIf { it.isNotBlank() }
        val json = message.getCustomHeader(QUEUE_MEDIA_HEADER)?.takeIf { it.isNotBlank() }
            ?: CustomImdn.queuedMediaJson(message)
            ?: findPersistedQueuedMediaJson(stableId, sipMessageId)
            ?: message.contents.firstOrNull { it.isText && !it.isFile }?.utf8Text?.trim()?.takeIf {
                CustomImdn.isQueueDeliveredIncoming(message) &&
                    it.startsWith("[") &&
                    com.naminfo.utils.LinphoneUtils.isFileTransferMetadata(it)
            }
            ?: return emptyList()
        val id = stableId ?: sipMessageId ?: return emptyList()
        val configuredHost = configuredHftHost()
        return try {
            val media = JSONArray(json)
            buildList {
                for (index in 0 until media.length()) {
                    val item = media.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    if (!isTrustedHftUrl(url, configuredHost)) continue
                    val name = safeFileName(item.optString("name", "attachment"))
                    add(
                        QueuedMedia(
                            messageId = id,
                            index = index,
                            name = name,
                            url = url,
                            mimeType = item.optString("mimeType", "application/octet-stream"),
                            size = item.optLong("size", 0L),
                            localPath = downloadedMediaPath(id, index)
                                ?: sipMessageId?.let { downloadedMediaPath(it, index) }
                        )
                    )
                }
            }
        } catch (exception: Exception) {
            Log.w("$TAG Couldn't read queued media metadata: ${exception.message}")
            emptyList()
        }
    }

    /** Returns any locally downloaded queued media path indexed under either stable or SIP message ID. */
    fun findDownloadedMediaPath(stableId: String?, sipMessageId: String?, index: Int = 0): String? =
        stableId?.takeIf { it.isNotBlank() }?.let { downloadedMediaPath(it, index) }
            ?: sipMessageId?.takeIf { it.isNotBlank() }?.let { downloadedMediaPath(it, index) }

    /** Downloads an offline queue attachment asynchronously, coalescing concurrent callers for the same item. */
    fun downloadQueuedMedia(media: QueuedMedia, onResult: (String?) -> Unit) {
        val existing = downloadedMediaPath(media.messageId, media.index)
        if (existing != null) {
            onResult(existing)
            return
        }
        val mediaKey = "${media.messageId}:${media.index}"
        var shouldStartWorker = false
        synchronized(downloadingMediaCallbacks) {
            val callbacks = downloadingMediaCallbacks[mediaKey]
            if (callbacks != null) {
                callbacks.add(onResult)
            } else {
                downloadingMediaCallbacks[mediaKey] = mutableListOf(onResult)
                shouldStartWorker = true
            }
        }
        if (!shouldStartWorker) return
        downloadingMediaKeys.add(mediaKey)
        Thread {
            val path = downloadQueuedMediaBlocking(media)
            downloadingMediaKeys.remove(mediaKey)
            val callbacks = synchronized(downloadingMediaCallbacks) {
                downloadingMediaCallbacks.remove(mediaKey).orEmpty()
            }
            callbacks.forEach { callback -> callback(path) }
        }.start()
    }

    /** Synchronously downloads a queued media file on a worker thread (never call on Main or Core threads). */
    fun downloadQueuedMediaSync(media: QueuedMedia): String? {
        downloadedMediaPath(media.messageId, media.index)?.let { return it }
        val latch = java.util.concurrent.CountDownLatch(1)
        var resolvedPath: String? = null
        downloadQueuedMedia(media) { path ->
            resolvedPath = path
            latch.countDown()
        }
        latch.await(35, java.util.concurrent.TimeUnit.SECONDS)
        return resolvedPath ?: downloadedMediaPath(media.messageId, media.index)
    }

    private fun downloadQueuedMediaBlocking(media: QueuedMedia): String? {
        downloadedMediaPath(media.messageId, media.index)?.let { return it }
        if (!isTrustedHftUrl(media.url, configuredHftHost())) {
            Log.w("$TAG Refusing untrusted queued media URL for [${media.name}]")
            return null
        }
        var destination: File? = null
        var connection: HttpURLConnection? = null
        return try {
            val target = FileUtils.getFileStoragePath(
                media.name,
                isImage = FileUtils.isExtensionImage(media.name)
            )
            target.parentFile?.mkdirs()
            destination = target
            connection = (URL(media.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = false
            }
            if (connection.responseCode !in 200..299) {
                Log.w("$TAG HFT download failed with HTTP ${connection.responseCode} for [${media.name}]")
                return null
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (!target.isFile || target.length() == 0L) {
                target.delete()
                return null
            }
            rememberDownloadedMedia(media.messageId, media.index, target.absolutePath)
            Log.i("$TAG Downloaded queued media [${media.name}] after user tap")
            target.absolutePath
        } catch (exception: Exception) {
            destination?.delete()
            Log.w("$TAG Couldn't download queued media [${media.name}]: ${exception.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Normalizes an offline queue item timestamp to epoch milliseconds for chronological restore. */
    internal fun itemTimestampMillis(item: JSONObject): Long {
        val raw = item.optLong(
            "sentAt",
            item.optLong("createdAt", item.optLong("timestamp", item.optLong("receivedAt", 0L)))
        )
        return CustomImdn.normalizeTimestampMillis(raw) ?: 0L
    }

    /**
     * Resolves an existing Basic 1:1 room on the current account or creates it on clean login so
     * pending offline messages can be injected immediately after authentication.
     */
    private fun findOrCreateBasicRoomForSender(sender: String): ChatRoom? {
        val core = coreContext.core
        val defaultAccount = core.defaultAccount ?: return null
        val defaultLocal = normalize(defaultAccount.params.identityAddress?.username)
        val matchingRooms = core.chatRooms.filter { candidate ->
            normalize(candidate.peerAddress.username) == sender &&
                (defaultLocal == null || normalize(candidate.localAddress.username) == defaultLocal)
        }
        val existing = matchingRooms.firstOrNull { it.account == defaultAccount }
            ?: matchingRooms.firstOrNull()
        if (existing != null) return existing

        val domain = defaultAccount.params.identityAddress?.domain
            ?: defaultAccount.params.serverAddress?.domain
            ?: return null
        val peerAddress = core.interpretUrl("sip:$sender@$domain", false) ?: return null
        val canonicalPeer = LinphoneUtils.canonicalizeBasicChatAddress(peerAddress, defaultAccount)
        val params = core.createConferenceParams(null).apply {
            isChatEnabled = true
            isGroupEnabled = false
            account = defaultAccount
            securityLevel = Conference.SecurityLevel.None
            chatParams?.apply {
                backend = ChatRoom.Backend.Basic
                ephemeralLifetime = 0
            }
        }
        val localAddress = defaultAccount.params.identityAddress
        return LinphoneUtils.findExistingOneToOneChatRoom(core, params, localAddress, canonicalPeer)
            ?: core.createChatRoom(params, arrayOf(canonicalPeer))
    }

    /** Must run on the Linphone Core thread. Creates a durable, incoming local chat event. */
    private fun injectIncomingMessage(item: JSONObject): Boolean {
        val id = item.optString("id")
        val sender = normalize(item.optString("sender")) ?: return false
        val room = findOrCreateBasicRoomForSender(sender)
        if (room == null) {
            Log.w("$TAG No local chat room for queued sender [$sender], keeping [$id] for retry")
            return false
        }
        // A recipient can be back online while the sender's durable enqueue request is
        // already in flight. The native SIP MESSAGE then arrives first, carrying the same
        // X-Diya-Message-ID. It is already the preferred normal chat row, so acknowledge
        // the queue copy without injecting a second image/bubble unless the native copy
        // arrived incomplete/undisplayable (e.g. multipart dropped in Basic SIP).
        val mediaJson = item.optJSONArray("media")?.takeIf { it.length() > 0 }?.toString()
        val siblingRooms = LinphoneUtils.findSiblingOneToOneChatRooms(coreContext.core, room)
        val matchingNative = siblingRooms.asSequence()
            .flatMap { it.getHistoryMessageEvents(0).asSequence() }
            .mapNotNull { it.chatMessage }
            .firstOrNull { message ->
                !message.isOutgoing &&
                    !CustomImdn.isHiddenControlMessage(message) &&
                    CustomImdn.id(message) == id
            }
        var replacedIncompleteGhost = false
        if (matchingNative != null) {
            val isIncompleteGhost = !LinphoneUtils.isDisplayableChatMessage(matchingNative) &&
                (mediaJson != null || item.optString("text").isNotBlank())
            if (!isIncompleteGhost) {
                Log.i("$TAG Native SIP message already present for [$id], acknowledging queue copy only")
                return true
            }
            Log.w("$TAG Replacing incomplete SIP copy for [$id] with complete durable queue copy")
            matchingNative.chatRoom.deleteMessage(matchingNative)
            replacedIncompleteGhost = true
        }
        // Reserve the application message before creating the local queue copy.
        // If the direct SIP MESSAGE arrives moments later, CustomImdn will drop
        // that duplicate even on handsets whose Core history failed to persist.
        if (!CustomImdn.claimIncomingDelivery(room, id, replaceExisting = replacedIncompleteGhost)) {
            Log.i("$TAG Incoming message [$id] is already owned by the direct SIP path")
            return true
        }
        return try {
            val message = room.createEmptyMessage()
            message.setToBeStored(true)
            message.addCustomHeader("X-Diya-Message-ID", id)
            message.addCustomHeader(CustomImdn.QUEUE_DELIVERY_HEADER, "1")
            val originalTimestamp = itemTimestampMillis(item)
            if (originalTimestamp > 0L) {
                message.addCustomHeader(
                    CustomImdn.ORIGINAL_TIMESTAMP_HEADER,
                    originalTimestamp.toString()
                )
            }
            val rawText = item.optString("text").orEmpty()
            val decodedForwardText = com.naminfo.ui.main.chat.decodeForwardEnvelope(rawText)
            val replyToId = item.optString("replyToId").takeIf { it.isNotBlank() }
            val replyPreview = item.optString("replyPreview").takeIf { it.isNotBlank() }
            val isForward = item.optBoolean("isForward", false) ||
                item.optBoolean("isForwarded", false) ||
                decodedForwardText != null
            val itemText = (decodedForwardText ?: rawText).takeIf { it.isNotBlank() }
            if (itemText != null) {
                message.addUtf8TextContent(
                    if (isForward && replyToId == null) {
                        com.naminfo.ui.main.chat.encodeForwardEnvelope(itemText)
                    } else {
                        itemText
                    }
                )
            } else if (mediaJson != null) {
                // Liblinphone's Basic SQLite history marks zero-content injected messages as
                // NotDelivered (`Unsupported content-type: /`) and drops them after restart.
                message.addUtf8TextContent(mediaJson)
            }
            if (mediaJson != null) message.addCustomHeader(QUEUE_MEDIA_HEADER, mediaJson)
            if (replyToId != null) {
                message.addCustomHeader(com.naminfo.ui.main.chat.DIYA_REPLY_MESSAGE_ID_HEADER, replyToId)
            }
            if (replyPreview != null) {
                message.addCustomHeader(com.naminfo.ui.main.chat.DIYA_REPLY_PREVIEW_HEADER, replyPreview)
            }
            if (isForward) {
                message.addCustomHeader(com.naminfo.ui.main.chat.DIYA_FORWARDED_HEADER, "1")
            }
            CustomImdn.recordQueueIncomingMetadata(
                message = message,
                id = id,
                originalTimestampMs = originalTimestamp,
                queuedMediaJson = mediaJson,
                isForward = isForward
            )
            room.receiveChatMessage(message)
            if (mediaJson != null) {
                rememberQueuedMediaJson(id = id, messageId = message.messageId, mediaJson = mediaJson)
            }
            // A queue-created message bypasses CoreContext.onMessagesReceived(), where native
            // SIP messages emit their per-message delivery receipt. Keep the queue API ACK, but
            // also send the normal SIP receipt so a batch restore cannot leave random outgoing
            // media bubbles at the single-tick state when an FCM acknowledgement is delayed.
            CustomImdn.acknowledgeQueueDelivery(message)
            Log.i("$TAG Inserted queued incoming chat message [$id] from [$sender]")
            true
        } catch (exception: Exception) {
            Log.w("$TAG Couldn't insert queued message [$id]: ${exception.message}")
            false
        }
    }

    private fun acknowledge(phoneNumber: String, fcmToken: String, ids: List<String>) {
        val status = postJson(
            JSONObject().apply {
            put("action", "ack")
            put("recipient", phoneNumber)
            put("fcmToken", fcmToken)
            put("ids", JSONArray(ids))
        }
        )
        if (status !in 200..299) Log.w("$TAG Queue acknowledgement failed with HTTP $status")
    }

    private fun requestReplay(phoneNumber: String, fcmToken: String, ids: List<String>) {
        val status = postJson(
            JSONObject().apply {
                put("action", "replay")
                put("recipient", phoneNumber)
                put("fcmToken", fcmToken)
                put("ids", JSONArray(ids))
            }
        )
        if (status !in 200..299) Log.w("$TAG Queue replay request failed with HTTP $status")
    }

    private fun clearLegacyInboxUi() {
        synchronized(lock) {
            preferences().edit().remove(ITEMS_KEY).apply()
        }
        NotificationManagerCompat.from(coreContext.context).cancel(NOTIFICATION_ID)
    }

    /** Called on A after Vercel confirms B is online and asks for a native SIP replay. */
    fun replayQueuedOutgoing(messageId: String?, recipient: String?) {
        val id = messageId.orEmpty()
        val expectedRecipient = normalize(recipient)
        if (id.isBlank() || expectedRecipient == null) return

        val original = coreContext.core.chatRooms.asSequence()
            .filter { room -> normalize(room.peerAddress.username) == expectedRecipient }
            .flatMap { room -> room.getHistoryMessageEvents(0).asSequence().mapNotNull { it.chatMessage } }
            .firstOrNull { message ->
                message.isOutgoing && !CustomImdn.isReceipt(message) && CustomImdn.id(message) == id
            }
        if (original == null) {
            Log.w("$TAG No original outgoing SIP message found for replay [$id]")
            return
        }
        try {
            CustomImdn.prepareOutgoing(original)
            original.send()
            Log.i("$TAG Replayed queued SIP message [$id] to $expectedRecipient")
        } catch (exception: Exception) {
            Log.w("$TAG Couldn't replay queued SIP message [$id]: ${exception.message}")
        }
    }

    private fun postJson(body: JSONObject): Int {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-signature", SIGNATURE)
                connectTimeout = 8_000
                readTimeout = 15_000
                doOutput = true
            }
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val response = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.replace(Regex("[\\r\\n]+"), " ")
                    ?.take(500)
                    .orEmpty()
                Log.w("$TAG Queue API rejected request (HTTP $status): $response")
            }
            status
        } catch (exception: Exception) {
            Log.w("$TAG Queue POST failed: ${exception.message}")
            -1
        } finally {
            connection?.disconnect()
        }
    }

    /** Identifies payloads that must wait for a finalized HFT URL before queueing. */
    private fun hasMediaContent(message: ChatMessage): Boolean =
        message.contents.any { content ->
            content.isFile || content.isFileTransfer || content.isVoiceRecording
        }

    /** Reads an HFT URL from Linphone's finalized message content when the SDK exposes it. */
    private fun extractHftMedia(message: ChatMessage): JSONArray {
        val urlPattern = Regex("https?://[^\\s\\\"<>]+:8019/files/[A-Za-z0-9]+\\?token=[A-Za-z0-9_-]+")
        val fileNamePattern = Regex("<file-name>([^<]+)</file-name>")
        val contentTypePattern = Regex("<content-type>([^<]+)</content-type>")
        val fileSizePattern = Regex("<file-size>(\\d+)</file-size>")
        val found = linkedMapOf<String, JSONObject>()
        val candidates = buildList {
            add(message.utf8Text.orEmpty())
            add(message.externalBodyUrl.orEmpty())
            message.contents.forEach { content ->
                add(content.utf8Text.orEmpty())
                add(content.filePath.orEmpty())
                add(content.name.orEmpty())
            }
        }
        val configuredHost = configuredHftHost()
        for (candidate in candidates) {
            val url = urlPattern.find(candidate)?.value ?: continue
            if (!isTrustedHftUrl(url, configuredHost)) {
                Log.w("$TAG Ignoring media URL from an untrusted HFT host")
                continue
            }
            val content = message.contents.firstOrNull { candidate.contains(it.name.orEmpty()) }
            found.putIfAbsent(
                url,
                JSONObject().apply {
                put("url", url)
                put("name", fileNamePattern.find(candidate)?.groupValues?.getOrNull(1) ?: content?.name ?: "attachment")
                put(
                    "mimeType",
                    contentTypePattern.find(candidate)?.groupValues?.getOrNull(1)
                        ?: "${content?.type ?: "application"}/${content?.subtype ?: "octet-stream"}"
                )
                put("size", fileSizePattern.find(candidate)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: content?.fileSize ?: 0)
            }
            )
        }
        return JSONArray(found.values.toList())
    }

    private fun isTrustedHftUrl(value: String, configuredHost: String?): Boolean {
        val uri = Uri.parse(value)
        if (uri.scheme !in setOf("http", "https") || uri.port != 8019 || !uri.path.orEmpty().startsWith("/files/")) {
            return false
        }
        return uri.host.equals(configuredHost, ignoreCase = true) ||
            uri.host.equals("203.95.216.68", ignoreCase = true) ||
            uri.host.equals("mobionglobal.com", ignoreCase = true)
    }

    private fun showNotification(newItems: List<JSONObject>) {
        if (newItems.isEmpty()) return
        val context = coreContext.context
        val intent = Intent(context, OfflineInboxActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // This can arrive while the user is online as well: the durable queue mirrors SIP
        // delivery for reliability. Keep the notification neutral instead of calling it
        // "offline" and do not leak sender numbers or HFT metadata on the lock screen.
        val count = newItems.size
        val summary = "$count message${if (count == 1) "" else "s"} received"
        val notification = NotificationCompat.Builder(context, context.getString(R.string.notification_channel_chat_id))
            .setSmallIcon(R.drawable.chat_teardrop_text)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(summary)
            // Android launchers/OEM notification UIs don't derive a badge number from the
            // notification text. Publish the received offline batch size explicitly so B sees
            // the same count in the notification shade and on supported app-icon badges.
            .setNumber(count)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (exception: SecurityException) {
            Log.w("$TAG Notification permission isn't granted")
        }
    }

    private fun readStoredItems(): JSONArray = try {
        JSONArray(preferences().getString(ITEMS_KEY, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun preferences() = coreContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun downloadedMediaPath(messageId: String, index: Int): String? {
        val path = safePreferences()?.getString("$DOWNLOADED_MEDIA_KEY:$messageId:$index", null) ?: return null
        return path.takeIf { File(it).isFile && File(it).length() > 0L }
    }

    private fun rememberDownloadedMedia(messageId: String, index: Int, path: String) {
        safePreferences()?.edit()?.putString("$DOWNLOADED_MEDIA_KEY:$messageId:$index", path)?.apply()
    }

    private fun safePreferences(): android.content.SharedPreferences? =
        runCatching { preferences() }.getOrNull()

    private fun rememberQueuedMediaJson(id: String, messageId: String?, mediaJson: String) {
        if (mediaJson.isBlank()) return
        val normalizedMid = messageId?.takeIf { it.isNotBlank() }
        inMemoryQueuedMediaByKey["id:$id"] = mediaJson
        if (normalizedMid != null) {
            inMemoryQueuedMediaByKey["mid:$normalizedMid"] = mediaJson
        }
        safePreferences()?.edit()?.apply {
            putString("$QUEUED_MEDIA_JSON_ID_KEY:$id", mediaJson)
            if (normalizedMid != null) {
                putString("$QUEUED_MEDIA_JSON_MID_KEY:$normalizedMid", mediaJson)
            }
            apply()
        }
    }

    private fun findPersistedQueuedMediaJson(id: String?, messageId: String?): String? {
        val cleanId = id?.takeIf { it.isNotBlank() }
        val cleanMid = messageId?.takeIf { it.isNotBlank() }
        if (cleanId != null) {
            inMemoryQueuedMediaByKey["id:$cleanId"]?.takeIf { it.isNotBlank() }?.let { return it }
            safePreferences()?.getString("$QUEUED_MEDIA_JSON_ID_KEY:$cleanId", null)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        if (cleanMid != null) {
            inMemoryQueuedMediaByKey["mid:$cleanMid"]?.takeIf { it.isNotBlank() }?.let { return it }
            safePreferences()?.getString("$QUEUED_MEDIA_JSON_MID_KEY:$cleanMid", null)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }

    private val downloadingMediaCallbacks = HashMap<String, MutableList<(String?) -> Unit>>()
    private val inMemoryQueuedMediaByKey = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val QUEUED_MEDIA_JSON_ID_KEY = "queued_media_json_id"
    private val QUEUED_MEDIA_JSON_MID_KEY = "queued_media_json_mid"

    private fun normalize(value: String?): String? = value.orEmpty().filter(Char::isDigit)
        .takeIf { it.length in 6..20 }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun safeFileName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .take(120)
        .ifBlank { "attachment" }
}
