package com.naminfo.core

import com.naminfo.DiyaOneApplication.Companion.corePreferences
import org.json.JSONObject
import org.linphone.core.tools.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Presence is kept server-side: the app never writes to Realtime Database directly.
 * That keeps the database rules locked down while allowing SIP registration state to
 * be used as an extra signal when the user enables the setting.
 */
object FirebasePresenceRepository {
    private const val TAG = "[Firebase Presence]"
    private const val ENDPOINT = "https://androidpushnotification.vercel.app/api/presence"
    private const val SIGNATURE = "015a3e11e51129f1e74326b993b508103506093f884b4f66e94404d334473820"
    private const val CACHE_TTL_MS = 15_000L

    private data class CachedPresence(val online: Boolean, val fetchedAt: Long)

    private val cache = ConcurrentHashMap<String, CachedPresence>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun syncRegistration(phoneNumber: String, online: Boolean) {
        val normalized = normalize(phoneNumber) ?: return
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-signature", SIGNATURE)
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    doOutput = true
                }
                connection.outputStream.bufferedWriter().use {
                    it.write(JSONObject().put("phoneNumber", normalized).put("online", online).toString())
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    cache[normalized] = CachedPresence(online, System.currentTimeMillis())
                } else {
                    Log.w("$TAG Presence sync for [$normalized] failed with HTTP $code")
                }
            } catch (exception: Exception) {
                Log.w("$TAG Presence sync for [$normalized] failed: ${exception.message}")
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    fun fetchIfEnabled(phoneNumber: String?, onResult: (Boolean) -> Unit) {
        if (!corePreferences.firebasePresenceEnabled) return
        val normalized = normalize(phoneNumber) ?: return
        val now = System.currentTimeMillis()
        cache[normalized]?.takeIf { now - it.fetchedAt <= CACHE_TTL_MS }?.let {
            onResult(it.online)
            return
        }
        if (!inFlight.add(normalized)) return

        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$ENDPOINT?phoneNumber=$normalized").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("x-signature", SIGNATURE)
                    connectTimeout = 3_000
                    readTimeout = 3_000
                }
                if (connection.responseCode !in 200..299) return@Thread
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val online = JSONObject(body).optBoolean("online", false)
                cache[normalized] = CachedPresence(online, System.currentTimeMillis())
                onResult(online)
            } catch (exception: Exception) {
                Log.w("$TAG Presence fetch for [$normalized] failed: ${exception.message}")
            } finally {
                inFlight.remove(normalized)
                connection?.disconnect()
            }
        }.start()
    }

    fun clearCache() {
        cache.clear()
    }

    private fun normalize(value: String?): String? {
        val digits = value.orEmpty().filter(Char::isDigit)
        return digits.takeIf { it.length in 6..20 }
    }
}
