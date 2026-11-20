package com.naminfo.translation

import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.core.ApiLogRepository
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.linphone.core.tools.Log
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors as JavaExecutors

object TranslationRepository {
    private const val TAG = "[Translation Repository]"
    private const val DEFAULT_TCHAT_DOMAIN = "https://mobionglobal.info:8020/translate"
    private const val API_KEY = "qhyvM1rr4RE2vqnvAAdfoDDC9Wlbylv-XXiUYF0HaZE"

    private val inFlightRequests = ConcurrentHashMap.newKeySet<String>()
    private val memoryCache = ConcurrentHashMap<String, String>()
    private val translationDispatcher = JavaExecutors.newFixedThreadPool(4).asCoroutineDispatcher()

    fun getCacheKey(messageId: String, targetLanguage: ChatTranslationLanguage): String {
        return "${messageId}_${targetLanguage.name}"
    }

    fun getCachedTranslation(messageId: String, targetLanguage: ChatTranslationLanguage): String? {
        if (targetLanguage == ChatTranslationLanguage.NONE) return null
        val cacheKey = getCacheKey(messageId, targetLanguage)
        memoryCache[cacheKey]?.let { return it }
        val persisted = corePreferences.getPersistedTranslation(cacheKey)
        if (persisted != null) {
            memoryCache[cacheKey] = persisted
            return persisted
        }
        return null
    }

    suspend fun translateMessage(
        messageId: String,
        toNumber: String,
        originalText: String,
        targetLanguage: ChatTranslationLanguage
    ): Result<String> = withContext(translationDispatcher) {
        if (targetLanguage == ChatTranslationLanguage.NONE || originalText.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid target language or empty text"))
        }

        val cached = getCachedTranslation(messageId, targetLanguage)
        if (cached != null) {
            Log.i("$TAG Returning cached translation for messageId [$messageId], lang [${targetLanguage.name}]")
            return@withContext Result.success(cached)
        }

        val cacheKey = getCacheKey(messageId, targetLanguage)
        if (!inFlightRequests.add(cacheKey)) {
            Log.i("$TAG Translation already in flight for messageId [$messageId], waiting...")
            var attempts = 0
            while (inFlightRequests.contains(cacheKey) && attempts < 30) {
                delay(100)
                attempts++
            }
            val cachedAfterWait = getCachedTranslation(messageId, targetLanguage)
            if (cachedAfterWait != null) {
                return@withContext Result.success(cachedAfterWait)
            } else {
                return@withContext Result.failure(IllegalStateException("In-flight translation failed or timed out"))
            }
        }

        try {
            var lastFailure: Throwable? = null
            repeat(3) { attempt ->
                val result = requestTranslation(cacheKey, messageId, toNumber, originalText, targetLanguage)
                if (result.isSuccess) return@withContext result

                lastFailure = result.exceptionOrNull()
                // A reset/timeout is transient on the translator's HTTP endpoint. Retrying only
                // transport and 5xx failures avoids retrying invalid credentials or malformed data.
                val retryable = lastFailure is IOException ||
                    (lastFailure as? TranslationHttpException)?.statusCode?.let { it in 500..599 } == true
                if (!retryable || attempt == 2) return@withContext result
                Log.w("$TAG Translation attempt ${attempt + 1} failed for [$messageId], retrying", lastFailure)
                delay(350L * (attempt + 1))
            }
            Result.failure(lastFailure ?: IllegalStateException("Translation failed"))
        } finally {
            inFlightRequests.remove(cacheKey)
        }
    }

    private fun requestTranslation(
        cacheKey: String,
        messageId: String,
        toNumber: String,
        originalText: String,
        targetLanguage: ChatTranslationLanguage
    ): Result<String> {
        var logId: Long? = null
        var connection: HttpURLConnection? = null
        return try {
            // The translation API accepts the human-readable language name (for example "Tamil").
            val languageParam = targetLanguage.label
            Log.i("$TAG Calling Translation API for messageId [$messageId], targetLang [$languageParam]")
            val requestJson = JSONObject().apply {
                put("to_number", toNumber)
                put("language", languageParam)
                put("message", originalText)
            }
            val apiUrl = translationApiUrl()
            logId = ApiLogRepository.begin("POST", apiUrl, requestJson.toString())

            val url = URL(apiUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("X-API-Key", API_KEY)
                setRequestProperty("accept", "application/json, */*")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
            }

            connection!!.outputStream.use { os ->
                val input = requestJson.toString().toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection!!.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection!!.inputStream.bufferedReader().use(BufferedReader::readText)
                ApiLogRepository.complete(requireNotNull(logId), responseCode, responseText)
                val responseJson = JSONObject(responseText)
                val translatedMessage = if (responseJson.has("translated_message") && !responseJson.isNull("translated_message")) {
                    responseJson.getString("translated_message")
                } else {
                    null
                }

                if (!translatedMessage.isNullOrEmpty()) {
                    Log.i("$TAG Translation successful for messageId [$messageId]")
                    memoryCache[cacheKey] = translatedMessage
                    corePreferences.setPersistedTranslation(cacheKey, translatedMessage)
                    return Result.success(translatedMessage)
                } else {
                    Log.e("$TAG 'translated_message' field missing in API response: $responseText")
                    return Result.failure(IllegalStateException("Empty translated_message in response"))
                }
            } else {
                val errorStream = connection!!.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                ApiLogRepository.complete(requireNotNull(logId), responseCode, errorStream)
                val friendlyError = when (responseCode) {
                    401 -> "HTTP 401 Unauthorized: Invalid or missing X-API-Key"
                    502 -> "HTTP 502 Bad Gateway: Translation service failed"
                    else -> "HTTP $responseCode: $errorStream"
                }
                Log.e("$TAG API HTTP Error [$responseCode]: $friendlyError")
                return Result.failure(TranslationHttpException(responseCode, friendlyError))
            }
        } catch (e: Exception) {
            // A request may fail before a connection response is available.
            ApiLogRepository.fail(logId ?: ApiLogRepository.begin("POST", translationApiUrl()), e)
            Log.e("$TAG Exception during translation: $e")
            return Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    private class TranslationHttpException(val statusCode: Int, message: String) : IOException(message)

    private fun translationApiUrl(): String {
        val configured = corePreferences.tChatDomain.trim()
        if (configured.isBlank()) return DEFAULT_TCHAT_DOMAIN

        val host = configured.removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')
        // A bare IPv4 T Chat endpoint is HTTP. Treating it as HTTPS produces an Android TLS
        // handshake failure even when the translator is responding normally.
        val defaultScheme = if (host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))) "http" else "https"
        val base = if (configured.startsWith("http://", ignoreCase = true) ||
            configured.startsWith("https://", ignoreCase = true)
        ) {
            configured
        } else {
            "$defaultScheme://$configured"
        }
        val normalized = base.trimEnd('/')
        return if (normalized.endsWith("/translate", ignoreCase = true)) normalized else "$normalized/translate"
    }
}
