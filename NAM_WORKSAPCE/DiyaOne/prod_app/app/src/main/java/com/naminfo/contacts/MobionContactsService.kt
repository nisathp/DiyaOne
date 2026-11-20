package com.naminfo.contacts

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.core.ApiLogRepository
import com.naminfo.core.MobionTokenRefresher
import org.linphone.core.tools.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

object MobionContactsService {
    private const val TAG = "[Mobion Contacts Service]"

    @Volatile
    private var contactsCache: List<Contact>? = null

    private val contactsRefreshVersion = AtomicLong(0L)
    private val avatarCacheEpoch = AtomicLong(0L)

    fun currentAvatarCacheEpoch(): Long = avatarCacheEpoch.get()

    fun bumpAvatarCacheEpoch(): Long = avatarCacheEpoch.incrementAndGet()

    // The backend can briefly return the old URL immediately after DELETE. Keep the local
    // removal authoritative until `/users` confirms that this number has no picture anymore.
    private val locallyRemovedProfileNumbers = ConcurrentHashMap.newKeySet<String>()

    /**
     * Published after a profile photo is changed. Active contact screens use this to reload the
     * single `/users` profile map immediately instead of waiting for a manual pull-to-refresh.
     */
    val contactsRefreshRequested = MutableLiveData<Long>()

    private const val CONFERENCES_URL =
        "https://mobionglobal.com/fs_webservice/WebService.asmx/GroupSetting_View"

    data class Contact(
        val name: String,
        val mobileNumber: String,
        val profilePictureUrl: String? = null
    )

    data class Conference(
        val id: String,
        val groupNumber: String,
        val groupName: String,
        val type: String
    )

    @Synchronized
    fun fetchContacts(forceRefresh: Boolean = false): List<Contact> {
        contactsCache?.takeIf { !forceRefresh }?.let { return it }

        val contacts = try {
            fetchUsersContacts()
        } catch (error: Exception) {
            Log.w("$TAG Failed to fetch /users contacts: ${error.javaClass.simpleName}")
            // A pull-to-refresh must never replace a usable directory with an empty list
            // when the request times out or authentication cannot be refreshed.
            return contactsCache ?: readPersistedContacts()
        }
        if (contacts.isEmpty()) return contactsCache ?: readPersistedContacts()

        val resolvedContacts = contacts.map { contact ->
            // Keep an immediately deleted picture hidden while the backend deletion finishes
            // propagating. A later /users response with null confirms it and clears the guard.
            if (contact.mobileNumber in locallyRemovedProfileNumbers) {
                if (contact.profilePictureUrl == null) {
                    locallyRemovedProfileNumbers.remove(contact.mobileNumber)
                }
                contact.copy(profilePictureUrl = null)
            } else {
                contact
            }
        }

        contactsCache = resolvedContacts
        persistContacts(resolvedContacts)
        return resolvedContacts
    }

    /**
     * Returns the directory name already available on the device without starting a network
     * request. This is used for the signed-in account header as well: a handset does not need
     * to have its own number saved in the native contacts database to display its profile name.
     */
    fun findCachedContactName(subscriberId: String): String? {
        val normalized = PhoneContactNumbers.normalize(subscriberId)
        if (normalized.isBlank()) return null
        val contact = (contactsCache ?: readPersistedContacts())
            .firstOrNull { PhoneContactNumbers.normalize(it.mobileNumber) == normalized }
            ?: return null
        return contact.name.trim().takeIf {
            it.isNotBlank() && !PhoneContactNumbers.isNumericPlaceholder(it, normalized)
        }
    }

    /** Clears only memory by default; sign-out also removes the persisted directory. */
    fun clearContactsCache(clearPersisted: Boolean = false) {
        contactsCache = null
        locallyRemovedProfileNumbers.clear()
        if (clearPersisted) {
            corePreferences.mobionContactsCache = null
            Log.i("$TAG Cleared persisted Mobion contacts for sign-out")
        }
    }

    fun refreshContactsAfterProfilePictureChange() {
        clearContactsCache()
        avatarCacheEpoch.incrementAndGet()
        contactsRefreshRequested.postValue(contactsRefreshVersion.incrementAndGet())
    }

