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
import org.linphone.core.tools.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

object MobionContactsService {
    private const val TAG = "[Mobion Contacts Service]"

    @Volatile
    private var contactsCache: List<Contact>? = null

    // A directory cache can be created before a new sign-in has stored its
    // access token. Keep the token used for the profile map separately so the
    // first Contacts screen after sign-in always enriches the cached directory.
    @Volatile
    private var profileMapAccessToken: String? = null

    private val contactsRefreshVersion = AtomicLong(0L)

    // The backend can briefly return the old URL immediately after DELETE. Keep the local
    // removal authoritative until `/users` confirms that this number has no picture anymore.
    private val locallyRemovedProfileNumbers = ConcurrentHashMap.newKeySet<String>()

    /**
     * Published after a profile photo is changed. Active contact screens use this to reload the
     * single `/users` profile map immediately instead of waiting for a manual pull-to-refresh.
     */
    val contactsRefreshRequested = MutableLiveData<Long>()

    private const val CONTACTS_URL =
        "https://mobionglobal.com/fs_webservice/WebService.asmx/Get_MobionNumber"

    private const val CONFERENCES_URL =
        "https://mobionglobal.com/fs_webservice/WebService.asmx/GroupSetting_View"

    data class Contact(
        val name: String,
        val mobileNumber: String,
        val profilePictureUrl: String? = null
    )

    private data class ProfilePictureMapResult(
        val profileUrls: Map<String, String>,
        val loaded: Boolean
    )

    data class Conference(
        val id: String,
        val groupNumber: String,
        val groupName: String,
        val type: String
    )

    @Synchronized
    fun fetchContacts(forceRefresh: Boolean = false): List<Contact> {
        val accessToken = corePreferences.authToken.orEmpty()
        val hasProfileMapForCurrentSession =
            accessToken.isNotBlank() && profileMapAccessToken == accessToken

        // Use a cached, already enriched directory during this signed-in
        // session. A plain XML-only cache must not bypass the first `/users`
        // request after sign-in.
        contactsCache?.takeIf { !forceRefresh && hasProfileMapForCurrentSession }
            ?.let { return it }

        // The XML endpoint is the complete phone directory. The authenticated
        // /users endpoint enriches that directory with profile URLs only; it
        // never replaces contacts which are absent from the API response.
        val xmlContacts = if (!forceRefresh && contactsCache != null) {
            contactsCache.orEmpty()
        } else {
            try {
                fetchMobionXmlContacts()
            } catch (error: Exception) {
                Log.w("$TAG Failed to fetch XML contacts: ${error.javaClass.simpleName}")
                // A pull-to-refresh must never replace a usable directory with an
                // empty list when this network request times out.
                return contactsCache ?: readPersistedContacts()
            }
        }
        if (xmlContacts.isEmpty()) return contactsCache ?: readPersistedContacts()

        val profileMapResult = fetchProfileUrlsByNumber()
        if (profileMapResult.loaded && accessToken.isNotBlank()) {
            profileMapAccessToken = accessToken
        }
        val cachedProfileUrlsByNumber = (contactsCache ?: readPersistedContacts()).associate {
            it.mobileNumber to it.profilePictureUrl
        }
        val contacts = xmlContacts.map { contact ->
            val isLocallyRemoved = contact.mobileNumber in locallyRemovedProfileNumbers
            val serverPictureUrl = profileMapResult.profileUrls[contact.mobileNumber]
            if (isLocallyRemoved && profileMapResult.loaded && serverPictureUrl == null) {
                locallyRemovedProfileNumbers.remove(contact.mobileNumber)
            }
            contact.copy(
                // A successful `/users` response is authoritative, including an explicit
                // null picture URL after the user deletes their profile photo. While DELETE
                // propagation is still catching up, never briefly restore the old image.
                profilePictureUrl = if (isLocallyRemoved) {
                    null
                } else if (profileMapResult.loaded) {
                    serverPictureUrl
                } else {
                    cachedProfileUrlsByNumber[contact.mobileNumber]
                }
            )
        }
        contactsCache = contacts
        persistContacts(contacts)
        return contacts
    }

