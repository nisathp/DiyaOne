package com.naminfo.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale

/** Stores a small, private on-device history of SIP call signaling for the Logs screen. */
object SipTraceRepository {
    enum class Direction { SENT, RECEIVED, UNKNOWN }

    data class Entry(
        val timestamp: Long,
        val direction: Direction,
        val startLine: String,
        val callId: String,
        val cseq: String,
        val details: String
    ) {
        val label: String
            get() {
                val status = statusRegex.matchEntire(startLine)
                if (status != null) {
                    val method = cseq.substringAfter(' ', "SIP")
                    return "${status.groupValues[1]} ${status.groupValues[2]} · $method"
                }
                val method = startLine.substringBefore(' ')
                val number = cseq.substringBefore(' ').takeIf { it.isNotBlank() }
                return if (number != null) "$method · CSeq $number" else method
            }

        val mediaSummary: String
            get() {
                val media = details.lineSequence().filter { it.startsWith("m=") }.toList()
                if (media.isEmpty()) return "SIP signaling"
                val audio = media.any { it.startsWith("m=audio") }
                val video = media.any { it.startsWith("m=video") }
                return when {
                    audio && video -> "Audio + video SDP"
                    audio -> "Audio only SDP"
                    video -> "Video SDP"
                    else -> "SDP media"
                }
            }
    }

    val entries = MutableLiveData<List<Entry>>(emptyList())

    private const val PREFS_NAME = "sip_trace_history"
    private const val PREFS_ENTRIES = "entries"
    private const val PREFS_COMPLETED_CALL_IDS = "completed_call_ids"
    private const val MAX_ENTRIES = 250
    private const val CALLS_BEFORE_AUTO_CLEAR = 3
    private val callMethods = setOf("INVITE", "ACK", "BYE", "CANCEL", "UPDATE", "PRACK")
    private val startLineRegex = Regex("^(?:[A-Z]+\\s+\\S+\\s+SIP/2\\.0|SIP/2\\.0\\s+\\d{3}\\s+.*)$")
    private val statusRegex = Regex("^SIP/2\\.0\\s+(\\d{3})\\s+(.*)$")
    private val callIdRegex = Regex("(?im)^Call-ID:\\s*(.+)$")
    private val cseqRegex = Regex("(?im)^CSeq:\\s*(.+)$")
    private val fromRegex = Regex("(?im)^From:\\s*(.+)$")
    private val toRegex = Regex("(?im)^To:\\s*(.+)$")
    private val sipUsernameRegex = Regex("(?i)sips?:([^@;>\\s]+)@")
    private val sensitiveAuthRegex = Regex("(?im)^(Proxy-Authorization|Authorization):.*$")
    private val pushParamRegex = Regex("(?i);pn-[a-z0-9_-]+=[^;>\\s]+")
    private val lock = Any()
    private val messages = ArrayDeque<Entry>()
    private val completedCallIds = linkedSetOf<String>()
    private val requestDirectionByCall = mutableMapOf<Pair<String, String>, Direction>()
    private var localUsername: String = ""
    private var context: Context? = null
    private var currentMessage: MessageBuilder? = null
    private val flushHandler = Handler(Looper.getMainLooper())
    private val flushPendingMessage = Runnable {
        synchronized(lock) { finishCurrentMessage() }
    }

    fun initialize(context: Context) {
        synchronized(lock) {
            if (this.context != null) return
            this.context = context.applicationContext
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = preferences
                .getString(PREFS_ENTRIES, null)
            val savedCallIds = preferences.getString(PREFS_COMPLETED_CALL_IDS, null)
            if (!savedCallIds.isNullOrBlank()) {
                runCatching {
                    val json = JSONArray(savedCallIds)
                    for (index in 0 until json.length()) {
                        json.optString(index).takeIf { it.isNotBlank() }?.let(completedCallIds::add)
                    }
                }
            }
            if (!saved.isNullOrBlank()) {
                runCatching {
                    val json = JSONArray(saved)
                    for (index in 0 until json.length()) {
                        val item = json.getJSONObject(index)
                        messages.addLast(
                            Entry(
                                timestamp = item.optLong("time"),
                                direction = runCatching {
                                    Direction.valueOf(item.optString("direction"))
                                }.getOrDefault(Direction.UNKNOWN),
                                startLine = item.optString("start"),
                                callId = item.optString("callId"),
                                cseq = item.optString("cseq"),
                                details = item.optString("details")
                            )
                        )
                    }
                    trimEntries()
                }
            }
            entries.postValue(messages.toList().asReversed())
        }
    }

    fun setLocalIdentity(username: String?) {
        synchronized(lock) {
            localUsername = username.orEmpty().substringBefore('@').trim()
        }
    }

    /** Receives liblinphone log lines and turns complete SIP messages into local trace entries. */
    fun onLogMessageWritten(message: String) {
        if (message.isBlank()) return
        synchronized(lock) {
            message.lineSequence().forEach { rawLine ->
                val line = sanitize(rawLine.trimEnd('\r'))
                if (startLineRegex.matches(line.trim())) {
                    finishCurrentMessage()
                    currentMessage = MessageBuilder(System.currentTimeMillis(), line.trim())
                } else {
                    val builder = currentMessage ?: return@forEach
                    if (isSipDetailLine(line)) builder.lines.add(line)
                }
            }
            flushHandler.removeCallbacks(flushPendingMessage)
            if (currentMessage != null) flushHandler.postDelayed(flushPendingMessage, 700)
        }
    }

    fun clear() {
        synchronized(lock) {
            messages.clear()
            completedCallIds.clear()
            currentMessage = null
            requestDirectionByCall.clear()
            persistAndPublish()
        }
    }

