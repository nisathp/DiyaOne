package com.naminfo.ui.main.chat

import java.util.concurrent.atomic.AtomicBoolean

internal class SingleFlightGate {
    private val active = AtomicBoolean(false)

    fun tryEnter(): Boolean = active.compareAndSet(false, true)

    fun complete() {
        active.set(false)
    }
}
