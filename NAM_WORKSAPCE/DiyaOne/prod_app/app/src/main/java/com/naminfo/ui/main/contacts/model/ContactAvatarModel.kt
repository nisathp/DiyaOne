package com.naminfo.ui.main.contacts.model

import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.R
import com.naminfo.contacts.AbstractAvatarModel
import org.linphone.core.Address
import org.linphone.core.ChatRoom
import org.linphone.core.ConsolidatedPresence
import org.linphone.core.Friend
import org.linphone.core.FriendListenerStub
import org.linphone.core.SecurityLevel
import org.linphone.core.tools.Log
import com.naminfo.utils.AppUtils
import com.naminfo.utils.TimestampUtils
import androidx.core.net.toUri
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.core.FirebasePresenceRepository

class ContactAvatarModel
    @WorkerThread
    constructor(val friend: Friend, val address: Address? = null) : AbstractAvatarModel() {
    companion object {
        private const val TAG = "[Contact Avatar Model]"
    }

    val id = friend.refKey ?: friend.name ?: ""

    val contactName = friend.name

    val isStored = friend.inList()

    val isReadOnly = friend.isReadOnly

    val isNative = !friend.nativeUri.isNullOrEmpty()

    val isFavourite = MutableLiveData<Boolean>()

    val lastPresenceInfo = MutableLiveData<String>()

    @Volatile
    private var latestPicturePath: String = ""

    @Volatile
    private var latestDisplayName: String = friend.name.orEmpty()

    @Volatile
    private var avatarEpoch: Long = com.naminfo.contacts.MobionContactsService.currentAvatarCacheEpoch()

    val name = MutableLiveData<String>()

    var sortingName: String? = null

    var firstLetter: String? = null

    private val friendListener = object : FriendListenerStub() {
        @WorkerThread
        override fun onPresenceReceived(fr: Friend) {
            Log.d(
                "$TAG Presence received for friend [${fr.name}]: [${fr.consolidatedPresence}]"
            )
            computePresence(address)
        }
    }

    init {
        presenceStatus.postValue(ConsolidatedPresence.Offline)

        if (friend.addresses.isNotEmpty()) {
            friend.addListener(friendListener)
        }

        update(address)
        refreshSortingName()
    }

    @AnyThread
    fun compare(other: ContactAvatarModel?): Boolean {
        if (other == null) return false

        if (avatarEpoch != other.avatarEpoch) {
            return false
        }

        val picture = picturePath.value ?: latestPicturePath
        val otherPicture = other.picturePath.value ?: other.latestPicturePath
        if (picture != otherPicture) {
            return false
        }

        val currentName = name.value ?: latestDisplayName
        val otherName = other.name.value ?: other.latestDisplayName
        if (currentName != otherName) {
            return false
        }

        if (contactName != other.contactName) {
            return false
        }

        if (id != other.id) {
            return false
        }

        if (isFavourite.value != other.isFavourite.value) {
            return false
        }

        return true
    }

    @WorkerThread
    fun destroy() {
        if (friend.addresses.isNotEmpty()) {
            friend.removeListener(friendListener)
        }
    }

    @WorkerThread
    fun refreshSortingName() {
        sortingName = getNameToUseForSorting()
        firstLetter = AppUtils.getFirstLetter(getNameToUseForSorting().orEmpty())
    }

    @WorkerThread
    fun update(address: Address?) {
        updateSecurityLevel(address)

        isFavourite.postValue(friend.starred)
        initials.postValue(AppUtils.getInitials(friend.name.orEmpty()))
        showTrust.postValue(true)
        val resolvedPath = getAvatarUri(friend)?.toString().orEmpty()
        val resolvedName = friend.name.orEmpty()
        latestPicturePath = resolvedPath
        latestDisplayName = resolvedName
        avatarEpoch = com.naminfo.contacts.MobionContactsService.currentAvatarCacheEpoch()
        // `null.toString()` becomes the literal "null", which Coil treats as a remote image
        // request and can leave a stale avatar visible after a server-side DELETE.
        picturePath.postValue(resolvedPath)

        name.postValue(resolvedName)
        initials.postValue(AppUtils.getInitials(resolvedName))
        computePresence(address)
    }

    @AnyThread
    fun applyResolvedProfile(displayName: String, photoUrl: String) {
        latestDisplayName = displayName
        latestPicturePath = photoUrl
        avatarEpoch = com.naminfo.contacts.MobionContactsService.currentAvatarCacheEpoch()
        name.postValue(displayName)
        initials.postValue(AppUtils.getInitials(displayName))
        picturePath.postValue(photoUrl)
    }

    @WorkerThread
    fun updateSecurityLevelUsingConversation(chatRoom: ChatRoom) {
        /*
        // Don't do that, as chatRoom securityLevel is taking into account the security level
        // between the device and the other devices that shares the same SIP identity
        val securityLevel = when (chatRoom.securityLevel) {
            ChatRoom.SecurityLevel.Unsafe -> {
                SecurityLevel.Unsafe
            }
            ChatRoom.SecurityLevel.Encrypted -> {
                SecurityLevel.EndToEndEncrypted
            }
            ChatRoom.SecurityLevel.Safe -> {
                SecurityLevel.EndToEndEncryptedAndVerified
            }
            else -> SecurityLevel.None
        }*/

        val participants = chatRoom.participants
        var lowestSecurityLevel = if (participants.isEmpty()) {
            SecurityLevel.None
        } else {
            SecurityLevel.EndToEndEncryptedAndVerified
        }
        for (participant in participants) {
            val avatar = coreContext.contactsManager.getContactAvatarModelForAddress(
                participant.address
            )
            val level = avatar.trust.value ?: SecurityLevel.None
            if (level == SecurityLevel.None) {
                lowestSecurityLevel = SecurityLevel.None
            } else if (level == SecurityLevel.Unsafe) {
                lowestSecurityLevel = SecurityLevel.Unsafe
                break
            } else if (lowestSecurityLevel != SecurityLevel.None && level != SecurityLevel.EndToEndEncryptedAndVerified) {
                lowestSecurityLevel = SecurityLevel.EndToEndEncrypted
            }
        }
        trust.postValue(lowestSecurityLevel)
    }

    @WorkerThread
    fun updateSecurityLevel(address: Address?) {
        if (address == null) {
            trust.postValue(friend.securityLevel)
        } else {
            trust.postValue(friend.getSecurityLevelForAddress(address))
        }
    }

    @WorkerThread
    fun getNameToUseForSorting(): String {
        val sortByFirstName = corePreferences.sortContactsByFirstName
        val firstOrLastName = if (sortByFirstName) friend.firstName else friend.lastName
        return firstOrLastName ?: friend.name ?: friend.organization ?: friend.vcard?.fullName ?: ""
    }

    @WorkerThread
    private fun getAvatarUri(friend: Friend): Uri? = resolveFriendAvatarUri(friend)

    @WorkerThread
    private fun computePresence(address: Address? = null) {
        val presence = if (address == null) {
            friend.consolidatedPresence
        } else {
            friend.getPresenceModelForUriOrTel(address.asStringUriOnly())?.consolidatedPresence ?: friend.consolidatedPresence
        }
        Log.d("$TAG Friend [${friend.name}] presence status is [$presence]")
        presenceStatus.postValue(presence)

        val presenceString = when (presence) {
            ConsolidatedPresence.Online -> {
                AppUtils.getString(R.string.contact_presence_status_online)
            }
            ConsolidatedPresence.Busy -> {
                val timestamp = friend.presenceModel?.latestActivityTimestamp ?: -1L
                if (timestamp != -1L) {
                    when {
                        TimestampUtils.isToday(timestamp) -> {
                            val time = TimestampUtils.timeToString(
                                timestamp,
                                timestampInSecs = true
                            )
                            AppUtils.getFormattedString(
                                R.string.contact_presence_status_was_online_today_at,
                                time
                            )
                        }
                        TimestampUtils.isYesterday(timestamp) -> {
                            val time = TimestampUtils.timeToString(
                                timestamp,
                                timestampInSecs = true
                            )
                            AppUtils.getFormattedString(
                                R.string.contact_presence_status_was_online_yesterday_at,
                                time
                            )
                        }
                        else -> {
                            val date = TimestampUtils.toString(
                                timestamp,
                                onlyDate = true,
                                shortDate = false,
                                hideYear = true
                            )
                            AppUtils.getFormattedString(
                                R.string.contact_presence_status_was_online_on,
                                date
                            )
                        }
                    }
                } else {
                    AppUtils.getString(R.string.contact_presence_status_away)
                }
            }
            ConsolidatedPresence.DoNotDisturb -> {
                AppUtils.getString(R.string.contact_presence_status_do_not_disturb)
            }
            else -> ""
        }
        lastPresenceInfo.postValue(presenceString)

        // Firebase is deliberately additive: when it reports a recent SIP registration,
        // show Online; otherwise retain the normal SIP presence result unchanged.
        val phoneNumber = address?.username
            ?: friend.phoneNumbersWithLabel.firstOrNull()?.phoneNumber
            ?: friend.addresses.firstOrNull()?.username
        FirebasePresenceRepository.fetchIfEnabled(phoneNumber) { online ->
            if (online) {
                presenceStatus.postValue(ConsolidatedPresence.Online)
                lastPresenceInfo.postValue(AppUtils.getString(R.string.contact_presence_status_online))
            }
        }
    }
}

internal fun resolveFriendAvatarUri(friend: Friend): Uri? {
    val picturePath = friend.photo?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    if (!picturePath.isNullOrEmpty()) {
        return picturePath.toUri()
    }

    // Only server/app-assigned profile photos are shown; never fall back to Android
    // phone-book/native contact photos.
    return null
}
