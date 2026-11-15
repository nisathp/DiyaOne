package com.naminfo.core

import android.util.Log
import java.util.ArrayDeque

/** Bridges native Linphone file-transfer diagnostics into the API Logs screen. */
object FileTransferApiLogger {
    private const val TAG = "HFT-Response"
    private const val REQUEST_DESCRIPTION = "multipart/form-data; native SIP file transfer"
    private val httpError = Regex("Unhandled HTTP code response\\s+(\\d{3})\\s+for file transfer")
    private val xmlFileName = Regex("<file-name>([^<]+)</file-name>")
    private val xmlFileSize = Regex("<file-size>(\\d+)</file-size>")
    private val xmlContentType = Regex("<content-type>([^<]+)</content-type>")
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

        // The SDK performs the HTTP transfer in native code. Mirror only safe response
        // metadata into Android Logcat so the normal app filter can diagnose media
        // uploads without disclosing the signed, tokenized download URL.
        if (line.startsWith("HTTP/1.1 200")) {
            Log.i(TAG, "HFT upload response status=200")
        }
        if (line.contains("<file") && line.contains("rcs:fthttp")) {
            val fileName = xmlFileName.find(line)?.groupValues?.getOrNull(1) ?: "unknown"
            val fileSize = xmlFileSize.find(line)?.groupValues?.getOrNull(1) ?: "unknown"
            val contentType = xmlContentType.find(line)?.groupValues?.getOrNull(1) ?: "unknown"
            val hasDownloadUrl = line.contains("<data ") && line.contains("url=")
            Log.i(
                TAG,
                "HFT response XML received: file=$fileName size=$fileSize type=$contentType downloadUrl=$hasDownloadUrl"
            )
        }
        if (line.contains("unable to parse file name or URL", ignoreCase = true)) {
            Log.e(TAG, "HFT response XML was rejected by the SDK; check <data> is inside <file-info>")
        }

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
