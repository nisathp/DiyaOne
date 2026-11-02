package com.naminfo.core

import java.util.ArrayDeque

/** Bridges native Linphone file-transfer diagnostics into the API Logs screen. */
object FileTransferApiLogger {
    private const val REQUEST_DESCRIPTION = "multipart/form-data; native SIP file transfer"
    private val httpError = Regex("Unhandled HTTP code response\\s+(\\d{3})\\s+for file transfer")
    private val lock = Any()

    private data class PendingUpload(
        val id: Long,
        var responseBody: String? = null
    )

    private val pendingUploads = ArrayDeque<PendingUpload>()

    fun beginUpload(url: String, fileName: String, fileSize: Long): Long {
        val request = "$REQUEST_DESCRIPTION; file=$fileName; size=$fileSize bytes"
        val id = ApiLogRepository.begin("POST", url, request)
        synchronized(lock) {
            pendingUploads.addLast(PendingUpload(id))
        }
        return id
    }

    /** The SDK owns this HTTP request; its native log stream is the source of the response. */
    fun onNativeLog(message: String) {
        val line = message.trim()
        if (line.isEmpty()) return

        var result: Triple<Long, Int, String?>? = null
        synchronized(lock) {
            val current = pendingUploads.peekFirst() ?: return

            // hft.php returns JSON such as {"error":"Forbidden"}; preserve it for the user.
            if (line.startsWith("{") && line.endsWith("}") && line.length <= 12_000) {
                current.responseBody = line
            }

            val status = httpError.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (status != null) {
                pendingUploads.removeFirst()
                result = Triple(
                    current.id,
                    status,
                    current.responseBody ?: "Native file transfer response: $line"
                )
            }
        }
        result?.let { (id, status, body) -> ApiLogRepository.complete(id, status, body) }
    }

    fun completeUpload(id: Long) {
        if (remove(id)) ApiLogRepository.complete(id, 200, "File transfer completed")
    }

    fun failUpload(id: Long, reason: String) {
        if (remove(id)) ApiLogRepository.fail(id, IllegalStateException(reason))
    }

    private fun remove(id: Long): Boolean = synchronized(lock) {
        val iterator = pendingUploads.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().id == id) {
                iterator.remove()
                return@synchronized true
            }
        }
        false
    }
}
