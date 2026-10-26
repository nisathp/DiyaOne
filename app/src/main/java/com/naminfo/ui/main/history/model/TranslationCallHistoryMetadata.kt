package com.naminfo.ui.main.history.model

import android.content.Context

/** Persists the incoming INVITE's TCall marker because Linphone CallLog doesn't retain SIP headers. */
object TranslationCallHistoryMetadata {
    private const val PREFERENCES_NAME = "translation_call_history"
    private const val KEY_PREFIX = "tcall_"

    fun markTranslationCall(context: Context, callId: String?) {
        if (callId.isNullOrBlank()) return

        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("$KEY_PREFIX$callId", true)
            .apply()
    }

    fun isTranslationCall(context: Context, callId: String?): Boolean {
        if (callId.isNullOrBlank()) return false

        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean("$KEY_PREFIX$callId", false)
    }
}
