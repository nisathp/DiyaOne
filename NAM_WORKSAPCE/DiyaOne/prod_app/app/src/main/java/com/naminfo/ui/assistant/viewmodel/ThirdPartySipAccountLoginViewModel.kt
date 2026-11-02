package com.naminfo.ui.assistant.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.core.ApiLogRepository
import com.naminfo.core.MobionTlsCompatibility
import com.naminfo.contacts.MobionContactsService
import com.naminfo.R
import org.linphone.core.Account
import org.linphone.core.AuthInfo
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import org.linphone.core.tools.Log
import com.naminfo.ui.GenericViewModel
import com.naminfo.utils.AppUtils
import com.naminfo.utils.Event

class ThirdPartySipAccountLoginViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Third Party SIP Account Login ViewModel]"
        private const val MOBION_API_DOMAIN = "mobionglobal.com"
    }

    private data class ApiAuthenticationResult(
        val success: Boolean,
        val failureReason: String? = null,
        val profilePictureUrl: String? = null
    )

    val username = MutableLiveData<String>()

    val authId = MutableLiveData<String>()

    val apiDomain = MutableLiveData<String>()

    val sipDomain = MutableLiveData<String>()

    val displayName = MutableLiveData<String>()

    val transport = MutableLiveData<String>()

    val countryDialPlans = MutableLiveData<List<org.linphone.core.DialPlan>>()

    val internationalPrefix = MutableLiveData("91")

    val internationalPrefixIsoCountryCode = MutableLiveData("IN")

    val expandAdvancedSettings = MutableLiveData<Boolean>()

    val proxy = MutableLiveData<String>()

    val outboundProxy = MutableLiveData<String>()

    val loginEnabled = MediatorLiveData<Boolean>()

    val registrationInProgress = MutableLiveData<Boolean>()

    // Once an attempted SIP registration has failed, make the next user action
    // explicit. We do not keep re-creating accounts in the background.
    val registrationFailed = MutableLiveData<Boolean>()

    val accountLoggedInEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val accountLoginErrorEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val defaultTransportIndexEvent: MutableLiveData<Event<Int>> by lazy {
        MutableLiveData()
    }

    val availableTransports = arrayListOf<String>()

    private lateinit var newlyCreatedAuthInfo: AuthInfo
    private lateinit var newlyCreatedAccount: Account
    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            if (account == newlyCreatedAccount) {
                Log.i("$TAG Newly created account registration state is [$state] ($message)")

                if (state == RegistrationState.Ok) {
                    registrationInProgress.postValue(false)
                    core.removeListener(this)

                    // Set new account as default
                    core.defaultAccount = newlyCreatedAccount
                    // Persist the account and authentication immediately. The account
                    // will be restored and re-registered on later app launches until
                    // the user explicitly signs out from Settings.
                    core.config.sync()
                    accountLoggedInEvent.postValue(Event(true))
                } else if (state == RegistrationState.Failed) {
                    registrationInProgress.postValue(false)
                    registrationFailed.postValue(true)
                    core.removeListener(this)

                    val error = when (account.error) {
                        Reason.Forbidden -> {
                            AppUtils.getString(R.string.assistant_account_login_forbidden_error)
                        }
                        Reason.IOError -> {
                            AppUtils.getString(R.string.assistant_account_login_sip_server_unreachable_error)
                        }
                        else -> {
                            AppUtils.getFormattedString(
                                R.string.assistant_account_login_error,
                                account.error.toString()
                            )
                        }
                    }
                    accountLoginErrorEvent.postValue(Event(error))

                    Log.e("$TAG Account failed to REGISTER [$message], removing it")
                    // Explicitly disable registration before removing the account so
                    // the app itself never schedules another REGISTER attempt. Any
                    // packets already sent are SIP UDP transaction retransmissions.
                    val disabledAccountParams = account.params.clone()
                    disabledAccountParams.isRegisterEnabled = false
                    account.params = disabledAccountParams
                    core.removeAuthInfo(newlyCreatedAuthInfo)
                    core.removeAccount(newlyCreatedAccount)
                }
            }
        }
    }

    init {
        expandAdvancedSettings.value = true
        registrationInProgress.value = false
        registrationFailed.value = false

        loginEnabled.addSource(username) {
            loginEnabled.value = isLoginButtonEnabled()
        }
        loginEnabled.addSource(apiDomain) {
            loginEnabled.value = isLoginButtonEnabled()
        }
        loginEnabled.addSource(sipDomain) {
            loginEnabled.value = isLoginButtonEnabled()
        }

        // TODO: handle formatting errors ?

        availableTransports.add(TransportType.Udp.name.uppercase(Locale.getDefault()))
        availableTransports.add(TransportType.Tcp.name.uppercase(Locale.getDefault()))
        availableTransports.add(TransportType.Tls.name.uppercase(Locale.getDefault()))

        coreContext.postOnCoreThread {
            val orderedDialPlans = Factory.instance().dialPlans.toList().sortedBy {
                when {
                    it.isoCountryCode.equals("IN", ignoreCase = true) -> 0
                    it.isoCountryCode.equals("US", ignoreCase = true) -> 1
                    else -> 2
                }
            }
            countryDialPlans.postValue(orderedDialPlans)
            apiDomain.postValue(MOBION_API_DOMAIN)
            sipDomain.postValue(
                corePreferences.thirdPartySipAccountDefaultDomain.ifBlank {
                    corePreferences.defaultDomain
                }
            )

            val defaultTransport = corePreferences.thirdPartySipAccountDefaultTransport.uppercase(
                Locale.getDefault()
            )
            val udpIndex = availableTransports.indexOf(
                TransportType.Udp.name.uppercase(Locale.getDefault())
            )
            val savedTransportIndex = availableTransports.indexOf(defaultTransport)
            val index = savedTransportIndex.takeIf { it >= 0 } ?: udpIndex
            defaultTransportIndexEvent.postValue(Event(index))
        }
    }

    @UiThread
    fun login() {
        val apiBase = getBackendApiBaseUrl(apiDomain.value.orEmpty())
        val domainValue = sipDomain.value.orEmpty().trim()
        val user = username.value.orEmpty().trim()
        // For this operator flow, the registered mobile number is also the
        // credential used by the API and the SIP account. Do not expose a
        // separate password entry on the registration screen.
        val pwd = user
        val name = displayName.value.orEmpty().trim()
        val cCode = internationalPrefix.value.orEmpty().trim()

        registrationInProgress.value = true
        registrationFailed.value = false

        viewModelScope.launch {
            val authentication = performSignupAndSignin(apiBase, user, pwd, name, cCode)
            if (!authentication.success) {
                registrationInProgress.value = false
                registrationFailed.value = true
                val reason = authentication.failureReason ?: "Unknown API error"
                accountLoginErrorEvent.value = Event(
                    AppUtils.getFormattedString(R.string.assistant_account_login_error, reason)
                )
                return@launch
            }

            coreContext.postOnCoreThread { core ->
                core.loadConfigFromXml(corePreferences.thirdPartyDefaultValuesPath)

                // Remove sip: in front of domain, just in case...
                val domainWithoutSip = if (domainValue.startsWith("sip:")) {
                    domainValue.substring("sip:".length)
                } else {
                    domainValue
                }
                val domainAddress = Factory.instance().createAddress("sip:$domainWithoutSip")
                val port = domainAddress?.port ?: -1
                if (port != -1) {
                    Log.w("$TAG It seems a port [$port] was set in the domain [$domainValue], removing it from SIP identity but setting it to proxy server URI")
                }
                val domain = domainAddress?.domain ?: domainWithoutSip

                // Allow to enter SIP identity instead of simply username
                // in case identity domain doesn't match proxy domain
                var parsedUser = user
                if (parsedUser.startsWith("sip:")) {
                    parsedUser = parsedUser.substring("sip:".length)
                } else if (parsedUser.startsWith("sips:")) {
                    parsedUser = parsedUser.substring("sips:".length)
                }
                if (parsedUser.contains("@")) {
                    parsedUser = parsedUser.split("@")[0]
                }

                val userId = authId.value.orEmpty().trim()

                Log.i("$TAG Parsed username is [$parsedUser], user ID [$userId] and domain [$domain]")
                val identity = "sip:$parsedUser@$domain"
                val identityAddress = Factory.instance().createAddress(identity)
                if (identityAddress == null) {
                    Log.e("$TAG Can't parse [$identity] as Address!")
                    showRedToast(R.string.assistant_login_cant_parse_address_toast, R.drawable.warning_circle)
                    registrationInProgress.postValue(false)
                    registrationFailed.postValue(true)
                    return@postOnCoreThread
                }
                Log.i("$TAG Computed SIP identity is [${identityAddress.asStringUriOnly()}]")

                val accounts = core.accountList
                val found = accounts.find {
                    it.params.identityAddress?.weakEqual(identityAddress) == true
                }
                if (found != null) {
                    Log.w("$TAG An account with the same identity address [${found.params.identityAddress?.asStringUriOnly()}] already exists, do not add it again!")
                    showRedToast(R.string.assistant_account_login_already_connected_error, R.drawable.warning_circle)
                    registrationInProgress.postValue(false)
                    registrationFailed.postValue(true)
                    return@postOnCoreThread
                }

                newlyCreatedAuthInfo = Factory.instance().createAuthInfo(
                    parsedUser,
                    userId,
                    pwd,
                    null,
                    null,
                    domainAddress?.domain ?: domainValue
                )
                core.addAuthInfo(newlyCreatedAuthInfo)

                val accountParams = core.createAccountParams()

                if (displayName.value.orEmpty().isNotEmpty()) {
                    identityAddress.displayName = displayName.value.orEmpty().trim()
                }
                val profileSection = "saved_profile_${identityAddress.asStringUriOnly()}"
                if (identityAddress.displayName.isNullOrBlank()) {
                    identityAddress.displayName = core.config.getString(profileSection, "display_name", null)
                }
                accountParams.pictureUri = authentication.profilePictureUrl
                    ?: core.config.getString(profileSection, "picture_uri", null)
                accountParams.identityAddress = identityAddress

                val outboundProxyValue = outboundProxy.value.orEmpty().trim()
                val outboundProxyAddress = if (outboundProxyValue.isNotEmpty()) {
                    val server = if (outboundProxyValue.startsWith("sip:")) {
                        outboundProxyValue
                    } else {
                        "sip:$outboundProxyValue"
                    }
                    Factory.instance().createAddress(server)
                } else {
                    null
                }
                if (outboundProxyAddress != null) {
                    outboundProxyAddress.transport = when (transport.value.orEmpty().trim()) {
                        TransportType.Tcp.name.uppercase(Locale.getDefault()) -> TransportType.Tcp
                        TransportType.Tls.name.uppercase(Locale.getDefault()) -> TransportType.Tls
                        else -> TransportType.Udp
                    }
                    Log.i("$TAG Created outbound proxy server SIP address [${outboundProxyAddress.asStringUriOnly()}]")
                    accountParams.setRoutesAddresses(arrayOf(outboundProxyAddress))
                }

                val proxyServerValue = proxy.value.orEmpty().trim()
                val proxyServerAddress = if (proxyServerValue.isNotEmpty()) {
                    val server = if (proxyServerValue.startsWith("sip:")) {
                        proxyServerValue
                    } else {
                        "sip:$proxyServerValue"
                    }
                    Factory.instance().createAddress(server)
                } else {
                    outboundProxyAddress ?: domainAddress ?: Factory.instance().createAddress("sip:$domainWithoutSip")
                }
                proxyServerAddress?.transport = when (transport.value.orEmpty().trim()) {
                    TransportType.Tcp.name.uppercase(Locale.getDefault()) -> TransportType.Tcp
                    TransportType.Tls.name.uppercase(Locale.getDefault()) -> TransportType.Tls
                    else -> TransportType.Udp
                }
                Log.i("$TAG Created proxy server SIP address [${proxyServerAddress?.asStringUriOnly()}]")
                accountParams.serverAddress = proxyServerAddress
                Log.i("$TAG Is outbound proxy enabled ? [${accountParams.isOutboundProxyEnabled}]")

                // The login country selection must not add a prefix to dialpad calls.
                accountParams.internationalPrefix = ""
                accountParams.internationalPrefixIsoCountryCode = ""

                newlyCreatedAccount = core.createAccount(accountParams)
                registrationInProgress.postValue(true)
                core.addListener(coreListener)
                core.addAccount(newlyCreatedAccount)
            }
        }
    }

    @UiThread
    private fun isLoginButtonEnabled(): Boolean {
        return username.value.orEmpty().isNotBlank() &&
            apiDomain.value.orEmpty().isNotBlank() &&
            sipDomain.value.orEmpty().isNotBlank()
    }

   /* @UiThread
    private fun isLoginButtonEnabled(): Boolean {
        val mobileNumber = username.value.orEmpty().trim()
        return true // mobileNumber.matches(Regex("[6-9]\\d{9}")) && domain.value.orEmpty().isNotEmpty()
    }*/

    private suspend fun performSignupAndSignin(
        apiBase: String,
        subscriberId: String,
        passwordStr: String,
        displayNameStr: String,
        countryCodeStr: String
    ): ApiAuthenticationResult = withContext(Dispatchers.IO) {

        // Certificate compatibility setup can be slow on some OEM builds. Run it on
        // the login worker thread rather than blocking Application.onCreate().
        MobionTlsCompatibility.install(coreContext.context)

        val password = passwordStr
        val names = displayNameStr.trim().split(" ", limit = 2)
        val firstName = names.getOrNull(0)?.takeIf { it.isNotBlank() } ?: subscriberId
        val lastName = names.getOrNull(1).orEmpty()
        val countryCode = if (countryCodeStr.startsWith("+")) countryCodeStr else "+$countryCodeStr"

        // A new number is created first. A conflict means this is an existing user,
        // so continue directly with sign-in instead of treating it as an error.
        var signupLogId: Long? = null
        try {
            val signupUrl = URL("$apiBase/users")
            val signupConn = signupUrl.openConnection() as HttpURLConnection
            signupConn.requestMethod = "POST"
            signupConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            signupConn.connectTimeout = 5000
            signupConn.readTimeout = 5000
            signupConn.doOutput = true

            val signupBody = JSONObject().apply {
                put("subscriberId", subscriberId)
                put("password", password)
                put("firstName", firstName)
                put("lastName", lastName)
                put("countryCode", countryCode)
                put("mobileNo", subscriberId)
            }

            signupLogId = ApiLogRepository.begin("POST", signupUrl.toString(), signupBody.toString())
            OutputStreamWriter(signupConn.outputStream).use { it.write(signupBody.toString()) }
            val signupCode = signupConn.responseCode
            val signupError = signupConn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            ApiLogRepository.complete(requireNotNull(signupLogId), signupCode, signupError)
            signupConn.disconnect()

            val userAlreadyExists = signupCode == HttpURLConnection.HTTP_CONFLICT ||
                signupError.contains("already", ignoreCase = true) ||
                signupError.contains("exist", ignoreCase = true)
            when {
                signupCode in 200..299 -> Log.i("$TAG Signup API succeeded with HTTP $signupCode")
                userAlreadyExists -> Log.i("$TAG Signup reports existing user (HTTP $signupCode), signing in")
                else -> {
                    Log.w("$TAG Signup API returned HTTP $signupCode, attempting signin fallback")
                }
            }
        } catch (e: Exception) {
            ApiLogRepository.fail(signupLogId ?: ApiLogRepository.begin("POST", "$apiBase/users"), e)
            Log.w("$TAG Signup API unavailable (${e.javaClass.simpleName}: ${e.message}), attempting signin fallback")
        }

        // Sign in both newly-created and existing users before registering SIP.
        var signinLogId: Long? = null
        try {
            val signinUrl = URL("$apiBase/auth/signin")
            val signinConn = signinUrl.openConnection() as HttpURLConnection
            signinConn.requestMethod = "POST"
            signinConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            signinConn.connectTimeout = 10000
            signinConn.readTimeout = 10000
            signinConn.doOutput = true

            val signinBody = JSONObject().apply {
                put("subscriberId", subscriberId)
                put("password", password)
            }

            signinLogId = ApiLogRepository.begin("POST", signinUrl.toString(), signinBody.toString())
            OutputStreamWriter(signinConn.outputStream).use { it.write(signinBody.toString()) }
            val signinCode = signinConn.responseCode
            if (signinCode in 200..299) {
                val responseText = signinConn.inputStream.bufferedReader().use { it.readText() }
                ApiLogRepository.complete(requireNotNull(signinLogId), signinCode, responseText)
                Log.i("$TAG Signin API succeeded with HTTP $signinCode")
                var profilePictureUrl: String? = null
                try {
                    val json = JSONObject(responseText)
                    val token = json.optString("token").ifEmpty { json.optString("accessToken") }
                    val refreshToken = json.optString("refreshToken")
                    val accessTokenExpiresAt = json.optString("accessTokenExpiresAt")
                    val refreshTokenExpiresAt = json.optString("refreshTokenExpiresAt")
                    if (token.isNotEmpty()) corePreferences.authToken = token
                    if (refreshToken.isNotEmpty()) corePreferences.refreshToken = refreshToken
                    corePreferences.accessTokenExpiresAt = accessTokenExpiresAt.ifEmpty { null }
                    corePreferences.refreshTokenExpiresAt = refreshTokenExpiresAt.ifEmpty { null }
                    corePreferences.apiDomain = apiBase
                    val now = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                    corePreferences.loginTime = now
                    // This single-user endpoint is intentionally used only for
                    // the account that just signed in. Contact photos come from
                    // the single bulk `/users` map, never N individual calls.
                    profilePictureUrl = MobionContactsService.fetchProfilePictureUrl(subscriberId)
                } catch (e: Exception) {
                    Log.w("$TAG Failed to parse signin response JSON: $e")
                }
                signinConn.disconnect()
                return@withContext ApiAuthenticationResult(
                    success = true,
                    profilePictureUrl = profilePictureUrl
                )
            } else {
                val errorText = signinConn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                ApiLogRepository.complete(requireNotNull(signinLogId), signinCode, errorText)
                Log.e("$TAG Signin API failed with HTTP $signinCode")
                signinConn.disconnect()
                return@withContext ApiAuthenticationResult(false, "Signin API HTTP $signinCode")
            }
        } catch (e: Exception) {
            ApiLogRepository.fail(signinLogId ?: ApiLogRepository.begin("POST", "$apiBase/auth/signin"), e)
            val errorMsg = if (e is java.net.SocketTimeoutException) {
                "Server Connection Timeout"
            } else {
                "Signin API unavailable (${e.javaClass.simpleName})"
            }
            Log.e("$TAG Signin API unavailable: ${e.javaClass.simpleName}")
            return@withContext ApiAuthenticationResult(false, errorMsg)
        }
    }

    private fun getBackendApiBaseUrl(rawApiDomain: String): String {
        val input = rawApiDomain.trim().trimEnd('/')
        val baseUrl = when {
            input.startsWith("http://", ignoreCase = true) ||
                input.startsWith("https://", ignoreCase = true) -> input
            isIpAddressOrLocalhost(input) -> "http://$input"
            else -> "https://$input"
        }

        return when {
            baseUrl.endsWith("/Diyaone/api", ignoreCase = true) -> baseUrl
            baseUrl.endsWith("/Diyaone", ignoreCase = true) -> "$baseUrl/api"
            else -> "$baseUrl/Diyaone/api"
        }
    }

    private fun isIpAddressOrLocalhost(value: String): Boolean {
        val host = value.substringBefore('/').substringBefore(':')
        return host.equals("localhost", ignoreCase = true) ||
            host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))
    }

    @UiThread
    fun toggleAdvancedSettingsExpand() {
        expandAdvancedSettings.value = expandAdvancedSettings.value == false
    }
}
