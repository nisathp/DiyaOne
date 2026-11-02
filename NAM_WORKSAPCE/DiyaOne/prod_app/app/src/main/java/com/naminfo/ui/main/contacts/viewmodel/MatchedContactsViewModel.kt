package com.naminfo.ui.main.contacts.viewmodel

import android.provider.ContactsContract
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.contacts.MobionContactsService
import com.naminfo.contacts.PhoneContactNumbers
import com.naminfo.ui.main.contacts.model.ContactAvatarModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.Friend
import org.linphone.core.FriendList

class MatchedContactsViewModel : ViewModel() {
    val contacts = MutableLiveData<List<ContactAvatarModel>>(emptyList())
    val loading = MutableLiveData(false)
    val status = MutableLiveData("")

    private var loadJob: Job? = null
    private var generation = 0

    // Access only on the Core thread.
    private val models = mutableMapOf<String, ContactAvatarModel>()

    private val mobionContactsRefreshObserver = Observer<Long> {
        reload(forceRefresh = true)
    }

    private data class AccountIdentity(
        val uri: String,
        val domain: String
    )

    private data class MatchedContact(
        val name: String,
        val number: String,
        val profilePictureUrl: String?
    )

    init {
        MobionContactsService.contactsRefreshRequested.observeForever(mobionContactsRefreshObserver)
    }

