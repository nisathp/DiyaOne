package com.naminfo.notifications

import com.google.firebase.messaging.RemoteMessage
import com.naminfo.DiyaOneApplication.Companion.coreContext
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

        Log.i(
            "$TAG FCM received at [${System.currentTimeMillis()}]. Type: [$type], Call-ID: [$callId], " +
                    "sentTime=[${remoteMessage.sentTime}], ttl=[${remoteMessage.ttl}], " +
                    "priority=[${remoteMessage.priority}], originalPriority=[${remoteMessage.originalPriority}]"
        )

        if (type == "WAKE_CALL" || type == "INCOMING_CALL") {
            Log.i("$TAG Received WAKE_CALL push for callId [$callId]. Setting pendingWakeCallId and refreshing registers")
            if (!callId.isNullOrEmpty() && !readyToken.isNullOrEmpty()) {
                // Show a user-visible alert before Core startup or SIP registration.
                // It is replaced by the normal actionable CallStyle notification once
                // FreeSWITCH delivers the matching SIP INVITE.
                coreContext.notificationsManager.showIncomingCallWakeNotification(callId, callerName)
                coreContext.pendingWakeCallId = callId
                coreContext.pendingWakeReadyToken = readyToken
            } else {
                Log.w("$TAG Incoming-call FCM is missing callId or ready-token")
            }
            coreContext.postOnCoreThread {
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
