package com.naminfo.core

import org.json.JSONObject
import org.linphone.core.ChatMessage
import org.linphone.core.ChatMessageListenerStub
import org.linphone.core.ChatRoom
import org.linphone.core.tools.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** App-to-app composing fallback for SIP servers that discard RFC 3994 messages. */
object CustomComposing {
    private const val PREFIX = "DIYA_COMPOSING_V1\n"
    // Four-second refresh interval plus three seconds of grace for a lost signal.
    private const val ACTIVE_TTL_SECONDS = 7L

    private data class ActiveSignal(val roomKey: String, val address: String)

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DiyaComposingExpiry").apply { isDaemon = true }
    }
    private val expiryTasks = ConcurrentHashMap<ActiveSignal, ScheduledFuture<*>>()
    private val activeSignals = ConcurrentHashMap.newKeySet<ActiveSignal>()
    private val observers = CopyOnWriteArraySet<(String, String, Boolean) -> Unit>()

    fun roomKey(room: ChatRoom): String =
        "${room.localAddress.asStringUriOnly()}|${room.peerAddress.asStringUriOnly()}"

    fun activeSenders(roomKey: String): List<String> =
        activeSignals.asSequence()
            .filter { it.roomKey == roomKey }
            .map { it.address }
            .sorted()
            .toList()

    fun addObserver(observer: (String, String, Boolean) -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: (String, String, Boolean) -> Unit) {
        observers.remove(observer)
    }

    fun isControlMessage(message: ChatMessage?): Boolean =
        message?.utf8Text?.startsWith(PREFIX) == true

    /** Returns true for control messages, including malformed ones, so they never enter chat history. */
    fun receive(room: ChatRoom, message: ChatMessage): Boolean {
        val body = message.utf8Text ?: return false
        if (!body.startsWith(PREFIX)) return false

        try {
            if (message.isOutgoing) return true
            val state = JSONObject(body.removePrefix(PREFIX)).optString("state")
            val address = message.fromAddress.asStringUriOnly()
            if (address.isNotBlank() && (state == "active" || state == "idle")) {
                update(roomKey(room), address, state == "active")
            }
        } catch (exception: Exception) {
            Log.w("[Custom composing] Ignoring invalid control message: ${exception.message}")
        } finally {
            room.deleteMessage(message)
        }
        return true
    }

    /** Send a small text/plain control message over the existing one-to-one chat room. */
    fun send(room: ChatRoom, isComposing: Boolean) {
        if (!room.hasCapability(ChatRoom.Capabilities.Basic.toInt()) ||
            room.hasCapability(ChatRoom.Capabilities.Encrypted.toInt())
        ) {
            return
        }

        val message = room.createMessageFromUtf8(
            PREFIX + JSONObject().put("state", if (isComposing) "active" else "idle").toString()
        )
        message.setToBeStored(false)
        message.addListener(object : ChatMessageListenerStub() {
            override fun onMsgStateChanged(message: ChatMessage, state: ChatMessage.State?) {
                if (state == ChatMessage.State.Delivered || state == ChatMessage.State.NotDelivered) {
                    message.removeListener(this)
                    message.chatRoom.deleteMessage(message)
                }
            }
        })
        message.send()
    }

    private fun update(roomKey: String, address: String, isComposing: Boolean) {
        val signal = ActiveSignal(roomKey, address)
        expiryTasks.remove(signal)?.cancel(false)
        if (isComposing) {
            activeSignals.add(signal)
            expiryTasks[signal] = executor.schedule({
                expiryTasks.remove(signal)
                if (activeSignals.remove(signal)) {
                    notifyObservers(roomKey, address, false)
                }
            }, ACTIVE_TTL_SECONDS, TimeUnit.SECONDS)
        } else {
            activeSignals.remove(signal)
        }
        notifyObservers(roomKey, address, isComposing)
    }

    private fun notifyObservers(roomKey: String, address: String, isComposing: Boolean) {
        observers.forEach { observer ->
            runCatching { observer(roomKey, address, isComposing) }
                .onFailure { Log.e("[Custom composing] Observer failed: ${it.message}") }
        }
    }
}
