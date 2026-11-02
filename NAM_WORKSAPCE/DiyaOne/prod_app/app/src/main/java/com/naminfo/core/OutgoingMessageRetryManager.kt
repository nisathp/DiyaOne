package com.naminfo.core

import com.naminfo.DiyaOneApplication.Companion.coreContext
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.core.ChatMessage
import org.linphone.core.ChatMessageListenerStub
import org.linphone.core.tools.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Best-effort retry for outgoing SIP messages that ended in NotDelivered.
 *
 * A direct SIP MESSAGE has no offline mailbox in the client. Retrying here covers a temporary
 * network interruption and a recipient that comes back online while DiyaOne stays running. A
 * server-side store-and-forward queue is still required for guaranteed delivery after the app is
 * killed or after the retry window expires.
 */
object OutgoingMessageRetryManager {
    private const val TAG = "[Outgoing Message Retry]"
    private const val MAX_RETRIES = 8
    private val retryDelaysMs = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000, 180_000, 300_000, 300_000)

    private data class PendingMessage(
        val key: String,
        val message: ChatMessage,
        var attempts: Int = 0,
        var retryScheduled: Boolean = false,
        val listener: ChatMessageListenerStub
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, PendingMessage>()

    @WorkerThread
    fun watch(message: ChatMessage) {
        val key = CustomImdn.id(message)
            ?: message.messageId.takeIf { it.isNotBlank() }
            ?: "native-${System.identityHashCode(message)}"
        if (pending.containsKey(key)) return

        lateinit var entry: PendingMessage
        val listener = object : ChatMessageListenerStub() {
            @WorkerThread
            override fun onMsgStateChanged(
                changedMessage: ChatMessage,
                state: ChatMessage.State?
            ) {
                when (state) {
                    ChatMessage.State.Delivered,
                    ChatMessage.State.DeliveredToUser,
                    ChatMessage.State.Displayed -> complete(entry)

                    ChatMessage.State.NotDelivered,
                    ChatMessage.State.FileTransferError -> scheduleRetry(entry)

                    else -> Unit
                }
            }
        }
        entry = PendingMessage(key, message, listener = listener)
        pending[key] = entry
        message.addListener(listener)
    }

    /** Call after SIP registration returns so queued failures retry immediately on reconnect. */
    @WorkerThread
    fun onSipRegistrationRestored() {
        pending.values.forEach { entry ->
            if (isRetryable(entry.message.state)) {
                retry(entry, immediate = true)
            }
        }
    }

    @WorkerThread
    private fun scheduleRetry(entry: PendingMessage) {
        if (entry.attempts >= MAX_RETRIES || entry.retryScheduled) return
        entry.retryScheduled = true
        val delayMs = retryDelaysMs[entry.attempts]
        scope.launch {
            delay(delayMs)
            coreContext.postOnCoreThread {
                retry(entry, immediate = false)
            }
        }
    }

    @WorkerThread
    private fun retry(entry: PendingMessage, immediate: Boolean) {
        if (pending[entry.key] !== entry || entry.attempts >= MAX_RETRIES) return
        entry.retryScheduled = false
        if (!isRetryable(entry.message.state)) return

        val core = coreContext.core
        if (!core.isNetworkReachable) {
            scheduleRetry(entry)
            return
        }

        entry.attempts += 1
        Log.i("$TAG Retrying outgoing message (attempt ${entry.attempts}/$MAX_RETRIES, immediate=$immediate)")
        CustomImdn.prepareOutgoing(entry.message)
        entry.message.send()
    }

    private fun isRetryable(state: ChatMessage.State?): Boolean =
        state == ChatMessage.State.NotDelivered || state == ChatMessage.State.FileTransferError

    @WorkerThread
    private fun complete(entry: PendingMessage) {
        if (pending.remove(entry.key, entry)) {
            entry.message.removeListener(entry.listener)
        }
    }
}