    fun markProfilePictureUploaded(subscriberId: String) {
        val number = PhoneContactNumbers.normalize(subscriberId)
        if (number.length == 10) locallyRemovedProfileNumbers.remove(number)
        refreshContactsAfterProfilePictureChange()
    }

    /** Removes an obsolete profile URL immediately after the authenticated delete succeeds. */
    fun clearCachedProfilePicture(subscriberId: String) {
        val number = PhoneContactNumbers.normalize(subscriberId)
        if (number.length != 10) return

        locallyRemovedProfileNumbers.add(number)

        val cachedContacts = contactsCache ?: readPersistedContacts()
        if (cachedContacts.isEmpty()) return

        val updatedContacts = cachedContacts.map { contact ->
            if (contact.mobileNumber == number) contact.copy(profilePictureUrl = null) else contact
        }
        contactsCache = updatedContacts
        persistContacts(updatedContacts)
    }

    private fun persistContacts(contacts: List<Contact>) {
        corePreferences.mobionContactsCache = JSONArray().apply {
            contacts.forEach { contact ->
                put(
                    JSONObject()
                        .put("name", contact.name)
                        .put("mobileNumber", contact.mobileNumber)
                        .put("profilePictureUrl", contact.profilePictureUrl)
                )
            }
        }.toString()
    }

