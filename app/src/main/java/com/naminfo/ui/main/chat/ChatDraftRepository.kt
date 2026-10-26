package com.naminfo.ui.main.chat

import com.naminfo.DiyaOneApplication.Companion.coreContext
import java.util.concurrent.CopyOnWriteArraySet

/** Stores unsent text locally and notifies conversation previews when it changes. */
object ChatDraftRepository {
    private const val PREFERENCES_NAME = "chat_drafts"
    private const val KEY_PREFIX = "draft_"

    private val observers = CopyOnWriteArraySet<(String, String) -> Unit>()

    fun get(conversationId: String): String {
        return preferences().getString(key(conversationId), "").orEmpty()
    }

    fun save(conversationId: String, text: String) {
        if (conversationId.isEmpty()) return
        val normalized = text
        val prefs = preferences()
        val previous = prefs.getString(key(conversationId), "").orEmpty()
        if (normalized.isBlank()) {
            prefs.edit().remove(key(conversationId)).apply()
        } else {
            prefs.edit().putString(key(conversationId), normalized).apply()
        }
        if (previous != normalized) observers.forEach { it(conversationId, normalized) }
    }

    fun addObserver(observer: (String, String) -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: (String, String) -> Unit) {
        observers.remove(observer)
    }

    private fun preferences() = coreContext.context.getSharedPreferences(PREFERENCES_NAME, 0)

    private fun key(conversationId: String) = KEY_PREFIX + conversationId
}
