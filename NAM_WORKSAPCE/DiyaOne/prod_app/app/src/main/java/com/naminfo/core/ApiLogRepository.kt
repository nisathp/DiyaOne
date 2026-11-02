package com.naminfo.core

import androidx.lifecycle.MutableLiveData
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** A bounded, on-device diagnostic history of application HTTP calls. */
object ApiLogRepository {
    data class Entry(
        val id: Long,
        val timestamp: Long,
        val method: String,
        val url: String,
        val requestBody: String?,
        val statusCode: Int? = null,
        val responseBody: String? = null,
        val error: String? = null
    )

    val entries = MutableLiveData<List<Entry>>(emptyList())

    private const val MAX_ENTRIES = 200
    private const val MAX_BODY_LENGTH = 12_000
    private val nextId = AtomicLong()
    private val lock = Any()
    private val history = ArrayDeque<Entry>()

    fun begin(method: String, url: String, requestBody: String? = null): Long {
        val id = nextId.incrementAndGet()
        synchronized(lock) {
            history.addFirst(
                Entry(id, System.currentTimeMillis(), method.uppercase(), url, sanitize(requestBody))
            )
            trimAndPublish()
        }
        return id
    }

    fun complete(id: Long, statusCode: Int, responseBody: String? = null) {
        update(id) { it.copy(statusCode = statusCode, responseBody = sanitize(responseBody), error = null) }
    }

    fun fail(id: Long, error: Throwable) {
        update(id) { it.copy(error = error.javaClass.simpleName + ": " + error.message.orEmpty()) }
    }

    fun clear() = synchronized(lock) {
        history.clear()
        entries.postValue(emptyList())
    }

    fun copyableText(): String = synchronized(lock) {
        history.joinToString("\n\n") { entry ->
            buildString {
                append("${entry.method} ${entry.url}")
                entry.requestBody?.takeIf { it.isNotBlank() }?.let { append("\nRequest: $it") }
                entry.statusCode?.let { append("\nResponse HTTP: $it") }
                entry.responseBody?.takeIf { it.isNotBlank() }?.let { append("\nResponse: $it") }
                entry.error?.takeIf { it.isNotBlank() }?.let { append("\nError: $it") }
            }
        }
    }

    private fun update(id: Long, change: (Entry) -> Entry) = synchronized(lock) {
        val index = history.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        val updated = history.elementAt(index).let(change)
        val snapshot = history.toMutableList()
        snapshot[index] = updated
        history.clear()
        history.addAll(snapshot)
        entries.postValue(history.toList())
    }

    private fun trimAndPublish() {
        while (history.size > MAX_ENTRIES) history.removeLast()
        entries.postValue(history.toList())
    }

    private fun sanitize(value: String?): String? {
        if (value.isNullOrBlank()) return value
        return value.take(MAX_BODY_LENGTH)
            .replace(Regex("(?i)(\\\"(?:accessToken|refreshToken|password|api[_-]?key|authorization|fcmToken|readyToken)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")"), "\$1[hidden]\$2")
            .replace(Regex("(?i)((?:password|api[_-]?key|token|authorization)=)[^&\\s]+"), "\$1[hidden]")
    }
}
