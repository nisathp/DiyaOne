package com.naminfo.notifications

import com.google.firebase.messaging.RemoteMessage
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.core.CustomImdn
import com.naminfo.core.OfflineInboxRepository
import org.linphone.core.Call
import org.linphone.core.tools.Log
import org.linphone.core.tools.firebase.FirebaseMessaging

class DiyaOneFirebaseMessagingService : FirebaseMessaging() {
    companion object {
        private const val TAG = "[DiyaOne Firebase Messaging]"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val type = data["type"]
        val callId = data["call-id"] ?: data["callId"]
        val readyToken = data["ready-token"]
        val callerName = data["callerName"]?.takeIf { it.isNotBlank() } ?: "Incoming call"
        val calleeNumber = data["calleeNumber"] ?: data["receiverNumber"] ?: data["recipient"] ?: data["toNumber"]

        Log.i(
            "$TAG FCM received at [${System.currentTimeMillis()}]. Type: [$type], Call-ID: [$callId], " +
                    "sentTime=[${remoteMessage.sentTime}], ttl=[${remoteMessage.ttl}], " +
                    "priority=[${remoteMessage.priority}], originalPriority=[${remoteMessage.originalPriority}]"
        )

        if (type == "WAKE_CALL" || type == "INCOMING_CALL") {
            if (callId.isNullOrEmpty() || readyToken.isNullOrEmpty() || calleeNumber.isNullOrEmpty()) {
                Log.w("$TAG Incoming-call FCM is missing callId or ready-token")
                return
            }
            coreContext.postOnCoreThread {
                val activeNumber = coreContext.core.defaultAccount?.params?.identityAddress?.username
                    .orEmpty().filter(Char::isDigit)
                val requestedNumber = calleeNumber.filter(Char::isDigit)
                if (activeNumber.isBlank() || requestedNumber != activeNumber) {
                    // An FCM registration token is device-scoped. If this phone has changed
                    // account, an old server token mapping must never wake or register the
                    // new account for a call addressed to the old one.
                    Log.w("$TAG Ignoring WAKE_CALL [$callId] for [$requestedNumber]; active account is [$activeNumber]")
                    return@postOnCoreThread
                }
                Log.i("$TAG Accepted WAKE_CALL [$callId] for active account [$activeNumber]")
                coreContext.notificationsManager.showIncomingCallWakeNotification(callId, callerName)
                coreContext.pendingWakeCallId = callId
                coreContext.pendingWakeReadyToken = readyToken
                coreContext.pendingWakeRegistrationCallId = callId
                if (coreContext.isCoreAvailable() && coreContext.core.globalState == org.linphone.core.GlobalState.On) {
                    Log.i("$TAG Refreshing SIP registration for call [$callId], beforeState=[${coreContext.core.defaultAccount?.state}]")
                    coreContext.core.refreshRegisters()
                    Log.i("$TAG Register refresh queued for call [$callId], afterState=[${coreContext.core.defaultAccount?.state}]")
                    // Do not ACK based on a possibly stale RegistrationState.Ok.
                    // The account-registration callback ACKs only after Linphone
                    // reports the registration attempt's result.
                } else {
                    Log.w("$TAG Core is not ready yet; pending call wake will be handled when Core starts")
                }
            }
        }

        if (type == "OFFLINE_QUEUE_AVAILABLE") {
            Log.i("$TAG Received offline-queue wake-up; pulling durable inbox")
            com.naminfo.DiyaOneApplication.coreContext.postOnCoreThread {
                OfflineInboxRepository.pullForCurrentAccount()
            }
        }

        if (type == "OFFLINE_QUEUE_DELIVERED") {
            val messageId = data["id"]
            val recipient = data["recipient"]
            Log.i("$TAG Received offline queue delivery acknowledgement for id [$messageId]")
            coreContext.postOnCoreThread {
                CustomImdn.markDeliveredFromOfflineQueue(messageId, recipient)
            }
        }

        if (type == "OFFLINE_QUEUE_READ" || type == "OFFLINE_QUEUE_DISPLAYED") {
            val messageId = data["id"]
            val recipient = data["recipient"]
            Log.i("$TAG Received offline queue read acknowledgement for id [$messageId]")
            coreContext.postOnCoreThread {
                CustomImdn.markDisplayedFromOfflineQueue(messageId, recipient)
            }
        }

        if (type == "OFFLINE_QUEUE_REPLAY") {
            val messageId = data["id"]
            val recipient = data["recipient"]
            Log.i("$TAG Received offline queue SIP replay request for id [$messageId]")
            coreContext.postOnCoreThread {
                OfflineInboxRepository.replayQueuedOutgoing(messageId, recipient)
            }
        }

        if (type == "CANCEL_CALL") {
            Log.i("$TAG Received CANCEL_CALL FCM push notification for callId [$callId]")
            coreContext.postOnCoreThread {
                val core = coreContext.core
                val calls = core.calls
                var terminated = false

                for (call in calls) {
                    if (callId.isNullOrEmpty() || call.callLog.callId == callId) {
                        if (call.state == Call.State.IncomingReceived || call.state == Call.State.IncomingEarlyMedia) {
                            Log.i("$TAG Terminating incoming call [${call.callLog.callId}] due to CANCEL_CALL push")
                            call.terminate()
                            terminated = true
                        }
                    }
                }

                if (!terminated && core.currentCall != null) {
                    val current = core.currentCall
                    if (current?.state == Call.State.IncomingReceived || current?.state == Call.State.IncomingEarlyMedia) {
                        Log.i("$TAG Terminating current call due to CANCEL_CALL push")
                        current.terminate()
                    }
                }

                try {
                    if (!callId.isNullOrEmpty()) {
                        coreContext.notificationsManager.cancelIncomingCallWakeNotification(callId)
                    }
                } catch (e: Exception) {
                    Log.e("$TAG Exception while clearing wake-call notification: $e")
                }
            }
            return
        }

        super.onMessageReceived(remoteMessage)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i("$TAG New FCM token received; syncing it with the backend")
        try {
            coreContext.fetchAndSyncFcmToken()
        } catch (e: Exception) {
            Log.e("$TAG Error syncing token onNewToken: $e")
        }
    }
}
