package com.naminfo.notifications

import androidx.core.app.NotificationManagerCompat
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

        Log.i("$TAG FCM message received. Type: [$type], Call-ID: [$callId]")

        if (type == "WAKE_CALL" || type == "INCOMING_CALL") {
            Log.i("$TAG Received WAKE_CALL push for callId [$callId]. Setting pendingWakeCallId and refreshing registers")
            if (!callId.isNullOrEmpty() && !readyToken.isNullOrEmpty()) {
                coreContext.pendingWakeCallId = callId
                coreContext.pendingWakeReadyToken = readyToken
            }
            coreContext.postOnCoreThread {
                if (coreContext.isCoreAvailable() && coreContext.core.globalState == org.linphone.core.GlobalState.On) {
                    // A phone that was backgrounded may still be SIP-registered.
                    // In that case a refresh need not emit another RegistrationState.Ok
                    // callback. A refresh can also move the state out of Ok before it
                    // is checked. The Core is running and has queued register refresh;
                    // acknowledge that wake immediately so FreeSWITCH can start INVITE
                    // retries while the refreshed SIP registration completes.
                    coreContext.core.refreshRegisters()
                    coreContext.acknowledgePendingWakeCallAfterRegisterRefresh()
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
                    NotificationManagerCompat.from(applicationContext).cancelAll()
                } catch (e: Exception) {
                    Log.e("$TAG Exception while clearing notifications: $e")
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
