package com.naminfo.notifications

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.naminfo.DiyaOneApplication.Companion.coreContext
import org.linphone.core.Address
import org.linphone.core.AudioDevice
import org.linphone.core.ConferenceParams
import org.linphone.core.tools.Log
import com.naminfo.telecom.RedirectionHandler
import com.naminfo.utils.AudioUtils

class NotificationBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "[Notification Broadcast Receiver]"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val notificationId = intent.getIntExtra(NotificationsManager.INTENT_NOTIF_ID, 0)
        val action = intent.action
        Log.i("$TAG Got notification broadcast for ID [$notificationId] with action [$action]")

        if (
            action == NotificationsManager.INTENT_HANGUP_CALL_NOTIF_ACTION ||
            action == NotificationsManager.INTENT_TOGGLE_SPEAKER_CALL_NOTIF_ACTION
        ) {
            handleCallIntent(intent, notificationId, action, pendingResult)
        } else if (
            action == NotificationsManager.INTENT_REPLY_MESSAGE_NOTIF_ACTION ||
            action == NotificationsManager.INTENT_MARK_MESSAGE_AS_READ_NOTIF_ACTION ||
            action == NotificationsManager.INTENT_TOGGLE_TRANSLATION_NOTIF_ACTION
        ) {
            handleChatIntent(context, intent, notificationId, action, pendingResult)
        } else if (
            action == NotificationsManager.INTENT_DENY_CALL_REDIRECTION_ACTION
        ) {
            handleCallRedirectionIntent(action)
            pendingResult.finish()
        } else {
            pendingResult.finish()
        }
    }

    private fun handleCallIntent(intent: Intent, notificationId: Int, action: String, pendingResult: BroadcastReceiver.PendingResult? = null) {
        val remoteSipUri = intent.getStringExtra(NotificationsManager.INTENT_REMOTE_SIP_URI)
        if (remoteSipUri == null) {
            Log.e("$TAG Remote SIP address is null for call notification ID [$notificationId]")
            pendingResult?.finish()
            return
        }

        coreContext.postOnCoreThread { core ->
            try {
                val call = core.calls.find {
                    it.remoteAddress.asStringUriOnly() == remoteSipUri
                }
                if (call == null) {
                    Log.e("$TAG Couldn't find call from remote address [$remoteSipUri]")
                } else {
                    when (action) {

                        NotificationsManager.INTENT_HANGUP_CALL_NOTIF_ACTION -> {
                            Log.i("$TAG Declining/terminating call with remote address [$remoteSipUri]")
                            coreContext.terminateCall(call)
                        }
                        NotificationsManager.INTENT_TOGGLE_SPEAKER_CALL_NOTIF_ACTION -> {
                            val audioDevice = call.outputAudioDevice
                            val isUsingSpeaker = audioDevice?.type == AudioDevice.Type.Speaker
                            if (isUsingSpeaker) {
                                Log.i("$TAG Routing audio to earpiece for call [$remoteSipUri]")
                                AudioUtils.routeAudioToEarpiece(call)
                            } else {
                                Log.i("$TAG Routing audio to speaker for call [$remoteSipUri]")
                                AudioUtils.routeAudioToSpeaker(call)
                            }
                        }
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun handleCallRedirectionIntent(action: String) {
        when (action) {
            NotificationsManager.INTENT_DENY_CALL_REDIRECTION_ACTION -> {
                Log.i("$TAG User declined GSM call redirection through Linphone")
                RedirectionHandler.useLinphone = false
                RedirectionHandler.responseLatch?.countDown()
            }
        }
    }

    private fun handleChatIntent(context: Context, intent: Intent, notificationId: Int, action: String, pendingResult: PendingResult? = null) {
        val remoteSipAddress = intent.getStringExtra(NotificationsManager.INTENT_REMOTE_SIP_URI)
        if (remoteSipAddress == null) {
            Log.e("$TAG Remote SIP address is null for notification ID [$notificationId]")
            pendingResult?.finish()
            return
        }
        val localIdentity = intent.getStringExtra(NotificationsManager.INTENT_LOCAL_IDENTITY)
        if (localIdentity == null) {
            Log.e("$TAG Local identity is null for notification ID [$notificationId]")
            pendingResult?.finish()
            return
        }

        val reply = getMessageText(intent)?.toString()
        if (action == NotificationsManager.INTENT_REPLY_MESSAGE_NOTIF_ACTION) {
            if (reply == null) {
                Log.e("$TAG Couldn't get reply text")
                pendingResult?.finish()
                return
            }
        }

        coreContext.postOnCoreThread { core ->
            try {
                val remoteAddress = core.interpretUrl(remoteSipAddress, false)
                if (remoteAddress == null) {
                    Log.e(
                        "$TAG Couldn't interpret remote address [$remoteSipAddress]"
                    )
                    return@postOnCoreThread
                }

                val localAddress = core.interpretUrl(localIdentity, false)
                if (localAddress == null) {
                    Log.e(
                        "$TAG Couldn't interpret local address [$localIdentity]"
                    )
                    return@postOnCoreThread
                }

                val params: ConferenceParams? = null
                val room = core.searchChatRoom(
                    params,
                    localAddress,
                    remoteAddress,
                    arrayOfNulls<Address>(
                        0
                    )
                )
                if (room == null) {
                    Log.e(
                        "$TAG Couldn't find conversation for remote address [$remoteSipAddress] and local address [$localIdentity]"
                    )
                    return@postOnCoreThread
                }

                if (action == NotificationsManager.INTENT_REPLY_MESSAGE_NOTIF_ACTION) {
                    val msg = room.createMessageFromUtf8(reply)
                    com.naminfo.core.CustomImdn.prepareOutgoing(msg)
                    com.naminfo.core.OutgoingMessageRetryManager.watch(msg)
                    msg.userData = notificationId
                    msg.addListener(coreContext.notificationsManager.chatMessageListener)
                    msg.send()
                    Log.i("$TAG Reply sent for notif id [$notificationId]")

                    val address = room.peerAddress.asStringUriOnly()
                    val notifiable = coreContext.notificationsManager.chatNotificationsMap[address]
                    if (notifiable != null) {
                        coreContext.notificationsManager.displayReplyMessageNotification(msg, notifiable)
                    }
                } else if (action == NotificationsManager.INTENT_MARK_MESSAGE_AS_READ_NOTIF_ACTION) {
                    Log.i("$TAG Marking chat room from notification id [$notificationId] as read")
                    com.naminfo.core.CustomImdn.prepareRoomRead(listOf(room))
                    room.markAsRead()
                    if (!coreContext.notificationsManager.dismissChatNotification(room)) {
                        Log.w(
                            "$TAG Notifications Manager failed to cancel notification"
                        )
                        val notificationManager = context.getSystemService(
                            NotificationManager::class.java
                        )
                        notificationManager.cancel(NotificationsManager.CHAT_TAG, notificationId)
                    }
                } else if (action == NotificationsManager.INTENT_TOGGLE_TRANSLATION_NOTIF_ACTION) {
                    Log.i("$TAG Toggling translation for notif id [$notificationId]")
                    coreContext.notificationsManager.toggleNotificationTranslation(room, notificationId)
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun getMessageText(intent: Intent): CharSequence? {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        return remoteInput?.getCharSequence(NotificationsManager.KEY_TEXT_REPLY)
    }
}