    /** Clears the saved trace after three distinct calls have reached a terminal state. */
    fun onCallCompleted(callId: String?) {
        if (callId.isNullOrBlank()) return

        synchronized(lock) {
            if (!completedCallIds.add(callId)) return
            if (completedCallIds.size >= CALLS_BEFORE_AUTO_CLEAR) {
                messages.clear()
                completedCallIds.clear()
                currentMessage = null
                requestDirectionByCall.clear()
                flushHandler.removeCallbacks(flushPendingMessage)
                persistAndPublish()
            } else {
                persistCompletedCallIds()
            }
        }
    }

    fun copyableTrace(): String = synchronized(lock) {
        val visible = messages.toList().takeLast(MAX_ENTRIES).asReversed()
        visible.joinToString("\n\n") { entry ->
            val direction = when (entry.direction) {
                Direction.SENT -> "SENT FROM THIS PHONE"
                Direction.RECEIVED -> "RECEIVED ON THIS PHONE"
                Direction.UNKNOWN -> "DIRECTION UNKNOWN"
            }
            "$direction · ${entry.label}\n${entry.details}"
        }
    }

    private fun isSipDetailLine(line: String): Boolean {
        val value = line.trim()
        if (value.isEmpty()) return false
        return SIP_DETAIL_PREFIXES.any { value.startsWith(it, ignoreCase = true) }
    }

    private fun sanitize(line: String): String {
        return line.replace(sensitiveAuthRegex) { "${it.groupValues[1]}: [hidden]" }
            .replace(pushParamRegex, "")
    }

    private fun finishCurrentMessage() {
        val builder = currentMessage ?: return
        currentMessage = null
        val entry = buildEntry(builder)
        if (entry != null) {
            messages.addLast(entry)
            trimEntries()
            persistAndPublish()
        }
    }

    private fun buildEntry(builder: MessageBuilder): Entry? {
        val fullMessage = (listOf(builder.startLine) + builder.lines).joinToString("\n").trim()
        val status = statusRegex.matchEntire(builder.startLine)
        val cseqValue = cseqRegex.find(fullMessage)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val method = if (status == null) {
            builder.startLine.substringBefore(' ').uppercase(Locale.ROOT)
        } else {
            cseqValue.substringAfter(' ', "").uppercase(Locale.ROOT)
        }
        if (method !in callMethods) return null

        val callId = callIdRegex.find(fullMessage)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val from = fromRegex.find(fullMessage)?.groupValues?.getOrNull(1).orEmpty()
        val to = toRegex.find(fullMessage)?.groupValues?.getOrNull(1).orEmpty()
        val fromUser = sipUsernameRegex.find(from)?.groupValues?.getOrNull(1).orEmpty()
        val toUser = sipUsernameRegex.find(to)?.groupValues?.getOrNull(1).orEmpty()
        val localUser = localUsername

        val direction = if (status == null) {
            when {
                localUser.isNotBlank() && fromUser.equals(localUser, ignoreCase = true) -> Direction.SENT
                localUser.isNotBlank() && toUser.equals(localUser, ignoreCase = true) -> Direction.RECEIVED
                else -> Direction.UNKNOWN
            }.also { requestDirectionByCall[callId to method] = it }
        } else {
            when {
                localUser.isNotBlank() && fromUser.equals(localUser, ignoreCase = true) -> Direction.RECEIVED
                localUser.isNotBlank() && toUser.equals(localUser, ignoreCase = true) -> Direction.SENT
                else -> requestDirectionByCall[callId to method]?.opposite() ?: Direction.UNKNOWN
            }
        }

        return Entry(
            timestamp = builder.timestamp,
            direction = direction,
            startLine = builder.startLine,
            callId = callId,
            cseq = cseqValue,
            details = fullMessage
        )
    }

    private fun Direction.opposite(): Direction = when (this) {
        Direction.SENT -> Direction.RECEIVED
        Direction.RECEIVED -> Direction.SENT
        Direction.UNKNOWN -> Direction.UNKNOWN
    }

    private fun trimEntries() {
        while (messages.size > MAX_ENTRIES) messages.removeFirst()
    }

    private fun persistAndPublish() {
        val snapshot = messages.toList()
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(
                PREFS_ENTRIES,
                JSONArray().apply {
                snapshot.forEach { entry ->
                    put(
                        JSONObject().apply {
                        put("time", entry.timestamp)
                        put("direction", entry.direction.name)
                        put("start", entry.startLine)
                        put("callId", entry.callId)
                        put("cseq", entry.cseq)
                        put("details", entry.details)
                    }
                    )
                }
            }.toString()
            )
            ?.apply()
        persistCompletedCallIds()
        entries.postValue(snapshot.asReversed())
    }

    private fun persistCompletedCallIds() {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(PREFS_COMPLETED_CALL_IDS, JSONArray(completedCallIds.toList()).toString())
            ?.apply()
    }

    private data class MessageBuilder(val timestamp: Long, val startLine: String) {
        val lines = mutableListOf<String>()
    }

    private val SIP_DETAIL_PREFIXES = listOf(
        "Via:", "From:", "To:", "Call-ID:", "CSeq:", "Contact:", "Content-Type:",
        "Content-Length:", "Max-Forwards:", "User-Agent:", "Allow:", "Supported:",
        "Require:", "Route:", "Record-Route:", "Subject:", "Expires:", "P-", "X-",
        "v=", "o=", "s=", "c=", "t=", "m=", "a=", "b=", "i=", "u=", "e=", "p=", "r=", "z=", "k="
    )
}
