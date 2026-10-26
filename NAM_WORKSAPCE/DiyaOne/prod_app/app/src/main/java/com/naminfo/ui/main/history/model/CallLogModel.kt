package com.naminfo.ui.main.history.model

import androidx.annotation.IntegerRes
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.R
import org.linphone.core.CallLog
import org.linphone.core.tools.Log
import com.naminfo.ui.main.contacts.model.ContactAvatarModel
import com.naminfo.utils.AppUtils
import com.naminfo.utils.LinphoneUtils
import com.naminfo.utils.TimestampUtils

class CallLogModel
    @WorkerThread
    constructor(private val callLog: CallLog) {
    companion object {
        private const val TAG = "[Call Log Model]"
    }

    val id = callLog.callId ?: callLog.refKey

    val timestamp = callLog.startDate

    val address = callLog.remoteAddress

    val sipUri = address.asStringUriOnly()

    val displayedAddress: String

    val displayedName: String

    val avatarModel: ContactAvatarModel

    val wasConference: Boolean

    val isVideoCall: Boolean

    val isTCall: Boolean

    @IntegerRes
    val iconResId: Int

    val dateTime: String

    val friendRefKey: String?

    var friendExists: Boolean = false

    val isSelected = MutableLiveData<Boolean>(false)

    val isSelectionMode = MutableLiveData<Boolean>(false)

    val canAddToContacts: Boolean
        get() = !friendExists && !wasConference && !corePreferences.disableAddContact

    init {
        val date = if (TimestampUtils.isToday(timestamp)) {
            AppUtils.getString(R.string.today)
        } else if (TimestampUtils.isYesterday(timestamp)) {
            AppUtils.getString(R.string.yesterday)
        } else {
            TimestampUtils.toString(timestamp, onlyDate = true, shortDate = true, hideYear = true)
        }
        val time = TimestampUtils.timeToString(timestamp)
        dateTime = "$date | $time"

        val remoteUsername = address.username.orEmpty()
        val isCustomAudioConference = remoteUsername.endsWith("_tcaudio", ignoreCase = true)
        val isCustomVideoConference = remoteUsername.endsWith("_tcvideo", ignoreCase = true)
        val isCustomConference = isCustomAudioConference || isCustomVideoConference

        wasConference = callLog.wasConference() || isCustomConference
        isVideoCall = if (isCustomConference) {
            isCustomVideoConference
        } else {
            LinphoneUtils.wasVideoCall(callLog)
        }
        isTCall = remoteUsername.startsWith("T_", ignoreCase = true) ||
            remoteUsername.startsWith("tac_", ignoreCase = true) ||
            remoteUsername.startsWith("tvc_", ignoreCase = true) ||
            TranslationCallHistoryMetadata.isTranslationCall(coreContext.context, callLog.callId ?: callLog.refKey)
        if (wasConference) {
            val conferenceInfo = callLog.conferenceInfo
            if (conferenceInfo != null && !isCustomConference) {
                avatarModel = coreContext.contactsManager.getContactAvatarModelForConferenceInfo(
                    conferenceInfo
                )
            } else {
                Log.w("$TAG Failed to retrieve conference info attached to call log!")
                val fakeFriend = coreContext.core.createFriend()
                fakeFriend.address = address
                fakeFriend.name = AppUtils.getString(
                    if (isVideoCall) {
                        R.string.history_call_type_video_conference
                    } else {
                        R.string.history_call_type_audio_conference
                    }
                )
                avatarModel = ContactAvatarModel(fakeFriend)
                avatarModel.forceConferenceIcon.postValue(true)
            }

            friendRefKey = null
            friendExists = false
        } else {
            avatarModel = coreContext.contactsManager.getContactAvatarModelForAddress(address)
            val friend = avatarModel.friend
            friendRefKey = friend.refKey
            friendExists = coreContext.contactsManager.isContactAvailable(friend)
        }
        displayedAddress = when {
            remoteUsername.startsWith("T_", ignoreCase = true) -> {
                val phoneNumber = remoteUsername.substringAfter('_')
                "${if (isVideoCall) "tvc_" else "tac_"}$phoneNumber"
            }
            isTCall && !remoteUsername.startsWith("tac_", ignoreCase = true) &&
                !remoteUsername.startsWith("tvc_", ignoreCase = true) ->
                "${if (isVideoCall) "tvc_" else "tac_"}$remoteUsername"
            else -> remoteUsername
        }

        displayedName = if (isCustomConference) {
            AppUtils.getString(
                if (isVideoCall) {
                    R.string.history_call_type_video_conference
                } else {
                    R.string.history_call_type_audio_conference
                }
            )
        } else if (wasConference) {
            callLog.conferenceInfo?.subject.orEmpty()
        } else {
            coreContext.contactsManager.findContactByAddress(address)
                ?.name
                .orEmpty()
                .trim()
                .takeUnless {
                    it == displayedAddress || it == sipUri
                }
                .orEmpty()
        }

        iconResId = LinphoneUtils.getCallIconResId(callLog.status, callLog.dir)
    }

    @UiThread
    fun delete() {
        coreContext.postOnCoreThread { core ->
            val logsToRemove = core.callLogs.filter { it.remoteAddress.weakEqual(address) }
            for (log in logsToRemove) {
                core.removeCallLog(log)
            }
        }
    }
}
