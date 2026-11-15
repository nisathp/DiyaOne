package com.naminfo.ui.main.settings.viewmodel

import android.net.Uri
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import java.util.Locale
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.contacts.MobionContactsService
import com.naminfo.core.ApiLogRepository
import com.naminfo.core.MobionTokenRefresher
import com.naminfo.R
import com.naminfo.ui.main.contacts.viewmodel.ContactsListViewModel
import org.linphone.core.Account
import org.linphone.core.AccountDevice
import org.linphone.core.AccountManagerServices
import org.linphone.core.AccountManagerServicesRequest
import org.linphone.core.AccountManagerServicesRequestListenerStub
import org.linphone.core.Address
import org.linphone.core.Dictionary
import org.linphone.core.tools.Log
import com.naminfo.ui.GenericViewModel
import com.naminfo.ui.main.model.AccountModel
import com.naminfo.ui.main.model.isEndToEndEncryptionMandatory
import com.naminfo.ui.main.settings.model.AccountDeviceModel
import com.naminfo.utils.Event
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.io.File
import java.net.URLConnection
import org.json.JSONObject

import androidx.annotation.*
import androidx.lifecycle.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.linphone.core.*
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.RegistrationState

class AccountProfileViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Account Profile ViewModel]"
    }

    val accountModel = MutableLiveData<AccountModel>()

    val sipAddress = MutableLiveData<String>()

    val displayName = MutableLiveData<String>()

    val registerEnabled = MutableLiveData<Boolean>()

    val isCurrentlySelectedModeSecure = MutableLiveData<Boolean>()

    val devices = MutableLiveData<ArrayList<AccountDeviceModel>>()

    val accountFoundEvent = MutableLiveData<Event<Boolean>>()

    val expandDetails = MutableLiveData<Boolean>()

    val expandDevices = MutableLiveData<Boolean>()

    val isOnDefaultDomain = MutableLiveData<Boolean>()

    val emptyDevices = MediatorLiveData<Boolean>()

    val devicesFetchInProgress = MutableLiveData<Boolean>()

    val hideAccountSettings = MutableLiveData<Boolean>()

    val hideSipAddresses = MutableLiveData<Boolean>()

    val deviceId = MutableLiveData<String>()

    val showDeviceId = MutableLiveData<Boolean>()

    val accountRemovedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    private lateinit var account: Account

    private lateinit var accountManagerServices: AccountManagerServices

    val signOutInProgress = MutableLiveData(false)
    val signOutError = MutableLiveData("")

    /** Keeps the existing avatar visible while the remote DELETE request is in flight. */
    val profilePictureDeleteInProgress = MutableLiveData(false)

    private val accountManagerServicesListener = object : AccountManagerServicesRequestListenerStub() {
        @WorkerThread
        override fun onDevicesListFetched(
            request: AccountManagerServicesRequest,
            accountDevices: Array<out AccountDevice>
        ) {
            Log.i("$TAG Fetched [${accountDevices.size}] devices for our account")
            val devicesList = arrayListOf<AccountDeviceModel>()
            for (accountDevice in accountDevices) {
                devicesList.add(
                    AccountDeviceModel(accountDevice) { model, device ->
                        if (::accountManagerServices.isInitialized) {
                            val identityAddress = account.params.identityAddress
                            if (identityAddress != null) {
                                Log.i(
                                    "$TAG Removing device with name [${device.name}] and uuid [${device.uuid}]"
                                )
                                val deleteRequest = accountManagerServices.createDeleteDeviceRequest(
                                    identityAddress,
                                    device
                                )
                                deleteRequest.addListener(this)
                                deleteRequest.submit()

                                val newList = arrayListOf<AccountDeviceModel>()
                                newList.addAll(devices.value.orEmpty())
                                newList.remove(model)
                                devices.postValue(newList)
                            } else {
                                Log.e("$TAG Account identity address is null, can't delete device!")
                            }
                        }
                    }
                )
            }
            devices.postValue(devicesList)
            devicesFetchInProgress.postValue(false)
        }

        override fun onRequestSuccessful(request: AccountManagerServicesRequest, data: String?) {
            if (request.type == AccountManagerServicesRequest.Type.DeleteDevice) {
                Log.i("$TAG Device successfully deleted: $data")
            }
        }

        @WorkerThread
        override fun onRequestError(
            request: AccountManagerServicesRequest,
            statusCode: Int,
            errorMessage: String?,
            parameterErrors: Dictionary?
        ) {
            Log.e(
                "$TAG Request [${request.type}] returned an error with status code [$statusCode] and message [$errorMessage]"
            )
            if (!errorMessage.isNullOrEmpty()) {
                when (request.type) {
                    AccountManagerServicesRequest.Type.GetDevicesList, AccountManagerServicesRequest.Type.DeleteDevice -> {
                        showFormattedRedToast(errorMessage, R.drawable.warning_circle)
                        devicesFetchInProgress.postValue(false)
                    }
                    else -> {}
                }
            }
        }
    }

    init {
        expandDetails.value = true
        expandDevices.value = false
        showDeviceId.value = false
        devicesFetchInProgress.value = true
        isOnDefaultDomain.value = false

        emptyDevices.value = true
        emptyDevices.addSource(devices) { list ->
            emptyDevices.value = list.orEmpty().isEmpty()
        }

        coreContext.postOnCoreThread {
            hideAccountSettings.postValue(corePreferences.hideAccountSettings)
            hideSipAddresses.postValue(corePreferences.hideSipAddresses)
        }
    }

    @UiThread
    override fun onCleared() {
        super.onCleared()

        coreContext.postOnCoreThread {
            accountModel.value?.destroy()
        }
    }

    @UiThread
    fun findAccountMatchingIdentity(identity: String) {
        coreContext.postOnCoreThread { core ->
            val found = core.accountList.find {
                it.params.identityAddress?.asStringUriOnly() == identity
            }
            if (found != null) {
                Log.i("$TAG Found matching account [$found]")
                account = found
                accountModel.postValue(AccountModel(account))
                isCurrentlySelectedModeSecure.postValue(isEndToEndEncryptionMandatory())
                registerEnabled.postValue(account.params.isRegisterEnabled)

                sipAddress.postValue(account.params.identityAddress?.asStringUriOnly())
                displayName.postValue(account.params.identityAddress?.displayName)
                showDeviceId.postValue(false)

                val identityAddress = account.params.identityAddress
                if (identityAddress != null) {
                    val domain = identityAddress.domain
                    val defaultDomain = corePreferences.defaultDomain
                    isOnDefaultDomain.postValue(domain == defaultDomain)
                    /*if (domain == defaultDomain) {
                        requestDevicesList(identityAddress)
                    } else {
                        Log.i(
                            "$TAG Account with domain [$domain] can't get devices list, only works with [$defaultDomain] domain"
                        )
                    }*/
                    // This app does not use the Account Manager device-list API.
                    devices.postValue(arrayListOf())
                    devicesFetchInProgress.postValue(false)
                    expandDevices.postValue(false)
                } else {
                    Log.e("$TAG No identity address found!")
                }

                deviceId.postValue(account.contactAddress?.getUriParam("gr"))

                accountFoundEvent.postValue(Event(true))
            } else {
                accountFoundEvent.postValue(Event(false))
            }
        }
    }

    /*@UiThread
    fun deleteAccount() {
        coreContext.postOnCoreThread { core ->
            if (::account.isInitialized) {
                val identity = account.params.identityAddress?.asStringUriOnly()
                Log.i("$TAG Signing out account [$identity], retaining local history and profile")
                val profileSection = "saved_profile_$identity"
                core.config.setString(profileSection, "display_name", account.params.identityAddress?.displayName)
                core.config.setString(profileSection, "picture_uri", account.params.pictureUri)
                val authInfo = account.findAuthInfo()
                core.removeAccount(account)
                if (authInfo != null && core.accountList.none { it.findAuthInfo() == authInfo }) {
                    core.removeAuthInfo(authInfo)
                }
                core.config.sync()
                accountRemovedEvent.postValue(Event(true))

                if (core.accountList.isEmpty()) {
                    Log.w("$TAG No more account found in Core")
                    if (!core.provisioningUri.isNullOrEmpty()) {
                        Log.w("$TAG Removing remote provisioning URI")
                        core.provisioningUri = null
                    }
                }
            }
        }
    }*/

    @UiThread
    fun deleteAccount() {
        if (signOutInProgress.value == true) return

        signOutInProgress.value = true
        signOutError.value = ""

        viewModelScope.launch {
            try {
                performLogout()
                ContactsListViewModel.resetMobionSessionCache()

                // Ask FreeSWITCH to unregister first.  An unreachable SIP server must not
                // trap the user in a signed-in screen after the HTTP logout has already
                // invalidated the app session.
                val unregisterFailure = try {
                    withTimeout(30_000L) {
                        unregisterAndRemoveAccount()
                    }
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(
                        "$TAG SIP unregister was not confirmed; completing local sign-out: $error"
                    )
                    error
                }

                if (unregisterFailure != null) {
                    removeAccountLocallyAfterUnregisterFailure()
                }

                // The API tokens are cleared by performLogout(). At this point the SIP
                // account is removed either after a confirmed UNREGISTER or a safe local
                // fallback, so the user can always return to the sign-in screen.
                accountRemovedEvent.value = Event(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val reason = error.message ?: error.javaClass.simpleName
                signOutError.value = "Sign-out failed: $reason"
                Log.e("$TAG Sign-out failed: $error")
            } finally {
                signOutInProgress.value = false
            }
        }
    }

    /**
     * Keeps chat history but removes the local SIP credentials if FreeSWITCH doesn't answer
     * an UNREGISTER. This is deliberately a fallback; the normal path waits for Cleared.
     */
    private suspend fun removeAccountLocallyAfterUnregisterFailure(): Unit =
        suspendCancellableCoroutine { continuation ->
            coreContext.postOnCoreThread { core ->
                try {
                    if (!::account.isInitialized || core.accountList.none { it == account }) {
                        continuation.resumeWith(Result.success(Unit))
                        return@postOnCoreThread
                    }

                    val target = account
                    val authInfo = target.findAuthInfo()
                    val identity = target.params.identityAddress?.asStringUriOnly()
                    val profileSection = "saved_profile_$identity"

                    core.config.setString(
                        profileSection,
                        "display_name",
                        target.params.identityAddress?.displayName
                    )
                    core.config.setString(profileSection, "picture_uri", target.params.pictureUri)
                    core.removeAccount(target)

                    if (authInfo != null && core.accountList.none { it.findAuthInfo() == authInfo }) {
                        core.removeAuthInfo(authInfo)
                    }
                    if (core.accountList.isEmpty()) {
                        core.provisioningUri = null
                    }
                    core.config.sync()

                    Log.i("$TAG Removed local SIP account after unregister failure")
                    continuation.resumeWith(Result.success(Unit))
                } catch (error: Exception) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }

    private suspend fun performLogout() = withContext(Dispatchers.IO) {
        val apiBase = corePreferences.apiDomain
        val refreshToken = corePreferences.refreshToken

        if (apiBase.isNullOrEmpty()) {
            // A legacy or partially completed sign-in may have tokens without
            // an API endpoint. A local logout must still discard them.
            corePreferences.authToken = null
            corePreferences.refreshToken = null
            corePreferences.accessTokenExpiresAt = null
            corePreferences.refreshTokenExpiresAt = null
            corePreferences.apiDomain = null
            corePreferences.loginTime = null
            corePreferences.loginTime = null
            return@withContext
        }

        try {
            val logoutUrl = URL("${getApiBaseUrl(apiBase)}/auth/logout")
            val body = JSONObject().apply {
                if (!refreshToken.isNullOrEmpty()) {
                    put("refreshToken", refreshToken)
                }
            }

            // Access tokens are short-lived. Refresh first when it is near expiry, and if
            // the server still returns 401, refresh once more then retry the logout request.
            var bearerToken = MobionTokenRefresher.validAccessToken(
                apiBase,
                endSessionOnRefreshFailure = false
            )
                ?: corePreferences.authToken
            var result = executeLogoutRequest(logoutUrl, body.toString(), bearerToken)
            if (result.first == HttpURLConnection.HTTP_UNAUTHORIZED) {
                bearerToken = MobionTokenRefresher.refreshAfterUnauthorized(
                    apiBase,
                    endSessionOnRefreshFailure = false
                )
                if (!bearerToken.isNullOrEmpty()) {
                    result = executeLogoutRequest(logoutUrl, body.toString(), bearerToken)
                }
            }
            Log.i("$TAG Logout API response code: ${result.first}")
        } catch (e: Exception) {
            Log.w("$TAG Logout API exception: $e")
        } finally {
            // Logout is a local sign-out as well: do not retain credentials when
            // the server is temporarily unreachable or closes the connection.
            corePreferences.authToken = null
            corePreferences.refreshToken = null
            corePreferences.accessTokenExpiresAt = null
            corePreferences.refreshTokenExpiresAt = null
            corePreferences.apiDomain = null
            corePreferences.loginTime = null
        }
    }

    @WorkerThread
    private fun executeLogoutRequest(
        logoutUrl: URL,
        body: String,
        bearerToken: String?
    ): Pair<Int, String?> {
        var logId: Long? = null
        var connection: HttpURLConnection? = null
        return try {
            connection = logoutUrl.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            bearerToken?.takeIf { it.isNotEmpty() }?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true

            logId = ApiLogRepository.begin("POST", logoutUrl.toString(), body)
            OutputStreamWriter(connection.outputStream).use { it.write(body) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            ApiLogRepository.complete(requireNotNull(logId), code, response)
            code to response
        } catch (error: Exception) {
            ApiLogRepository.fail(logId ?: ApiLogRepository.begin("POST", logoutUrl.toString()), error)
            throw error
        } finally {
            connection?.disconnect()
        }
    }

    private fun getApiBaseUrl(rawApiBase: String): String {
        val apiBase = rawApiBase.trim().trimEnd('/')
        return when {
            apiBase.endsWith("/Diyaone/api", ignoreCase = true) -> apiBase
            apiBase.endsWith("/Diyaone", ignoreCase = true) -> "$apiBase/api"
            else -> "$apiBase/Diyaone/api"
        }
    }

    private suspend fun unregisterAndRemoveAccount(): Unit =
        suspendCancellableCoroutine<Unit> { continuation ->
            coreContext.postOnCoreThread { core ->
                if (!continuation.isActive) return@postOnCoreThread

                if (!::account.isInitialized) {
                    continuation.resumeWith(
                        Result.failure(
                            IllegalStateException("Account is not loaded")
                        )
                    )
                    return@postOnCoreThread
                }

                val target = account
                val authInfo = target.findAuthInfo()
                var completed = false

                val listener = object : CoreListenerStub() {
                    override fun onAccountRegistrationStateChanged(
                        core: Core,
                        changedAccount: Account,
                        state: RegistrationState?,
                        message: String
                    ) {
                        if (changedAccount != target) return
                        Log.i(
                            "$TAG Sign-out registration state=$state, message=$message"
                        )
                        if (completed || !continuation.isActive) return

                        when (state) {
                            RegistrationState.Cleared -> {
                                completed = true
                                core.removeListener(this)

                                // This callback is dispatched while liblinphone is still
                                // handling the REGISTER 200 response. Removing the account
                                // here frees SIP objects owned by that transaction and can
                                // crash its Core Thread when response handling resumes.
                                // Snapshot the profile while the account is valid, then run
                                // the destructive cleanup in the next Core-thread task.
                                val identity = target.params.identityAddress
                                    ?.asStringUriOnly()
                                val displayName = target.params.identityAddress?.displayName
                                val pictureUri = target.params.pictureUri

                                coreContext.postOnCoreThread { cleanupCore ->
                                    try {
                                        val profileSection = "saved_profile_$identity"

                                        cleanupCore.config.setString(
                                            profileSection,
                                            "display_name",
                                            displayName
                                        )

                                        cleanupCore.config.setString(
                                            profileSection,
                                            "picture_uri",
                                            pictureUri
                                        )

                                        // Keep conversations and call history.
                                        // Do not use removeAccountWithData().
                                        if (cleanupCore.accountList.any { it == target }) {
                                            cleanupCore.removeAccount(target)
                                        }

                                        // Remove credentials only after unregistration.
                                        // Preserve credentials shared by another account.
                                        if (
                                            authInfo != null &&
                                            cleanupCore.accountList.none {
                                                it.findAuthInfo() == authInfo
                                            }
                                        ) {
                                            cleanupCore.removeAuthInfo(authInfo)
                                        }

                                        if (cleanupCore.accountList.isEmpty()) {
                                            cleanupCore.provisioningUri = null
                                        }

                                        cleanupCore.config.sync()

                                        if (continuation.isActive) {
                                            continuation.resumeWith(
                                                Result.success(Unit)
                                            )
                                        }
                                    } catch (error: Exception) {
                                        if (continuation.isActive) {
                                            continuation.resumeWith(
                                                Result.failure(error)
                                            )
                                        }
                                    }
                                }
                            }

                            RegistrationState.Failed -> {
                                completed = true
                                core.removeListener(this)

                                if (continuation.isActive) {
                                    continuation.resumeWith(
                                        Result.failure(
                                            IllegalStateException(
                                                "Unregistration failed: $message"
                                            )
                                        )
                                    )
                                }
                            }

                            else -> Unit
                        }
                    }
                }

                // A timeout/cancellation must remove the temporary listener.
                continuation.invokeOnCancellation {
                    coreContext.postOnCoreThread { currentCore ->
                        currentCore.removeListener(listener)
                    }
                }

                core.addListener(listener)

                if (!continuation.isActive) {
                    core.removeListener(listener)
                    return@postOnCoreThread
                }

                try {
                    when {
                        target.state == RegistrationState.Cleared -> {
                            // Unregistration already completed, possibly after
                            // an earlier UI timeout. Finish local cleanup.
                            listener.onAccountRegistrationStateChanged(
                                core,
                                target,
                                RegistrationState.Cleared,
                                "Already unregistered"
                            )
                        }

                        !core.isNetworkReachable -> {
                            core.removeListener(listener)

                            if (continuation.isActive) {
                                continuation.resumeWith(
                                    Result.failure(
                                        IllegalStateException("Network unavailable")
                                    )
                                )
                            }
                        }

                        else -> {
                            // Authentication and account remain available while
                            // the SDK sends the unregister request.
                            val params = target.params.clone()
                            params.isRegisterEnabled = false
                            params.isPublishEnabled = false
                            target.params = params

                            core.config.sync()

                            Log.i(
                                "$TAG Waiting for unregistration confirmation"
                            )
                        }
                    }
                } catch (error: Exception) {
                    core.removeListener(listener)

                    if (!completed && continuation.isActive) {
                        completed = true
                        continuation.resumeWith(
                            Result.failure(error)
                        )
                    }
                }
            }
        }

    @UiThread
    fun setNewPicturePath(path: String) {
        coreContext.postOnCoreThread {
            if (::account.isInitialized && coreContext.core.accountList.any { it == account }) {
                val params = account.params
                val copy = params.clone()

                if (path.isNotEmpty() && path != params.pictureUri) {
                    Log.i("$TAG New account profile picture [$path]")
                    copy.pictureUri = path
                } else {
                    Log.i("$TAG Account profile picture removed")
                    copy.pictureUri = null
                }

                // Create a new model to force UI to update
                val newModel = AccountModel(account)
                newModel.picturePath.postValue(path)
                accountModel.postValue(newModel)

                // Also update friend & contact avatar model for ourselves
                val model = coreContext.contactsManager.getContactAvatarModelForAddress(
                    params.identityAddress
                )
                model.friend.photo = path
                model.applyResolvedProfile(
                    displayName = model.contactName ?: params.identityAddress?.displayName.orEmpty(),
                    photoUrl = path
                )

                account.params = copy
                account.refreshRegister()

                val identity = params.identityAddress?.asStringUriOnly()
                if (!identity.isNullOrEmpty()) {
                    coreContext.core.config.setString("saved_profile_$identity", "picture_uri", path)
                    coreContext.core.config.sync()
                }
                MobionContactsService.bumpAvatarCacheEpoch()
                coreContext.contactsManager.invalidateCachesAndNotify()
            }
        }
    }

    /** Uploads the selected Settings profile image to the DiyaOne API. */
    @UiThread
    private fun resolveProfileSubscriberId(core: org.linphone.core.Core): String? {
        val activeAccount = if (::account.isInitialized && core.accountList.any { it == account }) {
            account
        } else {
            null
        }
        return (activeAccount ?: core.defaultAccount)?.params?.identityAddress?.username
    }

    fun uploadProfilePicture(path: String) {
        if (path.isBlank()) return

        coreContext.postOnCoreThread { core ->
            val subscriberId = resolveProfileSubscriberId(core)
            val apiBase = corePreferences.apiDomain
            val accessToken = corePreferences.authToken

            if (subscriberId.isNullOrBlank() || apiBase.isNullOrBlank() || accessToken.isNullOrBlank()) {
                Log.w("$TAG Profile picture upload skipped: missing signed-in API session")
                showFormattedRedToast(
                    "Sign in again, then upload your profile picture.",
                    R.drawable.warning_circle
                )
                return@postOnCoreThread
            }

            viewModelScope.launch {
                val uploaded = withContext(Dispatchers.IO) {
                    putProfilePicture(getApiBaseUrl(apiBase), accessToken, subscriberId, path)
                }
                if (uploaded) {
                    Log.i("$TAG Profile picture uploaded for subscriber [$subscriberId]")
                    // Reload active contact lists now, so the uploaded image does not wait for
                    // a manual swipe-to-refresh.
                    MobionContactsService.markProfilePictureUploaded(subscriberId)
                    showFormattedGreenToast("Profile picture updated.", R.drawable.user_circle_check)
                } else {
                    showFormattedRedToast(
                        "Couldn't upload profile picture. Please try again.",
                        R.drawable.warning_circle
                    )
                }
            }
        }
    }

    /** Deletes the signed-in user's remote profile picture before clearing the local avatar. */
    @UiThread
    fun deleteProfilePicture() {
        if (profilePictureDeleteInProgress.value == true) return

        // Do not clear picturePath here: the current photo stays behind the spinner until
        // the server confirms the deletion. The initials avatar is shown only after success.
        profilePictureDeleteInProgress.value = true
        coreContext.postOnCoreThread { core ->
            val subscriberId = resolveProfileSubscriberId(core)
            val apiBase = corePreferences.apiDomain
            val accessToken = corePreferences.authToken

            if (subscriberId.isNullOrBlank() || apiBase.isNullOrBlank() || accessToken.isNullOrBlank()) {
                Log.w("$TAG Profile picture deletion skipped: missing signed-in API session")
                profilePictureDeleteInProgress.postValue(false)
                showFormattedRedToast(
                    "Sign in again, then remove your profile picture.",
                    R.drawable.warning_circle
                )
                return@postOnCoreThread
            }

            viewModelScope.launch {
                try {
                    val deleted = withContext(Dispatchers.IO) {
                        deleteRemoteProfilePicture(getApiBaseUrl(apiBase), accessToken)
                    }
                    if (deleted) {
                        Log.i("$TAG Profile picture deleted for subscriber [$subscriberId]")
                        MobionContactsService.clearCachedProfilePicture(subscriberId)
                        MobionContactsService.refreshContactsAfterProfilePictureChange()
                        coreContext.postOnMainThread {
                            setNewPicturePath("")
                        }
                        showFormattedGreenToast("Profile picture removed.", R.drawable.user_circle_check)
                    } else {
                        showFormattedRedToast(
                            "Couldn't remove profile picture. Please try again.",
                            R.drawable.warning_circle
                        )
                    }
                } finally {
                    profilePictureDeleteInProgress.value = false
                }
            }
        }
    }

    private fun putProfilePicture(
        apiBase: String,
        accessToken: String,
        subscriberId: String,
        localPath: String,
        allowTokenRefreshRetry: Boolean = true
    ): Boolean {
        val filePath = Uri.parse(localPath).path ?: localPath.removePrefix("file:")
        val image = File(filePath)
        if (!image.isFile || image.length() == 0L) {
            Log.e("$TAG Profile picture upload failed: local image is unavailable")
            return false
        }

        val boundary = "DiyaOne-${System.currentTimeMillis()}"
        val connection = URL("${apiBase.trimEnd('/')}/users/profile-picture")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        val bearerToken = MobionTokenRefresher.validAccessToken(apiBase) ?: accessToken
        connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val logId = ApiLogRepository.begin(
            "PUT",
            "${apiBase.trimEnd('/')}/users/profile-picture",
            "multipart: SubscriberId=$subscriberId, FILE=${image.name}"
        )

        return try {
            connection.outputStream.buffered().use { output ->
                fun writeText(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                writeText("--$boundary\r\n")
                writeText("Content-Disposition: form-data; name=\"SubscriberId\"\r\n\r\n")
                writeText("$subscriberId\r\n")
                writeText("--$boundary\r\n")
                writeText(
                    "Content-Disposition: form-data; name=\"FILE\"; filename=\"${image.name}\"\r\n"
                )
                val mimeType = URLConnection.guessContentTypeFromName(image.name) ?: "image/jpeg"
                writeText("Content-Type: $mimeType\r\n\r\n")
                image.inputStream().use { input -> input.copyTo(output) }
                writeText("\r\n--$boundary--\r\n")
            }

            val responseCode = connection.responseCode
            val response = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            ApiLogRepository.complete(logId, responseCode, response)
            if (responseCode !in 200..299) {
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && allowTokenRefreshRetry) {
                    connection.disconnect()
                    if (MobionTokenRefresher.refreshAfterUnauthorized(apiBase) != null) {
                        return putProfilePicture(
                            apiBase,
                            accessToken,
                            subscriberId,
                            localPath,
                            allowTokenRefreshRetry = false
                        )
                    }
                }
                val message = response.orEmpty()
                Log.e("$TAG Profile picture upload failed with HTTP $responseCode: $message")
                false
            } else {
                true
            }
        } catch (error: Exception) {
            ApiLogRepository.fail(logId, error)
            Log.e("$TAG Profile picture upload failed: ${error.javaClass.simpleName}")
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun deleteRemoteProfilePicture(
        apiBase: String,
        accessToken: String,
        allowTokenRefreshRetry: Boolean = true
    ): Boolean {
        val endpoint = "${apiBase.trimEnd('/')}/users/profile-picture"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "DELETE"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val bearerToken = MobionTokenRefresher.validAccessToken(apiBase) ?: accessToken
        connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        connection.setRequestProperty("Accept", "application/json")
        val logId = ApiLogRepository.begin("DELETE", endpoint)

        return try {
            val responseCode = connection.responseCode
            val response = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            ApiLogRepository.complete(logId, responseCode, response)
            if (responseCode !in 200..299) {
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && allowTokenRefreshRetry) {
                    connection.disconnect()
                    if (MobionTokenRefresher.refreshAfterUnauthorized(apiBase) != null) {
                        return deleteRemoteProfilePicture(
                            apiBase,
                            accessToken,
                            allowTokenRefreshRetry = false
                        )
                    }
                }
                Log.e("$TAG Profile picture deletion failed with HTTP $responseCode: ${response.orEmpty()}")
                false
            } else {
                true
            }
        } catch (error: Exception) {
            ApiLogRepository.fail(logId, error)
            Log.e("$TAG Profile picture deletion failed: ${error.javaClass.simpleName}")
            false
        } finally {
            connection.disconnect()
        }
    }

    /*@UiThread
    fun saveChangesWhenLeaving() {
        coreContext.postOnCoreThread {
            if (::account.isInitialized) {
                val params = account.params
                val copy = params.clone()

                val address = params.identityAddress?.clone()
                if (address != null) {
                    val newValue = displayName.value.orEmpty().trim()
                    address.displayName = newValue
                    copy.identityAddress = address
                    // This will trigger a REGISTER, so account display name will be updated by
                    // CoreListener.onAccountRegistrationStateChanged everywhere in the app
                    Log.i(
                        "$TAG Updated account [${params.identityAddress?.asStringUriOnly()}] identity address display name [$newValue]"
                    )
                }

                account.params = copy
                account.refreshRegister()
            }
        }
    }*/

    @UiThread
    fun saveChangesWhenLeaving() {
        if (signOutInProgress.value == true) return

        // Read the UI value before switching threads.
        val newDisplayName = displayName.value.orEmpty().trim()

        coreContext.postOnCoreThread { core ->
            if (!::account.isInitialized) return@postOnCoreThread

            // Never update an account that has already been removed.
            if (core.accountList.none { it == account }) {
                return@postOnCoreThread
            }

            // Do not modify registration while signing out or disabled.
            val params = account.params
            if (!params.isRegisterEnabled) return@postOnCoreThread

            val address = params.identityAddress?.clone()
                ?: return@postOnCoreThread

            if (address.displayName.orEmpty() == newDisplayName) {
                return@postOnCoreThread
            }

            address.displayName = newDisplayName

            val updatedParams = params.clone()
            updatedParams.identityAddress = address
            account.params = updatedParams
        }
    }

    @UiThread
    fun toggleDetailsExpand() {
        expandDetails.value = expandDetails.value == false
    }

    @UiThread
    fun toggleDevicesExpand() {
        expandDevices.value = expandDevices.value == false
    }

    @UiThread
    fun toggleRegister() {
        coreContext.postOnCoreThread { core ->
            if (!::account.isInitialized || core.accountList.none { it == account }) {
                return@postOnCoreThread
            }
            val params = account.params
            val copy = params.clone()
            copy.isRegisterEnabled = !params.isRegisterEnabled
            Log.i(
                "$TAG Account registration is now [${if (copy.isRegisterEnabled) "enabled" else "disabled"}] for account [${account.params.identityAddress?.asStringUriOnly()}]"
            )
            account.params = copy
            registerEnabled.postValue(account.params.isRegisterEnabled)

            if (!core.isNetworkReachable) {
                Log.w("$TAG Network is not reachable, updating registration state to reflect that")
                // To reflect the difference between Disabled & Disconnected
                accountModel.value?.updateRegistrationState()
            }
        }
    }

    @UiThread
    fun showDebugInfo(): Boolean {
        showDeviceId.value = true
        return true
    }

    @WorkerThread
    private fun requestDevicesList(identityAddress: Address) {
        Log.i(
            "$TAG Request devices list for identity address [${identityAddress.asStringUriOnly()}]"
        )
        accountManagerServices = coreContext.core.createAccountManagerServices()
        accountManagerServices.language = Locale.getDefault().language // Returns en, fr, etc...
        val request = accountManagerServices.createGetDevicesListRequest(
            identityAddress
        )
        request.addListener(accountManagerServicesListener)
        request.submit()
    }
}
