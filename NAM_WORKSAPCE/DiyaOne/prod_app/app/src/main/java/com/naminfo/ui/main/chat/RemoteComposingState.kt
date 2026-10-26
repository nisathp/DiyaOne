package com.naminfo.ui.main.chat

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class RemoteComposingState(private val onTimeout: () -> Unit) {
    companion object {
        // Allow one four-second refresh interval, then expire after three seconds of grace.
        private const val ACTIVE_TIMEOUT_SECONDS = 7L
        private val expiryExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "DiyaRemoteComposingExpiry").apply { isDaemon = true }
        }
    }

    private val participants = linkedMapOf<String, String>()
    private val expiryTasks = mutableMapOf<String, ScheduledFuture<*>>()
    private val expiryGenerations = mutableMapOf<String, Long>()
    private var nextGeneration = 0L

    private var hasReceivedUpdate = false

    @Synchronized
    fun update(address: String, displayName: String, isComposing: Boolean) {
        hasReceivedUpdate = true
        if (isComposing) {
            participants[address] = displayName.trim().ifEmpty { address }
            expiryTasks.remove(address)?.cancel(false)
            val generation = ++nextGeneration
            expiryGenerations[address] = generation
            expiryTasks[address] = expiryExecutor.schedule(
                { expire(address, generation) },
                ACTIVE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        } else {
            participants.remove(address)
            expiryTasks.remove(address)?.cancel(false)
            expiryGenerations.remove(address)
        }
    }

    @Synchronized
    fun participantNamesOrNull(): List<String>? =
        if (hasReceivedUpdate) participants.values.sorted() else null

    @Synchronized
    fun clear() {
        expiryTasks.values.forEach { it.cancel(false) }
        expiryTasks.clear()
        expiryGenerations.clear()
        participants.clear()
        hasReceivedUpdate = false
    }

    private fun expire(address: String, generation: Long) {
        val didExpire = synchronized(this) {
            if (expiryGenerations[address] != generation) {
                false
            } else {
                expiryTasks.remove(address)
                expiryGenerations.remove(address)
                participants.remove(address) != null
            }
        }
        if (didExpire) onTimeout()
    }
}