    fun clearContactsCache() {
        contactsCache = null
        profileMapAccessToken = null
    }

    fun refreshContactsAfterProfilePictureChange() {
        clearContactsCache()
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

    private fun fetchProfileUrlsByNumber(): ProfilePictureMapResult {
        val apiBase = corePreferences.apiDomain?.takeIf { it.isNotBlank() }
            ?: "https://mobionglobal.com/Diyaone/api"
        // `/users` is used only as a mobile-number -> profile-picture map.
        // Keep this endpoint identical to the backend contract; the XML
        // Get_MobionNumber endpoint remains the 34-contact directory source.
        val urlStr = "$apiBase/users"
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        val token = corePreferences.authToken
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
                    val profileUrls = mutableMapOf<String, String>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val mobileNo = PhoneContactNumbers.normalize(
                            obj.optString("mobileNo").ifEmpty { obj.optString("subscriberId") }
                        )
                        val rawPic = obj.optString("profilePictureUrl")
                        val profilePictureUrl = normalizeProfilePictureUrl(rawPic, apiBase)
                        if (mobileNo.length == 10 && profilePictureUrl != null) {
                            profileUrls[mobileNo] = profilePictureUrl
                        }
                    }
                    Log.i("$TAG Parsed [${profileUrls.size}] profile URLs from JSON contacts")
                    return ProfilePictureMapResult(profileUrls, loaded = true)
                }
            }
            ApiLogRepository.complete(logId, responseCode, connection.errorStream?.bufferedReader()?.use { it.readText() })
            ProfilePictureMapResult(emptyMap(), loaded = false)
        } catch (e: Exception) {
            ApiLogRepository.fail(logId, e)
            Log.w("$TAG Failed to fetch profile URL map: ${e.javaClass.simpleName}")
            ProfilePictureMapResult(emptyMap(), loaded = false)
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchMobionXmlContacts(): List<Contact> {
        val connection = URL(CONTACTS_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "text/xml")
        val logId = ApiLogRepository.begin("GET", CONTACTS_URL)

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                ApiLogRepository.complete(logId, responseCode, error)
                error("Mobion contacts request failed with HTTP $responseCode")
            }

            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val responseXml = reader.readText()
                ApiLogRepository.complete(logId, responseCode, responseXml)
                Log.i("$TAG Get_MobionNumber response:\n$responseXml")

                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(StringReader(responseXml))
                parseContacts(parser).also { contacts ->
                    Log.i("$TAG Parsed [${contacts.size}] valid XML contacts")
                }
            }
        } catch (error: Exception) {
            ApiLogRepository.fail(logId, error)
            throw error
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

    fun fetchProfilePictureUrl(subscriberId: String): String? {
        val apiBase = corePreferences.apiDomain?.takeIf { it.isNotBlank() }
            ?: "https://mobionglobal.com/Diyaone/api"
        val accessToken = corePreferences.authToken
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

    private fun parseContacts(parser: XmlPullParser): List<Contact> {
        val contacts = linkedMapOf<String, Contact>()
        var name = ""
        var mobileNumber = ""
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                event == XmlPullParser.START_TAG && parser.name == "Table" -> {
                    name = ""
                    mobileNumber = ""
                }
                event == XmlPullParser.START_TAG && parser.name == "Name" -> {
                    name = parser.nextText().trim()
                }
                event == XmlPullParser.START_TAG && parser.name == "Mobile_No" -> {
                    mobileNumber = PhoneContactNumbers.normalize(parser.nextText())
                }
                event == XmlPullParser.END_TAG && parser.name == "Table" -> {
                    if (mobileNumber.length == 10) {
                        contacts[mobileNumber] = Contact(name, mobileNumber, null)
                    }
                }
            }
            event = parser.next()
        }
        return contacts.values.toList()
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
