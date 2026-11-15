package com.naminfo.core

import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.contacts.MobionContactsService
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.RegistrationState
import org.linphone.core.tools.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ends the local SIP session when the server can no longer refresh an API session.
 *
 * A refresh-token HTTP 401 is terminal: retaining a registered SIP account would leave
 * the user in a screen that cannot call authenticated APIs. We still send UNREGISTER
 * first, then remove the local account after its confirmation (or after a short fallback).
 */
object MobionSessionExpiryHandler {
    private const val TAG = "[Mobion Session Expiry]"
    private const val UNREGISTER_FALLBACK_DELAY_MS = 8_000L
    private val expiring = AtomicBoolean(false)

    fun resetForNewSession() {
        expiring.set(false)
    }

    fun expireAfterRefreshUnauthorized() {
        if (!expiring.compareAndSet(false, true)) return

        Log.w("$TAG Refresh token was rejected; ending the local SIP session")
        clearApiSession()
        MobionContactsService.clearContactsCache()

        coreContext.postOnCoreThread { core ->
            val target = core.defaultAccount ?: core.accountList.firstOrNull()
            if (target == null) {
                Log.w("$TAG No SIP account remains after refresh-token rejection")
                return@postOnCoreThread
            }

            val authInfo = target.findAuthInfo()
            var completed = false
            lateinit var listener: CoreListenerStub

            fun removeLocally(reason: String) {
                if (completed) return
                completed = true
                core.removeListener(listener)
                try {
                    saveProfileAndRemoveAccount(core, target, authInfo)
                    Log.w("$TAG Local SIP account removed ($reason)")
                } catch (error: Exception) {
                    Log.e("$TAG Couldn't remove expired SIP account: $error")
                }
            }

            listener = object : CoreListenerStub() {
                override fun onAccountRegistrationStateChanged(
                    core: Core,
                    account: Account,
                    state: RegistrationState?,
                    message: String
                ) {
                    if (account != target || completed) return
                    when (state) {
                        RegistrationState.Cleared -> removeLocally("UNREGISTER confirmed")
                        RegistrationState.Failed -> removeLocally("UNREGISTER failed: $message")
                        else -> Unit
                    }
                }
            }
            core.addListener(listener)

            try {
                if (target.state == RegistrationState.Cleared || !core.isNetworkReachable) {
                    removeLocally("network unavailable or already unregistered")
                } else {
                    val params = target.params.clone()
                    params.isRegisterEnabled = false
                    params.isPublishEnabled = false
                    target.params = params
                    core.config.sync()
                    Log.i("$TAG UNREGISTER requested before returning to sign-in")

                    coreContext.postOnCoreThreadDelayed({
                        removeLocally("UNREGISTER timeout")
                    }, UNREGISTER_FALLBACK_DELAY_MS)
                }
            } catch (error: Exception) {
                removeLocally("UNREGISTER exception: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun clearApiSession() {
        corePreferences.authToken = null
        corePreferences.refreshToken = null
        corePreferences.accessTokenExpiresAt = null
        corePreferences.refreshTokenExpiresAt = null
        corePreferences.apiDomain = null
        corePreferences.loginTime = null
    }

    private fun saveProfileAndRemoveAccount(
        core: Core,
        account: Account,
        authInfo: org.linphone.core.AuthInfo?
    ) {
        if (core.accountList.none { it == account }) return

        val identity = account.params.identityAddress?.asStringUriOnly()
        val profileSection = "saved_profile_$identity"
        core.config.setString(profileSection, "display_name", account.params.identityAddress?.displayName)
        core.config.setString(profileSection, "picture_uri", account.params.pictureUri)
        core.removeAccount(account)

        if (authInfo != null && core.accountList.none { it.findAuthInfo() == authInfo }) {
            core.removeAuthInfo(authInfo)
        }
        if (core.accountList.isEmpty()) core.provisioningUri = null
        core.config.sync()
    }
}