    private fun readPersistedContacts(): List<Contact> {
        val raw = corePreferences.mobionContactsCache.orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val values = JSONArray(raw)
            buildList {
                for (index in 0 until values.length()) {
                    val item = values.getJSONObject(index)
                    val mobileNumber = PhoneContactNumbers.normalize(item.optString("mobileNumber"))
                    if (mobileNumber.length == 10) {
                        add(
                            Contact(
                                name = item.optString("name"),
                                mobileNumber = mobileNumber,
                                profilePictureUrl = item.optString("profilePictureUrl")
                                    .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                            )
                        )
                    }
                }
            }
        }.onFailure {
            Log.w("$TAG Ignoring invalid persisted contacts cache")
        }.getOrDefault(emptyList())
    }

    /**
     * Retrieves the complete Diya directory from the JSON endpoint. A device's contact UI
     * subsequently filters this list against its local SIM/phone contacts by mobile number.
     */
    private fun fetchUsersContacts(allowTokenRefreshRetry: Boolean = true): List<Contact> {
        val apiBase = corePreferences.mobionApiBaseUrl
        val urlStr = "${apiBase.trimEnd('/')}/users"
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        val token = MobionTokenRefresher.validAccessToken(apiBase)
        if (!token.isNullOrEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        val logId = ApiLogRepository.begin("GET", urlStr)

        return try {
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val responseJson = reader.readText()
                    ApiLogRepository.complete(logId, responseCode, responseJson)
                    Log.i("$TAG /users response received")

                    val jsonArray = JSONArray(responseJson)
                    val contacts = linkedMapOf<String, Contact>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val mobileNo = PhoneContactNumbers.normalize(
                            obj.optString("mobileNo").ifEmpty { obj.optString("subscriberId") }
                        )
                        if (mobileNo.length != 10) continue

                        val incoming = Contact(
                            name = obj.optString("name").trim().ifBlank { mobileNo },
                            mobileNumber = mobileNo,
                            profilePictureUrl = normalizeProfilePictureUrl(
                                obj.optString("profilePictureUrl"),
                                apiBase
                            )
                        )
                        val existing = contacts[mobileNo]
                        contacts[mobileNo] = when {
                            existing == null -> incoming
                            existing.profilePictureUrl == null && incoming.profilePictureUrl != null -> incoming
                            else -> existing
                        }
                    }
                    Log.i("$TAG Parsed [${contacts.size}] contacts from /users JSON")
                    return contacts.values.toList()
                }
            }
            val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
            ApiLogRepository.complete(logId, responseCode, errorResponse)
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && allowTokenRefreshRetry) {
                connection.disconnect()
                if (MobionTokenRefresher.refreshAfterUnauthorized(apiBase) != null) {
                    return fetchUsersContacts(allowTokenRefreshRetry = false)
                }
            }
            error("Mobion users request failed with HTTP $responseCode")
        } catch (e: Exception) {
            ApiLogRepository.fail(logId, e)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeProfilePictureUrl(rawUrl: String, apiBase: String): String? {
        val profilePictureUrl = rawUrl.trim()
        if (profilePictureUrl.isEmpty() || profilePictureUrl.equals("null", ignoreCase = true)) {
            return null
        }

        val resolvedUrl = if (
            profilePictureUrl.startsWith("http://", ignoreCase = true) ||
            profilePictureUrl.startsWith("https://", ignoreCase = true)
        ) {
            profilePictureUrl
        } else {
            "${apiBase.trimEnd('/')}/${profilePictureUrl.trimStart('/')}"
        }

        return resolvedUrl
    }

    fun fetchProfilePictureUrl(subscriberId: String, allowTokenRefreshRetry: Boolean = true): String? {
        val apiBase = corePreferences.mobionApiBaseUrl
        val accessToken = MobionTokenRefresher.validAccessToken(apiBase)
        val encodedSubscriberId = URLEncoder.encode(subscriberId, Charsets.UTF_8.name())
        val connection = URL("${apiBase.trimEnd('/')}/users/$encodedSubscriberId")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Accept", "application/json")
        if (!accessToken.isNullOrEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
        }
        val logId = ApiLogRepository.begin("GET", "${apiBase.trimEnd('/')}/users/$encodedSubscriberId")

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                ApiLogRepository.complete(logId, responseCode, connection.errorStream?.bufferedReader()?.use { it.readText() })
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && allowTokenRefreshRetry) {
                    connection.disconnect()
                    if (MobionTokenRefresher.refreshAfterUnauthorized(apiBase) != null) {
                        return fetchProfilePictureUrl(subscriberId, allowTokenRefreshRetry = false)
                    }
                }
                return null
            }
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ApiLogRepository.complete(logId, responseCode, responseText)
            val profileJson = JSONObject(responseText)
            normalizeProfilePictureUrl(profileJson.optString("profilePictureUrl"), apiBase)
        } catch (e: Exception) {
            ApiLogRepository.fail(logId, e)
            Log.w("$TAG Profile picture lookup failed for [$subscriberId]: ${e.javaClass.simpleName}")
            null
        } finally {
            connection.disconnect()
        }
    }

    fun fetchConferences(registeredNumber: String): List<Conference> {
        val encodedNumber = URLEncoder.encode(registeredNumber, Charsets.UTF_8.name())
        val connection = URL("$CONFERENCES_URL?Number=$encodedNumber")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "text/xml")
        val logId = ApiLogRepository.begin("GET", "$CONFERENCES_URL?Number=$encodedNumber")

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                ApiLogRepository.complete(logId, responseCode, error)
                error("Mobion conferences request failed with HTTP $responseCode")
            }

            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val responseXml = reader.readText()
                ApiLogRepository.complete(logId, responseCode, responseXml)
                Log.i("$TAG GroupSetting_View response:\n$responseXml")
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(StringReader(responseXml))
                parseConferences(parser).also { conferences ->
                    Log.i("$TAG Parsed [${conferences.size}] conferences for [$registeredNumber]")
                }
            }
        } catch (error: Exception) {
            ApiLogRepository.fail(logId, error)
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun parseConferences(parser: XmlPullParser): List<Conference> {
        val conferences = linkedMapOf<String, Conference>()
        var id = ""
        var groupNumber = ""
        var groupName = ""
        var type = ""
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                event == XmlPullParser.START_TAG && parser.name == "Table" -> {
                    id = ""
                    groupNumber = ""
                    groupName = ""
                    type = ""
                }
                event == XmlPullParser.START_TAG && parser.name == "Id" -> {
                    id = parser.nextText().trim()
                }
                event == XmlPullParser.START_TAG && parser.name == "Groupnumber" -> {
                    groupNumber = parser.nextText().filter(Char::isDigit)
                }
                event == XmlPullParser.START_TAG && parser.name == "Group_Name" -> {
                    groupName = parser.nextText().trim()
                }
                event == XmlPullParser.START_TAG && parser.name == "conference" -> {
                    type = parser.nextText().trim()
                }
                event == XmlPullParser.END_TAG && parser.name == "Table" -> {
                    if (groupNumber.isNotEmpty() && groupName.isNotEmpty()) {
                        val key = id.ifEmpty { groupNumber }
                        conferences[key] = Conference(key, groupNumber, groupName, type)
                    }
                }
            }
            event = parser.next()
        }
        return conferences.values.toList()
    }
}
