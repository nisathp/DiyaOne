package com.naminfo.core

import android.content.Intent
import android.os.Bundle
import com.naminfo.ui.GenericActivity
import com.naminfo.ui.main.MainActivity

/**
 * Compatibility target for a notification posted by older app versions.
 *
 * The offline queue no longer displays raw server/HFT responses. If an old notification is
 * still present when the app is updated, opening it takes the user to normal chat instead.
 */
class OfflineInboxActivity : GenericActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }
}
