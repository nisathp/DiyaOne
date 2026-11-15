package com.naminfo.core

import com.naminfo.DiyaOneApplication.Companion.corePreferences
import org.json.JSONObject
import org.linphone.core.tools.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Refreshes the DiyaOne API access token without exposing credentials to UI code. */
object MobionTokenRefresher {
    private const val TAG = "[Mobion Token Refresher]"
    private const val EXPIRY_SAFETY_WINDOW_MILLIS = 60_000L

    /**
     * Returns an access token that is not about to expire. This is intentionally blocking
     * because every existing DiyaOne HTTP call is already made off the main thread.
     */
    @Synchronized
    fun validAccessToken(apiBase: String, endSessionOnRefreshFailure: Boolean = true): String? {
        val token = corePreferences.authToken ?: return null
        if (!isExpiringSoon(corePreferences.accessTokenExpiresAt)) return token

        Log.i("$TAG Access token is expired or near expiry, refreshing it")
        return refreshLocked(apiBase, endSessionOnRefreshFailure)
    }

    /** Refreshes after an authenticated endpoint returns HTTP 401, then permits one retry. */
    @Synchronized
    fun refreshAfterUnauthorized(
        apiBase: String,
        endSessionOnRefreshFailure: Boolean = true
    ): String? = refreshLocked(apiBase, endSessionOnRefreshFailure)

    private fun refreshLocked(apiBase: String, endSessionOnRefreshFailure: Boolean): String? {
        val refreshToken = corePreferences.refreshToken
        if (refreshToken.isNullOrBlank()) {
            Log.w("$TAG Can't refresh access token: refresh token is missing")
            return null
        }

        if (isExpired(corePreferences.refreshTokenExpiresAt)) {
            Log.w("$TAG Refresh token is already expired")
            if (endSessionOnRefreshFailure) {
                MobionSessionExpiryHandler.expireAfterRefreshUnauthorized()
            }
            return null
        }

        val endpoint = "${apiBase.trim().trimEnd('/')}/auth/refresh"
        var logId: Long? = null
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")

            val body = JSONObject().put("refreshToken", refreshToken).toString()
            logId = ApiLogRepository.begin("POST", endpoint, body)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }

            val responseCode = connection.responseCode
            val response = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ApiLogRepository.complete(requireNotNull(logId), responseCode, response)
            if (responseCode !in 200..299) {
                Log.w("$TAG Refresh API returned HTTP $responseCode")
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && endSessionOnRefreshFailure) {
                    MobionSessionExpiryHandler.expireAfterRefreshUnauthorized()
                }
                return null
            }

            val json = JSONObject(response.orEmpty())
            val accessToken = json.optString("accessToken").ifBlank { json.optString("token") }
            if (accessToken.isBlank()) {
                Log.w("$TAG Refresh API response does not contain an access token")
                return null
            }

            corePreferences.authToken = accessToken
            json.optString("refreshToken").takeIf { it.isNotBlank() }?.let {
                corePreferences.refreshToken = it
            }
            corePreferences.accessTokenExpiresAt = json.optString("accessTokenExpiresAt")
                .takeIf { it.isNotBlank() }
            json.optString("refreshTokenExpiresAt").takeIf { it.isNotBlank() }?.let {
                corePreferences.refreshTokenExpiresAt = it
            }
            MobionSessionExpiryHandler.resetForNewSession()
            Log.i("$TAG Access token refreshed successfully")
            accessToken
        } catch (error: Exception) {
            ApiLogRepository.fail(logId ?: ApiLogRepository.begin("POST", endpoint), error)
            Log.w("$TAG Refresh API failed: ${error.javaClass.simpleName}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun isExpiringSoon(rawExpiry: String?): Boolean {
        if (rawExpiry.isNullOrBlank()) return false
        val expiry = runCatching { Instant.parse(rawExpiry) }.getOrNull() ?: return false
        return expiry.toEpochMilli() - System.currentTimeMillis() <= EXPIRY_SAFETY_WINDOW_MILLIS
    }

    private fun isExpired(rawExpiry: String?): Boolean {
        if (rawExpiry.isNullOrBlank()) return false
        val expiry = runCatching { Instant.parse(rawExpiry) }.getOrNull() ?: return false
        return expiry.toEpochMilli() <= System.currentTimeMillis()
    }
}
