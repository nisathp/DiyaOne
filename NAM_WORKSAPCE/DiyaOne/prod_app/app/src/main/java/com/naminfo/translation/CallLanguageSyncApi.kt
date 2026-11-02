package com.naminfo.translation

import android.net.Uri
import com.naminfo.core.ApiLogRepository
import org.json.JSONObject
import org.linphone.core.tools.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reports only the SIP Call-ID, party role and selected language to the
 * translation controller.  FreeSWITCH UUIDs, phone numbers and SIP
 * credentials deliberately never leave the Android client here.
 */
object CallLanguageSyncApi {
    private const val TAG = "[Call Language Sync]"
    // Translation-controller service reachable from physical Android devices.
    private const val BASE_URL = "https://gomobion.in"
    private const val API_KEY = "KirHbNtdUraLJHFq694krx65TD83zVJVNgIhCe367So"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_ATTEMPTS = 3

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CallLanguageSync").apply { isDaemon = true }
    }
    private val delivered = Collections.synchronizedSet(mutableSetOf<String>())

    fun update(callId: String?, party: String, language: String?) {
        val sipCallId = callId?.trim().orEmpty()
        val lang = language?.trim().orEmpty()
        if (sipCallId.isEmpty() || lang.isEmpty()) {
            Log.w("$TAG Skipping language update: SIP Call-ID or language is empty")
            return
        }
        if (party != CALLER && party != ATTENDER) {
            Log.w("$TAG Skipping language update: unexpected party [$party]")
            return
        }

        // State updates are idempotent. Repeated Linphone state callbacks must
        // not create duplicate controller requests for the same selection.
        val requestKey = "$sipCallId|$party|$lang"
        if (!delivered.add(requestKey)) return

        worker.execute {
            if (!postUpdate(sipCallId, party, lang)) {
                // Allow a later call-state callback or manual re-selection to retry.
                delivered.remove(requestKey)
            }
        }
    }

    fun clear(callId: String?) {
        val sipCallId = callId?.trim().orEmpty()
        if (sipCallId.isEmpty()) return
        synchronized(delivered) {
            delivered.removeAll { it.startsWith("$sipCallId|") }
        }
    }

    private fun postUpdate(callId: String, party: String, language: String): Boolean {
        val endpoint = "$BASE_URL/v1/calls/${Uri.encode(callId)}/language"
        val body = JSONObject()
            .put("party", party)
            .put("lang", language)
            .toString()

        repeat(MAX_ATTEMPTS) { index ->
            var connection: HttpURLConnection? = null
            val logId = ApiLogRepository.begin("POST", endpoint, body)
            try {
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("X-API-Key", API_KEY)
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }

                val status = connection.responseCode
                val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
                ApiLogRepository.complete(logId, status, response)
                if (status in 200..299) {
                    Log.i("$TAG Language updated: party=[$party], lang=[$language], Call-ID=[$callId]")
                    return true
                }
                Log.w("$TAG Controller rejected language update with HTTP [$status], attempt [${index + 1}/$MAX_ATTEMPTS]")
            } catch (exception: Exception) {
                ApiLogRepository.fail(logId, exception)
                Log.w("$TAG Language update failed, attempt [${index + 1}/$MAX_ATTEMPTS]: ${exception.message}")
            } finally {
                connection?.disconnect()
            }

            if (index < MAX_ATTEMPTS - 1) TimeUnit.MILLISECONDS.sleep(500L * (index + 1))
        }
        return false
    }

    private const val CALLER = "caller"
    private const val ATTENDER = "attender"
}
