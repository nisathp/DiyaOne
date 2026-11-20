package com.naminfo.utils

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.util.Patterns
import androidx.annotation.AnyThread
import androidx.annotation.DrawableRes
import androidx.annotation.IntegerRes
import androidx.annotation.WorkerThread
import androidx.core.text.toSpannable
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.R
import com.naminfo.contacts.PhoneContactNumbers
import com.naminfo.contacts.getListOfSipAddresses
import com.naminfo.core.CustomImdn
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Call.Dir
import org.linphone.core.Call.Status
import org.linphone.core.CallLog
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom
import org.linphone.core.Conference
import org.linphone.core.ConferenceInfo
import org.linphone.core.ConferenceParams
import org.linphone.core.ConferenceScheduler
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.Friend
import org.linphone.core.MediaDirection
import org.linphone.core.Reason
import org.linphone.core.tools.Log
import com.naminfo.ui.main.contacts.model.ContactAvatarModel
import com.naminfo.ui.main.chat.decodeForwardEnvelope
import com.naminfo.ui.main.chat.decodeReplyPreviewEnvelope
import com.naminfo.ui.main.chat.resolveComposingNames
import com.naminfo.ui.main.model.isEndToEndEncryptionMandatory

class LinphoneUtils {
    companion object {
        private const val TAG = "[Linphone Utils]"

        private const val OUTGOING_VIDEO_LOG_PREFIX = "diyaone:outgoing:video:"
        private const val OUTGOING_AUDIO_LOG_PREFIX = "diyaone:outgoing:audio:"

        @WorkerThread
        fun rememberOutgoingCallMedia(call: Call) {
            val log = call.callLog
            if (call.dir != Dir.Outgoing || !log.refKey.isNullOrEmpty()) return

            // The SDK's video flag describes the final negotiated media, not the
            // caller's original choice. Store that choice in the persistent log.
            val prefix = if (call.params.isVideoEnabled) {
                OUTGOING_VIDEO_LOG_PREFIX
            } else {
                OUTGOING_AUDIO_LOG_PREFIX
            }
            log.refKey = prefix + java.util.UUID.randomUUID().toString()
        }

        @WorkerThread
        fun wasVideoCall(log: CallLog): Boolean {
            if (log.dir == Dir.Outgoing) {
                val reference = log.refKey.orEmpty()
                if (reference.startsWith(OUTGOING_VIDEO_LOG_PREFIX)) return true
                if (reference.startsWith(OUTGOING_AUDIO_LOG_PREFIX)) return false
            }
            return log.isVideoEnabled
        }

        const val RECORDING_FILE_NAME_HEADER = "call_recording_"

        @WorkerThread
        fun canonicalizeBasicChatAddress(address: Address, account: Account): Address {
            if (address.port > 0 || address.domain != account.params.identityAddress?.domain) {
                return address
            }

            return address.clone().apply {
                val proxyPort = account.params.serverAddress?.port ?: 0
                port = if (proxyPort > 0) proxyPort else 5060
            }
        }

        /**
         * Find a one-to-one room even when the same FreeSWITCH account is reached through
         * one of its configured SIP domains (for example, its LAN address and public domain).
         * The alias lookup is deliberately limited to Basic SIP rooms and this local account;
         * it must not merge Flexisip rooms or users belonging to another account.
         */
        @WorkerThread
        fun findExistingOneToOneChatRoom(
            core: Core,
            params: ConferenceParams,
            localAddress: Address?,
            remoteAddress: Address
        ): ChatRoom? {
            val participants = arrayOf(remoteAddress)
            core.searchChatRoom(params, localAddress, null, participants)?.let { return it }

            val chatParams = params.chatParams ?: return null
            if (chatParams.backend != ChatRoom.Backend.Basic) return null

            val account = params.account ?: return null
            val identityDomain = account.params.identityAddress?.domain
            val accountDomains = setOfNotNull(
                identityDomain,
                account.params.serverAddress?.domain,
                corePreferences.defaultDomain,
                corePreferences.thirdPartySipAccountDefaultDomain
            ).map { it.trim().trimEnd('.').lowercase() }.filter { it.isNotEmpty() }.toSet()
            val remoteDomain = remoteAddress.domain.orEmpty().trim().trimEnd('.').lowercase()
            val username = remoteAddress.username ?: return null
            if (remoteDomain !in accountDomains) return null

            return core.chatRooms.firstOrNull { room ->
                room.account == account &&
                    room.hasCapability(ChatRoom.Capabilities.Basic.toInt()) &&
                    !isChatRoomAGroup(room) &&
                    localAddress?.let { basicChatAddressesMatch(it, room.localAddress) } == true &&
                    room.peerAddress.username == username &&
                    room.peerAddress.domain.orEmpty().trim().trimEnd('.').lowercase() in accountDomains
            }
        }

        const val RECORDING_FILE_NAME_URI_TIMESTAMP_SEPARATOR = "_on_"
        const val RECORDING_MKV_FILE_EXTENSION = ".mkv"
        const val RECORDING_SMFF_FILE_EXTENSION = ".smff"

        @AnyThread
        fun getRemoteProvisioningUrlFromUri(uri: String): String? {
            val linphoneScheme = "linphone-config:"
            return if (uri.startsWith(linphoneScheme)) {
                val remoteConfigUri = uri.substring(linphoneScheme.length)
                val url = when {
                    remoteConfigUri.startsWith("http://") || remoteConfigUri.startsWith("https://") -> remoteConfigUri
                    remoteConfigUri.startsWith("file://") -> remoteConfigUri
                    remoteConfigUri.startsWith("//") -> "https:$remoteConfigUri"
                    else -> "https://$remoteConfigUri"
                }
                url
            } else {
                val isValidUrl = Patterns.WEB_URL.matcher(uri).matches()
                if (!isValidUrl) {
                    return null
                }
                uri
            }
        }

        @WorkerThread
        fun getDefaultAccount(): Account? {
            return coreContext.core.defaultAccount ?: coreContext.core.accountList.firstOrNull()
        }

        @WorkerThread
        fun getAccountForAddress(address: Address): Account? {
            return coreContext.core.accountList.find {
                it.params.identityAddress?.weakEqual(address) == true
            }
        }

        /** Local history keeps its SIP identity even after its Account object is removed. */
        @WorkerThread
        fun isHistoryOwnedBy(localAddress: Address?, account: Account?): Boolean {
            val identity = account?.params?.identityAddress ?: return false
            val localUser = localAddress?.username?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val activeUser = identity.username?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            if (identity.weakEqual(localAddress)) return true

            // FreeSWITCH may save a phone login with an alias domain or an international prefix.
            // Compare normalized phone usernames only. Named SIP users require an address match.
            if (!localUser.all { it.isDigit() || it in "+-() " } ||
                !activeUser.all { it.isDigit() || it in "+-() " }
            ) {
                return false
            }
            val phone = PhoneContactNumbers.normalize(localUser)
            return phone.isNotEmpty() && phone == PhoneContactNumbers.normalize(activeUser)
        }

        @WorkerThread
        fun applyInternationalPrefix(account: Account? = null): Boolean {
            return account?.params?.useInternationalPrefixForCallsAndChats
                ?: (getDefaultAccount()?.params?.useInternationalPrefixForCallsAndChats == true)
        }

        @WorkerThread
        fun getAddressAsCleanStringUriOnly(address: Address): String {
            val scheme = address.scheme ?: "sip"
            val username = address.username
            if (username.isNullOrEmpty()) {
                return "$scheme:${address.domain}"
            }
            return "$scheme:$username@${address.domain}"
        }

        @WorkerThread
        fun getDisplayName(address: Address?): String {
            if (address == null) return "[null]"

            val cleanUsername = address.username.orEmpty().trim()
            val displayName = address.displayName
            if (!displayName.isNullOrEmpty() && !com.naminfo.contacts.PhoneContactNumbers.isNumericPlaceholder(displayName, cleanUsername)) {
                return displayName
            }

            val account = coreContext.core.accountList.find { account ->
                account.params.identityAddress?.asStringUriOnly() == address.asStringUriOnly()
            }
            val localDisplayName = account?.params?.identityAddress?.displayName
            if (!localDisplayName.isNullOrEmpty() && !com.naminfo.contacts.PhoneContactNumbers.isNumericPlaceholder(localDisplayName, cleanUsername)) {
                return localDisplayName
            }

            val savedNativeName = coreContext.contactsManager.findSavedNativeName(address)
            if (!savedNativeName.isNullOrEmpty()) {
                return savedNativeName
            }

            return if (displayName.isNullOrEmpty()) {
                address.username ?: address.asString()
            } else {
                displayName
            }
        }

        @WorkerThread
        fun getSingleAvailableAddressForFriend(friend: Friend): Address? {
            val addresses = friend.getListOfSipAddresses()
            val addressesCount = addresses.size
            val numbersCount = friend.phoneNumbers.size

            // Do not consider phone numbers if default account is in secure mode
            val enablePhoneNumbers = !corePreferences.hidePhoneNumbers && !isEndToEndEncryptionMandatory()

            if (addressesCount == 1 && (numbersCount == 0 || !enablePhoneNumbers)) {
                val address = addresses.first()
                Log.i("$TAG Only 1 SIP address found for contact [${friend.name}], using it")
                return address
            } else if (addressesCount == 0 && numbersCount == 1 && enablePhoneNumbers) {
                val number = friend.phoneNumbers.first()
                val address = friend.core.interpretUrl(number, applyInternationalPrefix())
                if (address != null) {
                    Log.i("$TAG Only 1 phone number found for contact [${friend.name}], using it")
                    return address
                } else {
                    Log.e("$TAG Failed to interpret phone number [$number] as SIP address")
                }
            }

            val defaultDomain = corePreferences.defaultDomain
            val currentDomain = getDefaultAccount()?.params?.identityAddress?.domain
            if (defaultDomain != currentDomain) return null

            var defaultDomainAddressesCount = 0
            var firstDefaultDomainAddress: Address? = null
            for (address in addresses) {
                if (address.domain == defaultDomain) {
                    defaultDomainAddressesCount += 1
                    firstDefaultDomainAddress = address
                }
            }
            Log.i("$TAG Friend has [$defaultDomainAddressesCount] SIP addresses on the default domain")
            if (defaultDomainAddressesCount == 1) {
                return firstDefaultDomainAddress
            }

            return null
        }

        @WorkerThread
        fun getFirstAvailableAddressForFriend(friend: Friend): Address? {
            // Return any SIP address first
            val address = friend.address ?: friend.addresses.firstOrNull()
            if (address != null) return address

            val phoneNumbers = friend.phoneNumbers
            // If no SIP address stored in Friend, check for SIP address in phone numbers presence
            for (phoneNumber in phoneNumbers) {
                val presenceModel = friend.getPresenceModelForUriOrTel(phoneNumber)
                val hasPresenceInfo = !presenceModel?.contact.isNullOrEmpty()
                if (presenceModel != null && hasPresenceInfo) {
                    val contact = presenceModel.contact
                    if (!contact.isNullOrEmpty()) {
                        val address = coreContext.core.interpretUrl(contact, false)
                        if (address != null) {
                            address.clean() // To remove ;user=phone
                            return address
                        }
                    }
                }
            }

            // Finally format any phone number as SIP address
            for (phoneNumber in phoneNumbers) {
                val address = coreContext.core.interpretUrl(phoneNumber, false)
                if (address != null) {
                    return address
                }
            }

            return null
        }

        @WorkerThread
        fun isSipAddressLinkedToPhoneNumberByPresence(friend: Friend, sipAddress: String): Boolean {
            for (phoneNumber in friend.phoneNumbers) {
                val presenceModel = friend.getPresenceModelForUriOrTel(phoneNumber)
                if (presenceModel != null) {
                    val contact = presenceModel.contact
                    if (contact == sipAddress) {
                        Log.i("$TAG SIP address [$sipAddress] is presence contact address for phone number [$phoneNumber]")
                        return true
                    }
                }
            }
            return false
        }

        @AnyThread
        fun isCallIncoming(callState: Call.State): Boolean {
            return when (callState) {
                Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> true
                else -> false
            }
        }

        @AnyThread
        fun isCallOutgoing(callState: Call.State, considerEarlyMedia: Boolean = true): Boolean {
            return when (callState) {
                Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> true
                Call.State.OutgoingEarlyMedia -> considerEarlyMedia
                else -> false
            }
        }

        @AnyThread
        fun isCallPaused(callState: Call.State): Boolean {
            return when (callState) {
                Call.State.Pausing, Call.State.Paused, Call.State.PausedByRemote, Call.State.Resuming -> true
                else -> false
            }
        }

        @AnyThread
        fun isCallEnding(callState: Call.State, considerReleasedAsEnding: Boolean = false): Boolean {
            return when (callState) {
                Call.State.End, Call.State.Error -> true
                Call.State.Released -> considerReleasedAsEnding
                else -> false
            }
        }

        @AnyThread
        fun isCallActive(callState: Call.State): Boolean {
            return when (callState) {
                Call.State.Connected, Call.State.StreamsRunning, Call.State.UpdatedByRemote, Call.State.Updating -> true
                else -> false
            }
        }

        @WorkerThread
        fun getCallErrorInfoToast(call: Call): String {
            val errorInfo = call.errorInfo
            Log.w(
                "$TAG Call error reason is [${errorInfo.reason}](${errorInfo.protocolCode}): ${errorInfo.phrase}"
            )
            return when (errorInfo.reason) {
                Reason.Busy -> {
                    AppUtils.getString(R.string.call_error_user_busy_toast)
                }
                Reason.IOError -> {
                    AppUtils.getString(R.string.call_error_io_error_toast)
                }
                Reason.NotAcceptable -> {
                    AppUtils.getString(R.string.call_error_incompatible_media_params_toast)
                }
                Reason.NotFound -> {
                    AppUtils.getString(R.string.call_error_user_not_found_toast)
                }
                Reason.ServerTimeout -> {
                    AppUtils.getString(R.string.call_error_server_timeout_toast)
                }
                Reason.TemporarilyUnavailable -> {
                    AppUtils.getString(R.string.call_error_temporarily_unavailable_toast)
                }
                else -> {
                    "${errorInfo.protocolCode} / ${errorInfo.phrase}"
                }
            }
        }

        @WorkerThread
        fun isEndToEndEncryptedChatAvailable(core: Core): Boolean {
            return core.isLimeX3DhEnabled &&
                core.defaultAccount?.params?.limeServerUrl != null &&
                core.defaultAccount?.params?.conferenceFactoryUri != null
        }

        @WorkerThread
        fun isGroupChatAvailable(core: Core): Boolean {
            return core.defaultAccount?.params?.conferenceFactoryUri != null
        }

        @WorkerThread
        fun isRemoteConferencingAvailable(core: Core): Boolean {
            return core.defaultAccount?.params?.audioVideoConferenceFactoryAddress != null
        }

        @WorkerThread
        fun isVideoEnabled(call: Call): Boolean {
            if (!call.core.isVideoEnabled) {
                Log.w("$TAG Video is disabled in Core, assume call is audio only")
                return false
            }

            val isIncoming = isCallIncoming(call.state)
            return if (isIncoming || call.state == Call.State.Connected) {
                call.remoteParams?.isVideoEnabled == true && call.remoteParams?.videoDirection != MediaDirection.Inactive
            } else {
                call.currentParams.isVideoEnabled && call.currentParams.videoDirection != MediaDirection.Inactive
            }
        }

        @WorkerThread
        fun getConferenceInfoIfAny(call: Call): ConferenceInfo? {
            val remoteContactAddress = call.remoteContactAddress
            return if (remoteContactAddress != null) {
                call.core.findConferenceInformationFromUri(remoteContactAddress) ?: call.callLog.conferenceInfo
            } else {
                call.callLog.conferenceInfo
            }
        }

        @WorkerThread
        fun createConferenceScheduler(account: Account?): ConferenceScheduler {
            if (!account?.params?.ccmpServerUrl.isNullOrEmpty()) {
                Log.i(
                    "$TAG CCMP server URL has been set in Account's params, using CCMP conference scheduler"
                )
                return coreContext.core.createConferenceSchedulerWithType(
                    account,
                    ConferenceScheduler.Type.CCMP
                )
            }
            Log.i(
                "$TAG CCMP server URL hasn't been set in Account's params, using SIP conference scheduler"
            )
            return coreContext.core.createConferenceSchedulerWithType(
                account,
                ConferenceScheduler.Type.SIP
            )
        }

        @WorkerThread
        fun createGroupCall(account: Account?, subject: String): Conference? {
            val core = coreContext.core
            val conferenceParams = core.createConferenceParams(null)
            conferenceParams.isVideoEnabled = true
            conferenceParams.account = account
            conferenceParams.subject = subject

            // Enable end-to-end encryption if client supports it
            conferenceParams.securityLevel = if (corePreferences.createEndToEndEncryptedMeetingsAndGroupCalls) {
                Log.i("$TAG Requesting EndToEnd security level for conference")
                Conference.SecurityLevel.EndToEnd
            } else {
                Log.i("$TAG Requesting PointToPoint security level for conference")
                Conference.SecurityLevel.PointToPoint
            }

            // Allows to have a chat room within the conference
            conferenceParams.isChatEnabled = true

            Log.i("$TAG Creating group call with subject ${conferenceParams.subject}")
            return core.createConferenceWithParams(conferenceParams)
        }

        @WorkerThread
        fun getChatRoomParamsToCancelMeeting(): ConferenceParams? {
            val chatRoomParams = coreContext.core.createConferenceParams(null)
            chatRoomParams.isChatEnabled = true
            chatRoomParams.isGroupEnabled = false
            chatRoomParams.subject = "Meeting invitation" // Won't be used
            val chatParams = chatRoomParams.chatParams ?: return null
            chatParams.ephemeralLifetime = 0 // Make sure ephemeral is disabled by default
            chatParams.backend = ChatRoom.Backend.FlexisipChat
            chatRoomParams.securityLevel = Conference.SecurityLevel.EndToEnd
            return chatRoomParams
        }

        @WorkerThread
        fun arePushNotificationsAvailable(core: Core): Boolean {
            if (!core.isPushNotificationAvailable) {
                Log.w(
                    "$TAG Push notifications aren't available in the Core, disable account creation"
                )
                return false
            }

            val pushConfig = core.pushNotificationConfig
            if (pushConfig == null) {
                Log.w(
                    "$TAG Core's push notifications configuration is null, disable account creation"
                )
                return false
            }

            if (pushConfig.provider.isNullOrEmpty()) {
                Log.w(
                    "$TAG Core's push notifications configuration provider is null or empty, disable account creation"
                )
                return false
            }
            if (pushConfig.param.isNullOrEmpty()) {
                Log.w(
                    "$TAG Core's push notifications configuration param is null or empty, disable account creation"
                )
                return false
            }
            if (pushConfig.prid.isNullOrEmpty()) {
                Log.w(
                    "$TAG Core's push notifications configuration prid is null or empty, disable account creation"
                )
                return false
            }

            Log.i("$TAG Push notifications seems to be available")
            return true
        }

        @WorkerThread
        fun isCallLogMissed(callLog: CallLog): Boolean {
            if (callLog.dir == Dir.Outgoing) return false
            return callLog.status == Status.Missed ||
                callLog.status == Status.Aborted ||
                callLog.status == Status.EarlyAborted
        }

        @AnyThread
        @IntegerRes
        fun getCallIconResId(callStatus: Status, callDir: Dir): Int {
            return when (callStatus) {
                Status.Missed -> {
                    if (callDir == Dir.Outgoing) {
                        R.drawable.outgoing_call_missed
                    } else {
                        R.drawable.incoming_call_missed
                    }
                }

                Status.Success -> {
                    if (callDir == Dir.Outgoing) {
                        R.drawable.outgoing_call
                    } else {
                        R.drawable.incoming_call
                    }
                }

                else -> {
                    if (callDir == Dir.Outgoing) {
                        R.drawable.outgoing_call_rejected
                    } else {
                        R.drawable.incoming_call_rejected
                    }
                }
            }
        }

        @AnyThread
        @DrawableRes
        fun getChatIconResId(chatState: ChatMessage.State): Int {
            return when (chatState) {
                ChatMessage.State.Displayed -> {
                    R.drawable.checks
                }
                // "DeliveredToUser" confirms that the recipient device got the message,
                // not that the recipient opened the conversation.  Keep it a single tick;
                // only an IMDN "displayed" receipt is a read acknowledgement.
                ChatMessage.State.Delivered,
                ChatMessage.State.DeliveredToUser,
                ChatMessage.State.FileTransferDone -> {
                    R.drawable.check
                }
                ChatMessage.State.NotDelivered, ChatMessage.State.FileTransferError -> {
                    R.drawable.warning_circle
                }
                ChatMessage.State.InProgress, ChatMessage.State.FileTransferInProgress -> {
                    R.drawable.animated_in_progress
                }
                ChatMessage.State.PendingDelivery, ChatMessage.State.Queued -> {
                    R.drawable.hourglass
                }
                else -> {
                    R.drawable.animated_in_progress
                }
            }
        }

        @WorkerThread
        fun getComposingIconAndText(chatRoom: ChatRoom): Pair<Int, String> {
            val composing = chatRoom.isRemoteComposing
            if (!composing) {
                return Pair(0, "")
            }

            val icon = R.drawable.chat_teardrop_dots
            val participantNames = chatRoom.composingParticipants.map { participant ->
                val address = participant.address
                val avatar = coreContext.contactsManager.getContactAvatarModelForAddress(address)
                val name = avatar.name.value?.takeIf { it.isNotBlank() } ?: getDisplayName(address)
                name
            }
            val isOneToOne = chatRoom.hasCapability(ChatRoom.Capabilities.OneToOne.toInt())
            val peerName = if (isOneToOne) {
                val address = chatRoom.peerAddress
                val avatar = coreContext.contactsManager.getContactAvatarModelForAddress(address)
                avatar.name.value?.takeIf { it.isNotBlank() } ?: getDisplayName(address)
            } else {
                null
            }

            val composingNames = resolveComposingNames(participantNames, isOneToOne, peerName)
            if (composingNames.isEmpty()) return Pair(icon, "")

            // TODO: use voice recording content type to change icon/text
            val label = AppUtils.getStringWithPlural(
                R.plurals.conversation_composing_label,
                composingNames.size,
                composingNames.joinToString(", ")
            )
            return Pair(icon, label)
        }

        @AnyThread
        fun formatEphemeralExpiration(duration: Long): String {
            return when (duration) {
                0L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_disabled
                )
                60L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_one_minute
                )
                3600L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_one_hour
                )
                86400L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_one_day
                )
                259200L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_three_days
                )
                604800L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_one_week
                )
                else -> "$duration s"
            }
        }

        @WorkerThread
        fun getConversationId(chatRoom: ChatRoom): String {
            if (chatRoom.hasCapability(ChatRoom.Capabilities.Basic.toInt())) {
                // FreeSWITCH and the SDK can expose the same Basic SIP peer both with
                // and without the default :5060 port.  A persisted conversation ID must
                // not depend on that transport detail, otherwise opening a chat can look
                // for a different native room and create duplicates.
                return "${basicChatConversationAddress(chatRoom.peerAddress)}##" +
                    basicChatConversationAddress(chatRoom.localAddress)
            }
            return chatRoom.identifier ?: ""
        }

        /** Only SIP's default port is equivalent to a port-less Basic chat URI. */
        @WorkerThread
        private fun basicChatConversationAddress(address: Address): String {
            return if (address.port == 5060) {
                getAddressAsCleanStringUriOnly(address)
            } else {
                address.asStringUriOnly()
            }
        }

        /**
         * Resolves the identifier persisted by the chat UI to the room owned by the Core.
         *
         * Basic SIP rooms don't have a server-issued identifier.  Depending on whether the
         * proxy supplied its default port, the same room can be represented with either no
         * port or :5060.  Using searchChatRoomByIdentifier() for that composite identifier
         * makes the native database look for a conference room that doesn't exist.
         */
        @WorkerThread
        fun findChatRoomByConversationId(core: Core, conversationId: String): ChatRoom? {
            core.chatRooms.firstOrNull { getConversationId(it) == conversationId }?.let { return it }

            val addresses = conversationId.split("##", limit = 2)
            if (addresses.size == 2) {
                val peer = Factory.instance().createAddress(addresses[0])
                val local = Factory.instance().createAddress(addresses[1])
                if (peer != null && local != null) {
                    core.chatRooms.firstOrNull { room ->
                        room.hasCapability(ChatRoom.Capabilities.Basic.toInt()) &&
                            basicChatAddressesMatch(room.peerAddress, peer) &&
                            basicChatAddressesMatch(room.localAddress, local)
                    }?.let { return it }

                    val localUser = normalizeOneToOneUserPart(local.username.orEmpty())
                    val peerUser = normalizeOneToOneUserPart(peer.username.orEmpty())
                    if (localUser.isNotBlank() && peerUser.isNotBlank()) {
                        val targetPeerKey = "$localUser::$peerUser"
                        core.chatRooms.firstOrNull { room ->
                            room.hasCapability(ChatRoom.Capabilities.Basic.toInt()) &&
                                getOneToOnePeerKey(room) == targetPeerKey
                        }?.let { return it }
                    }

                    // Empty Basic rooms are intentionally hidden from core.chatRooms until
                    // their first message is sent. A contact can therefore create a room and
                    // immediately navigate to it, while the list lookup above still cannot
                    // see it. Ask the Core for that exact Basic room before declaring it gone.
                    val account = core.accountList.firstOrNull { candidate ->
                        candidate.params.identityAddress?.let { identity ->
                            basicChatAddressesMatch(identity, local)
                        } == true
                    }
                    if (account != null) {
                        val params = core.createConferenceParams(null).apply {
                            isChatEnabled = true
                            isGroupEnabled = false
                            this.account = account
                            securityLevel = Conference.SecurityLevel.None
                        }
                        params.chatParams?.apply {
                            backend = ChatRoom.Backend.Basic
                            ephemeralLifetime = 0
                        }
                        val canonicalPeer = canonicalizeBasicChatAddress(peer, account)
                        val peerCandidates = listOf(canonicalPeer, peer).distinctBy {
                            it.asStringUriOnly()
                        }
                        val localCandidates = listOfNotNull(
                            local,
                            account.params.identityAddress
                        ).distinctBy { it.asStringUriOnly() }
                        for (localCandidate in localCandidates) {
                            for (peerCandidate in peerCandidates) {
                                core.searchChatRoom(
                                    params,
                                    localCandidate,
                                    null,
                                    arrayOf(peerCandidate)
                                )?.let { return it }
                            }
                        }
                    }
                }

                // This is a Basic-room composite identifier, not a conference identifier.
                // Passing it to the native conference lookup emits an error and can leave the
                // database unable to store the outgoing message event.
                return null
            }

            // Conference/Flexisip rooms use a server-issued identifier.
            return core.searchChatRoomByIdentifier(conversationId)
        }

        @WorkerThread
        fun conversationIdMatchesChatRoom(conversationId: String, chatRoom: ChatRoom): Boolean {
            if (conversationId == getConversationId(chatRoom)) return true
            if (!chatRoom.hasCapability(ChatRoom.Capabilities.Basic.toInt())) return false

            val addresses = conversationId.split("##", limit = 2)
            if (addresses.size != 2) return false
            val peer = Factory.instance().createAddress(addresses[0]) ?: return false
            val local = Factory.instance().createAddress(addresses[1]) ?: return false
            return basicChatAddressesMatch(chatRoom.peerAddress, peer) &&
                basicChatAddressesMatch(chatRoom.localAddress, local)
        }

        @WorkerThread
        private fun basicChatAddressesMatch(first: Address, second: Address): Boolean {
            if (!first.username.equals(second.username, ignoreCase = true)) return false
            if (!first.domain.equals(second.domain, ignoreCase = true)) return false

            // A missing port and SIP's standard 5060 port identify the same Basic room.
            return first.port == second.port ||
                (first.port in arrayOf(0, 5060) && second.port in arrayOf(0, 5060))
        }

        @WorkerThread
        fun isChatRoomAGroup(chatRoom: ChatRoom): Boolean {
            val oneToOne = chatRoom.hasCapability(ChatRoom.Capabilities.OneToOne.toInt())
            val conference = chatRoom.hasCapability(ChatRoom.Capabilities.Conference.toInt())
            return !oneToOne && conference
        }

        private fun normalizeOneToOneUserPart(raw: String): String {
            val trimmed = raw.trim().lowercase()
            if (trimmed.isEmpty() || trimmed.any(Char::isLetter)) return trimmed
            val digits = PhoneContactNumbers.normalize(trimmed)
            return if (digits.length >= 6) digits else trimmed
        }

        @WorkerThread
        fun getOneToOnePeerKey(chatRoom: ChatRoom): String? {
            if (isChatRoomAGroup(chatRoom)) return null

            val localAddress = chatRoom.localAddress
            val peerAddress = chatRoom.participants.firstOrNull()?.address ?: chatRoom.peerAddress
            val localRaw = localAddress.username?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: chatRoom.account?.params?.identityAddress?.username?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: getAddressAsCleanStringUriOnly(localAddress).lowercase()
            val peerRaw = peerAddress.username?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: getAddressAsCleanStringUriOnly(peerAddress).lowercase()
            val localPart = normalizeOneToOneUserPart(localRaw)
            val peerPart = normalizeOneToOneUserPart(peerRaw)
            if (localPart.isBlank() || peerPart.isBlank()) return null

            return "$localPart::$peerPart"
        }

        @WorkerThread
        fun findSiblingOneToOneChatRooms(core: Core, chatRoom: ChatRoom): List<ChatRoom> {
            if (!chatRoom.hasCapability(ChatRoom.Capabilities.Basic.toInt())) {
                return listOf(chatRoom)
            }
            val targetPeerKey = getOneToOnePeerKey(chatRoom) ?: return listOf(chatRoom)
            val targetAccount = chatRoom.account
            val siblings = core.chatRooms.filter { candidate ->
                candidate == chatRoom || (
                    candidate.hasCapability(ChatRoom.Capabilities.Basic.toInt()) &&
                        (targetAccount == null || candidate.account == null || candidate.account == targetAccount) &&
                        getOneToOnePeerKey(candidate) == targetPeerKey
                    )
            }
            return if (chatRoom in siblings) siblings else listOf(chatRoom) + siblings
        }

        @WorkerThread
        fun areSameOneToOnePeer(first: ChatRoom, second: ChatRoom): Boolean {
            if (first == second) return true
            if (
                !first.hasCapability(ChatRoom.Capabilities.Basic.toInt()) ||
                !second.hasCapability(ChatRoom.Capabilities.Basic.toInt())
            ) {
                return false
            }
            if (first.account != null && second.account != null && first.account != second.account) {
                return false
            }
            val firstKey = getOneToOnePeerKey(first) ?: return false
            return firstKey == getOneToOnePeerKey(second)
        }

        @WorkerThread
        fun isDisplayableChatMessage(message: ChatMessage?): Boolean {
            if (message == null || CustomImdn.isHiddenControlMessage(message)) return false
            if (message.isRetracted) return true
            val directVisibleText = stripMessageEnvelopes(message.utf8Text.orEmpty())
            if (directVisibleText.isNotBlank() && !isFileTransferMetadata(directVisibleText)) {
                return true
            }
            if (com.naminfo.core.OfflineInboxRepository.queuedMedia(message).isNotEmpty()) return true
            if (message.contents.any {
                    it.isIcalendar || it.isVoiceRecording || it.isFile || it.isFileTransfer
                }
            ) {
                return true
            }
            val rawText = message.contents.firstOrNull { it.isText }?.utf8Text.orEmpty()
            val visibleText = stripMessageEnvelopes(rawText)
            if (visibleText.isNotBlank() && !isFileTransferMetadata(visibleText)) {
                return true
            }
            val replyHeader = com.naminfo.ui.main.chat.decodeReplyPreviewHeader(
                message.getCustomHeader(com.naminfo.ui.main.chat.DIYA_REPLY_MESSAGE_ID_HEADER)
                    ?: message.replyMessageId,
                message.getCustomHeader(com.naminfo.ui.main.chat.DIYA_REPLY_PREVIEW_HEADER)
            )
            return replyHeader?.second.orEmpty().isNotBlank()
        }

        @WorkerThread
        fun hasVisibleOrRecentMessages(chatRoom: ChatRoom): Boolean {
            if (chatRoom.unreadMessagesCount > 0) return true
            val lastMsg = chatRoom.lastMessageInHistory
            if (isDisplayableChatMessage(lastMsg)) return true
            if (chatRoom.unreadHistory.any { isDisplayableChatMessage(it) }) return true
            val history = chatRoom.getHistoryMessageEvents(0)
            if (history.asSequence()
                .mapNotNull { it.chatMessage }
                .any { isDisplayableChatMessage(it) }
            ) {
                return true
            }

            // A Basic-SIP build can accept a SIP MESSAGE but fail to expose its local history
            // row after a reload (storage id -1). Such a room still has a last-update time and
            // is a genuine recent peer chat; a receipt-only room has only hidden control rows.
            val hasAnyHistory = history.isNotEmpty()
            return !hasAnyHistory && chatRoom.lastUpdateTime > 0L
        }

        /**
         * Returns the chronologically latest non-control chat message across [rooms], ordered by
         * sender/original timestamp rather than SQLite insertion order so out-of-order SIP replay
         * or offline queue injection cannot regress conversation previews.
         */
        @WorkerThread
        fun findLatestVisibleMessage(rooms: Iterable<ChatRoom>): ChatMessage? {
            var best: ChatMessage? = null
            var bestTimestampMs = Long.MIN_VALUE

            fun consider(candidate: ChatMessage?) {
                if (candidate == null || !isDisplayableChatMessage(candidate)) return
                val ts = CustomImdn.timestampMillis(candidate)
                if (best == null || ts >= bestTimestampMs) {
                    best = candidate
                    bestTimestampMs = ts
                }
            }
            for (room in rooms) {
                for (event in room.getHistoryMessageEvents(0).orEmpty()) {
                    consider(event.chatMessage)
                }
                for (unread in room.unreadHistory.orEmpty()) {
                    consider(unread)
                }
                consider(room.lastMessageInHistory)
            }
            return best
        }

        @WorkerThread
        fun getRecordingFilePathForAddress(address: Address): String {
            val extension = if (corePreferences.callRecordingUseSmffFormat) {
                RECORDING_SMFF_FILE_EXTENSION
            } else {
                RECORDING_MKV_FILE_EXTENSION
            }
            Log.i("$TAG Using [$extension] file format for call recording")
            val fileName = "${RECORDING_FILE_NAME_HEADER}${address.asStringUriOnly()}${RECORDING_FILE_NAME_URI_TIMESTAMP_SEPARATOR}${System.currentTimeMillis()}$extension"
            return FileUtils.getFileStoragePath(fileName, isRecording = true).absolutePath
        }

        @WorkerThread
        fun callStateToString(state: Call.State): String {
            return when (state) {
                Call.State.IncomingEarlyMedia, Call.State.IncomingReceived -> {
                    AppUtils.getString(R.string.call_state_incoming_received)
                }
                Call.State.OutgoingInit, Call.State.OutgoingProgress -> {
                    AppUtils.getString(R.string.call_state_outgoing_progress)
                }
                Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                    AppUtils.getString(R.string.call_state_outgoing_ringing)
                }
                Call.State.Connected, Call.State.StreamsRunning, Call.State.Updating, Call.State.UpdatedByRemote -> {
                    AppUtils.getString(R.string.call_state_connected)
                }
                Call.State.Pausing, Call.State.Paused, Call.State.PausedByRemote -> {
                    AppUtils.getString(R.string.call_state_paused)
                }
                Call.State.Resuming -> {
                    AppUtils.getString(R.string.call_state_resuming)
                }
                Call.State.End, Call.State.Released, Call.State.Error -> {
                    AppUtils.getString(R.string.call_state_ended)
                }
                else -> {
                    // TODO: handle other states?
                    ""
                }
            }
        }

        @WorkerThread
        fun getFormattedTextDescribingMessage(message: ChatMessage): Spannable {
            val pair = getTextDescribingMessage(message)
            val builder = SpannableStringBuilder(
                "${pair.first} ${pair.second}".trim()
            )
            if (pair.first.isNotEmpty()) { // prevent error log due to zero length exclusive span
                builder.setSpan(
                    StyleSpan(Typeface.ITALIC),
                    0,
                    pair.first.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return builder.toSpannable()
        }

        private val hftFileNamePattern = Regex("<file-name>\\s*([^<]+?)\\s*</file-name>", RegexOption.IGNORE_CASE)
        private val jsonFileNamePattern = Regex("\"file(?:name|_name)?\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

        fun isFileTransferMetadata(value: String): Boolean {
            if (value.isBlank()) return false
            val lower = value.lowercase()
            return lower.contains("rcs:fthttp") ||
                lower.contains("<file") ||
                lower.contains("<file-name>") ||
                lower.contains("server response:") ||
                (lower.contains("{") && (lower.contains("\"url\"") || lower.contains("\"filename\"") || lower.contains("\"file_name\""))) ||
                (lower.contains("<?xml") && lower.contains("file"))
        }

        fun extractFileNameFromPayload(value: String): String? {
            if (value.isBlank()) return null
            hftFileNamePattern.find(value)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            jsonFileNamePattern.find(value)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            return null
        }

        /**
         * Strips DiyaOne's internal reply and forward transport envelopes (including nested
         * combinations) while leaving ordinary user text untouched.
         */
        fun stripMessageEnvelopes(messageText: String): String {
            var current = messageText
            var strippedAny = false
            repeat(4) {
                val trimmed = current.trim()
                val next = decodeForwardEnvelope(trimmed)
                    ?: decodeReplyPreviewEnvelope(trimmed)?.second
                    ?: return if (strippedAny) trimmed else current
                if (next == current) return trimmed
                current = next
                strippedAny = true
            }
            return if (strippedAny) current.trim() else current
        }

        @WorkerThread
        fun getPlainTextDescribingMessage(message: ChatMessage): String {
            val pair = getTextDescribingMessage(message)
            return "${pair.first} ${pair.second}".trim()
        }

        @WorkerThread
        private fun getTextDescribingMessage(message: ChatMessage): Pair<String, String> {
            // Check if message is empty (when deleted by its sender, for everyone)
            if (message.isRetracted) {
                val text = if (message.isOutgoing) {
                    AppUtils.getString(R.string.conversation_message_content_deleted_by_us_label)
                } else {
                    AppUtils.getString(R.string.conversation_message_content_deleted_label)
                }
                return Pair(text, "")
            }

            // If message contains text, then use that
            var text = message.contents.find { content -> content.isText }?.utf8Text.orEmpty()
            // DiyaOne's reply and forward fallback envelopes are transport metadata. The same
            // description helper feeds the reply composer, conversation list, and quote bubble,
            // so strip them here before any UI can display internal prefixes or Base64 payloads.
            text = stripMessageEnvelopes(text)
            if (isFileTransferMetadata(text)) {
                text = ""
            }
            var contentDescription = ""

            if (text.isEmpty()) {
                // HFT messages carry two representations of the same attachment: the
                // XML/JSON transport payload (text) and the actual file Content.  Once
                // the latter is available, do not derive a second name from the former
                // or conversation previews become "image.jpg, image.jpg".
                val hasNamedNonTextContent = message.contents.any {
                    !it.isText && it.name.orEmpty().isNotEmpty()
                }
                val firstContent = message.contents.firstOrNull()
                if (firstContent?.isIcalendar == true) {
                    val conferenceInfo = Factory.instance().createConferenceInfoFromIcalendarContent(
                        firstContent
                    )
                    if (conferenceInfo != null) {
                        text = conferenceInfo.subject.orEmpty()
                        contentDescription = when (conferenceInfo.state) {
                            ConferenceInfo.State.Cancelled -> {
                                AppUtils.getString(
                                    R.string.message_meeting_invitation_cancelled_content_description
                                )
                            }
                            ConferenceInfo.State.Updated -> {
                                AppUtils.getString(
                                    R.string.message_meeting_invitation_updated_content_description
                                )
                            }
                            else -> {
                                AppUtils.getString(
                                    R.string.message_meeting_invitation_content_description
                                )
                            }
                        }
                    } else {
                        Log.e(
                            "$TAG Failed to parse content with iCalendar content type as conference info!"
                        )
                        text = firstContent.name.orEmpty()
                    }
                } else if (firstContent?.isVoiceRecording == true) {
                    val label = AppUtils.getString(
                        R.string.message_voice_message_content_description
                    )
                    val formattedDuration = VoiceDurationUtils.format(firstContent.fileDuration)
                    contentDescription = "$label ($formattedDuration)"
                } else {
                    for (content in message.contents) {
                        if (content.isText && hasNamedNonTextContent) {
                            continue
                        }
                        if (text.isNotEmpty()) {
                            text += ", "
                        }
                        var name = content.name.orEmpty()
                        if (name.isEmpty()) {
                            val rawPayload = message.contents.find { it.isText }?.utf8Text.orEmpty()
                            name = extractFileNameFromPayload(rawPayload).orEmpty()
                        }
                        if (name.isNotEmpty()) {
                            text += name
                        }
                    }
                    if (text.isEmpty()) {
                        val queuedNames = com.naminfo.core.OfflineInboxRepository
                            .queuedMedia(message)
                            .mapNotNull { it.name.takeIf { name -> name.isNotBlank() } }
                        if (queuedNames.isNotEmpty()) {
                            text = queuedNames.joinToString(", ")
                        }
                    }
                }
            }

            return Pair(contentDescription, text)
        }

        @WorkerThread
        fun chatRoomConfigureEphemeralMessagesLifetime(chatRoom: ChatRoom, lifetime: Long) {
            if (lifetime == 0L) {
                if (chatRoom.isEphemeralEnabled) {
                    Log.i("$TAG Disabling ephemeral messages")
                    chatRoom.isEphemeralEnabled = false
                }
            } else {
                if (!chatRoom.isEphemeralEnabled) {
                    Log.i("$TAG Enabling ephemeral messages")
                    chatRoom.isEphemeralEnabled = true
                }

                if (chatRoom.ephemeralLifetime != lifetime) {
                    Log.i("$TAG Updating lifetime to [$lifetime]")
                    chatRoom.ephemeralLifetime = lifetime
                }
            }
            Log.i(
                "$TAG Ephemeral messages are [${if (chatRoom.isEphemeralEnabled) "enabled" else "disabled"}], lifetime is [${chatRoom.ephemeralLifetime}]"
            )
        }

        @WorkerThread
        fun getAvatarModelForConferenceInfo(conferenceInfo: ConferenceInfo): ContactAvatarModel {
            val fakeFriend = coreContext.core.createFriend()
            fakeFriend.address = conferenceInfo.uri
            fakeFriend.name = conferenceInfo.subject

            val avatarModel = ContactAvatarModel(fakeFriend)
            avatarModel.defaultToConferenceIcon.postValue(true)
            avatarModel.skipInitials.postValue(true)
            avatarModel.showTrust.postValue(false)

            return avatarModel
        }

        @WorkerThread
        fun getAudioDeviceName(device: AudioDevice): String {
            return when (device.type) {
                AudioDevice.Type.Microphone -> {
                    AppUtils.getString(R.string.call_audio_device_type_microphone)
                }
                AudioDevice.Type.Earpiece -> {
                    AppUtils.getString(R.string.call_audio_device_type_earpiece)
                }
                AudioDevice.Type.Speaker -> {
                    AppUtils.getString(R.string.call_audio_device_type_speaker)
                }
                AudioDevice.Type.Headset -> {
                    AppUtils.getString(R.string.call_audio_device_type_headset)
                }
                AudioDevice.Type.Headphones -> {
                    AppUtils.getString(R.string.call_audio_device_type_headphones)
                }
                AudioDevice.Type.Bluetooth -> {
                    AppUtils.getFormattedString(
                        R.string.call_audio_device_type_bluetooth,
                        device.deviceName
                    )
                }
                AudioDevice.Type.HearingAid -> {
                    AppUtils.getFormattedString(
                        R.string.call_audio_device_type_hearing_aid,
                        device.deviceName
                    )
                }
                AudioDevice.Type.Hdmi -> {
                    AppUtils.getString(R.string.call_audio_device_type_hdmi)
                }
                else -> "${device.deviceName}(${device.type})"
            }
        }
    }
}