    fun reload(accountChanged: Boolean = false, forceRefresh: Boolean = false) {
        if (loadJob?.isActive == true && !accountChanged && !forceRefresh) return

        loadJob?.cancel()
        val requestGeneration = ++generation

        if (accountChanged) {
            contacts.value = emptyList()
        }

        loading.value = true
        status.value = "Loading contacts…"

        loadJob = viewModelScope.launch {
            try {
                val identity = onCoreThread { core ->
                    val address = core.defaultAccount
                        ?.params
                        ?.identityAddress
                        ?: error("Sign in to load contacts.")

                    AccountIdentity(
                        uri = address.asStringUriOnly(),
                        domain = address.domain.orEmpty()
                    ).also {
                        check(it.domain.isNotBlank()) {
                            "Your account SIP domain is missing."
                        }
                    }
                }

                val phoneContacts = withContext(Dispatchers.IO) {
                    if (PhoneContactNumbers.hasPermission(coreContext.context)) {
                        readPhoneContacts()
                    } else {
                        emptyMap()
                    }
                }

                val matches = withContext(Dispatchers.IO) {
                    MobionContactsService.fetchContacts(forceRefresh)
                        // The server directory can contain subscribers that are
                        // not in this phone's address book. Never show those.
                        .filter { apiContact ->
                            PhoneContactNumbers.normalize(apiContact.mobileNumber) in phoneContacts
                        }
                        .map { apiContact ->
                            val key = PhoneContactNumbers.normalize(apiContact.mobileNumber)
                            MatchedContact(
                                name = phoneContacts[key].orEmpty()
                                    .ifBlank { apiContact.name.trim() },
                                // Retain the API's number for the SIP address.
                                number = apiContact.mobileNumber,
                                // Profile URLs are mapped in one `/users` call;
                                // do not start one request per contact.
                                profilePictureUrl = apiContact.profilePictureUrl
                            )
                    }
                        .distinctBy { PhoneContactNumbers.normalize(it.number) }
                        .sortedBy { it.name.ifBlank { it.number }.lowercase() }
                }

                val result = onCoreThread { core ->
                    check(requestGeneration == generation) {
                        "Contact loading was replaced by another request."
                    }
                    check(
                        core.defaultAccount?.params?.identityAddress
                            ?.asStringUriOnly() == identity.uri
                    ) {
                        "Your account changed. Refresh contacts."
                    }

                    synchronizeFriends(core, identity.domain, matches)
                }

                contacts.value = result
                status.value = when {
                    result.isEmpty() ->
                        "No registered contacts found."

                    else -> ""
                }

            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == generation) {
                    status.value = if (contacts.value.orEmpty().isEmpty()) {
                        "Unable to load contacts: ${error.message.orEmpty()}"
                    } else {
                        "Refresh failed. Showing previously loaded contacts."
                    }
                }
            } finally {
                if (requestGeneration == generation) {
                    loading.value = false
                }
            }
        }
    }

    private fun readPhoneContacts(): Map<String, String> {
        val context = coreContext.context

        check(PhoneContactNumbers.hasPermission(context)) {
            "Contacts permission is required."
        }

        val result = linkedMapOf<String, String>()

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY +
                    " COLLATE LOCALIZED ASC"
        ) ?: error("Unable to read the phone book.")

        cursor.use {
            val numberColumn = it.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val nameColumn = it.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY
            )

            while (it.moveToNext()) {
                val number = PhoneContactNumbers.normalize(
                    it.getString(numberColumn).orEmpty()
                )
                val name = it.getString(nameColumn).orEmpty().trim()

                if (number.isNotBlank()) {
                    if (!result.containsKey(number) ||
                        result[number].isNullOrBlank()
                    ) {
                        result[number] = name
                    }
                }
            }
        }

        return result
    }

    // Called only on the Core thread.
    private fun synchronizeFriends(
        core: Core,
        domain: String,
        matches: List<MatchedContact>
    ): List<ContactAvatarModel> {
        val list = core.getFriendListByName("Mobion contacts")
            ?: core.createFriendList().also {
                it.displayName = "Mobion contacts"
                it.type = FriendList.Type.Default
                it.isDatabaseStorageEnabled = true
                core.addFriendList(it)
            }

        // Prepare all addresses before modifying the friend list.
        val prepared = matches.map { contact ->
            val address = Factory.instance().createAddress(
                "sip:${contact.number}@$domain"
            ) ?: error("Invalid contact number: ${contact.number}")

            contact to address
        }

        val existing = list.friends.associateBy { it.refKey.orEmpty() }
        val keep = mutableListOf<Friend>()

        for ((contact, address) in prepared) {
            val refKey = "mobion:${contact.number}"
            val friend = existing[refKey] ?: core.createFriend()

            friend.edit()
            friend.name = contact.name.ifBlank { contact.number }
            friend.refKey = refKey
            if (!contact.profilePictureUrl.isNullOrEmpty()) {
                friend.photo = contact.profilePictureUrl
            } else {
                friend.photo = null
            }

            for (oldAddress in friend.addresses) {
                friend.removeAddress(oldAddress)
            }
            for (oldNumber in friend.phoneNumbers) {
                friend.removePhoneNumber(oldNumber)
            }

            friend.addAddress(address)
            friend.addPhoneNumber(contact.number)
            friend.isSubscribesEnabled = true
            friend.done()

            keep.add(friend)
        }

        // Only this API-owned list is synchronized.
        // Native phone contacts and conference lists are untouched.
        list.synchronizeFriendsWith(keep.toTypedArray())
        list.isSubscriptionsEnabled = true
        list.updateSubscriptions()

        val wantedKeys = keep.map { it.refKey.orEmpty() }.toSet()

        for (key in models.keys.toList()) {
            if (key !in wantedKeys) {
                models.remove(key)?.destroy()
            }
        }

        val result = list.friends
            .filter { it.refKey.orEmpty() in wantedKeys }
            .map { friend ->
                val key = friend.refKey.orEmpty()
                val oldModel = models[key]

                val model = if (oldModel != null &&
                    oldModel.friend == friend &&
                    oldModel.contactName == friend.name
                ) {
                    oldModel.update(friend.address)
                    oldModel
                } else {
                    oldModel?.destroy()
                    ContactAvatarModel(friend, friend.address).also {
                        models[key] = it
                    }
                }

                model
            }
            .sortedBy { it.contactName.orEmpty().lowercase() }

        core.config.sync()
        coreContext.contactsManager.notifyContactsListChanged()

        return result
    }

    private suspend fun <T> onCoreThread(block: (Core) -> T): T =
        suspendCancellableCoroutine { continuation ->
            coreContext.postOnCoreThread { core ->
                if (continuation.isActive) {
                    continuation.resumeWith(runCatching { block(core) })
                }
            }
        }

    override fun onCleared() {
        loadJob?.cancel()
        MobionContactsService.contactsRefreshRequested.removeObserver(mobionContactsRefreshObserver)
        coreContext.postOnCoreThread {
            models.values.forEach { it.destroy() }
            models.clear()
        }

        super.onCleared()
    }
}
