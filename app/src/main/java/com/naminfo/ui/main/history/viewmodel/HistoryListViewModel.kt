package com.naminfo.ui.main.history.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.contacts.ContactsManager
import org.linphone.core.Address
import org.linphone.core.CallLog
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Friend
import org.linphone.core.GlobalState
import org.linphone.core.MagicSearch
import org.linphone.core.MagicSearchListenerStub
import org.linphone.core.SearchResult
import org.linphone.core.tools.Log
import com.naminfo.ui.main.contacts.model.ContactAvatarModel
import com.naminfo.ui.main.history.model.CallLogModel
import com.naminfo.ui.main.history.model.CallLogModelWrapper
import com.naminfo.ui.main.history.model.TranslationCallHistoryMetadata
import com.naminfo.ui.main.model.ConversationContactOrSuggestionModel
import com.naminfo.ui.main.viewmodel.AbstractMainViewModel
import com.naminfo.R
import com.naminfo.utils.AppUtils
import com.naminfo.utils.Event
import com.naminfo.utils.LinphoneUtils
import java.text.Collator
import java.util.Locale

enum class CallHistoryFilterMode {
    ALL,
    REGULAR,
    T_CALLS
}

class HistoryListViewModel
    @UiThread
    constructor() : AbstractMainViewModel() {
    companion object {
        private const val TAG = "[History List ViewModel]"
    }

    val selectedHistoryFilterMode = MutableLiveData<CallHistoryFilterMode>(CallHistoryFilterMode.ALL)

    val isSelectionMode = MutableLiveData<Boolean>(false)
    val selectedCount = MutableLiveData<Int>(0)
    val selectedCountTitle = MutableLiveData<String>("")
    val isAllSelected = MutableLiveData<Boolean>(false)
    private val selectedCallLogIds = HashSet<String>()

    val callLogs = MutableLiveData<ArrayList<CallLogModelWrapper>>()

    val fetchInProgress = MutableLiveData<Boolean>()

    val historyInsertedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val historyDeletedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    private val tempCallLogsList = ArrayList<CallLogModelWrapper>()

    private val magicSearch = coreContext.core.createMagicSearch()

    private val magicSearchListener = object : MagicSearchListenerStub() {
        @WorkerThread
        override fun onSearchResultsReceived(magicSearch: MagicSearch) {
            Log.i("$TAG Magic search contacts available")
            val results = magicSearch.lastSearch
            processMagicSearchResults(results)
            fetchInProgress.postValue(false)
        }
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState?, message: String) {
            if (state == GlobalState.On) {
                Log.i("$TAG Core just started, fetching history")
                computeCallLogsList(currentFilter)
            }
        }

        @WorkerThread
        override fun onCallLogUpdated(core: Core, callLog: CallLog) {
            Log.i("$TAG A call log was created, updating list")
            computeCallLogsList(currentFilter)
            historyInsertedEvent.postValue(Event(true))
        }
    }

    private val contactsListener = object : ContactsManager.ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
            Log.i("$TAG Contacts have been (re)loaded, updating list")
            computeCallLogsList(currentFilter)
        }

        @WorkerThread
        override fun onContactFoundInRemoteDirectory(friend: Friend) { }
    }

    init {
        fetchInProgress.value = true

        coreContext.postOnCoreThread { core ->
            coreContext.contactsManager.addListener(contactsListener)
            core.addListener(coreListener)
            magicSearch.addListener(magicSearchListener)

            computeCallLogsList(currentFilter)
        }
    }

    @UiThread
    override fun onCleared() {
        super.onCleared()

        coreContext.postOnCoreThread { core ->
            coreContext.contactsManager.removeListener(contactsListener)
            core.removeListener(coreListener)
            magicSearch.removeListener(magicSearchListener)
        }
    }

    @UiThread
    fun removeAllCallLogs() {
        coreContext.postOnCoreThread { core ->
            val account = LinphoneUtils.getDefaultAccount()
            account?.clearCallLogs()
            core.clearCallLogs()

            historyDeletedEvent.postValue(Event(true))
            computeCallLogsList(currentFilter)
        }
    }

    @UiThread
    fun selectHistoryFilterMode(mode: CallHistoryFilterMode) {
        selectedHistoryFilterMode.value = mode
        filter()
    }

    @UiThread
    fun enterSelectionMode(initialModel: CallLogModel? = null) {
        selectedCallLogIds.clear()
        if (initialModel != null) {
            selectedCallLogIds.add(initialModel.id.orEmpty())
        }
        isSelectionMode.value = true
        updateSelectionState()
    }

    @UiThread
    fun exitSelectionMode() {
        selectedCallLogIds.clear()
        isSelectionMode.value = false
        updateSelectionState()
    }

    @UiThread
    fun toggleSelection(model: CallLogModel) {
        val modelId = model.id.orEmpty()
        if (selectedCallLogIds.contains(modelId)) {
            selectedCallLogIds.remove(modelId)
        } else {
            selectedCallLogIds.add(modelId)
        }
        updateSelectionState()
    }

    @UiThread
    fun toggleSelectAll() {
        val currentLogs = callLogs.value.orEmpty().mapNotNull { it.callLogModel }
        if (isAllSelected.value == true) {
            selectedCallLogIds.clear()
        } else {
            selectedCallLogIds.clear()
            selectedCallLogIds.addAll(currentLogs.mapNotNull { it.id })
        }
        updateSelectionState()
    }

    fun updateSelectionState() {
        val count = selectedCallLogIds.size
        selectedCount.postValue(count)
        selectedCountTitle.postValue(AppUtils.getString(R.string.history_selected_count).format(count))

        val totalCount = callLogs.value.orEmpty().mapNotNull { it.callLogModel }.size
        isAllSelected.postValue(totalCount > 0 && count == totalCount)

        for (wrapper in callLogs.value.orEmpty()) {
            val model = wrapper.callLogModel ?: continue
            val selected = selectedCallLogIds.contains(model.id.orEmpty())
            model.isSelected.postValue(selected)
            model.isSelectionMode.postValue(isSelectionMode.value == true)
        }
    }

    @UiThread
    fun deleteSelectedCallLogs() {
        val allDisplayedModels = callLogs.value.orEmpty().mapNotNull { it.callLogModel }
        val selectedModels = allDisplayedModels.filter { selectedCallLogIds.contains(it.id.orEmpty()) }

        coreContext.postOnCoreThread { core ->
            for (model in selectedModels) {
                val logsToRemove = core.callLogs.filter { it.remoteAddress.weakEqual(model.address) }
                for (log in logsToRemove) {
                    core.removeCallLog(log)
                }
            }

            coreContext.postOnMainThread {
                exitSelectionMode()
            }
            computeCallLogsList(currentFilter)
        }
    }

    @UiThread
    override fun filter() {
        coreContext.postOnCoreThread {
            computeCallLogsList(currentFilter)
        }
    }

    @WorkerThread
    private fun computeCallLogsList(filter: String) {
        if (coreContext.core.globalState != GlobalState.On) {
            Log.e("$TAG Core isn't ON yet, do not attempt to get calls history")
            return
        }

        if (callLogs.value.orEmpty().isEmpty()) {
            fetchInProgress.postValue(true)
        }

        val isFilterEmpty = filter.isEmpty()
        if (!isFilterEmpty) {
            magicSearch.getContactsListAsync(
                filter,
                corePreferences.contactsFilter,
                MagicSearch.Source.All.toInt(),
                MagicSearch.Aggregation.Friend
            )
        }

        val list = arrayListOf<CallLogModelWrapper>()

        val account = LinphoneUtils.getDefaultAccount()
        // History remains on this device after sign-out; only show this account's calls.
        val localAddress = account?.params?.identityAddress
        val logs = coreContext.core.callLogs.filter {
            localAddress?.weakEqual(it.localAddress) == true
        }

        val mode = selectedHistoryFilterMode.value ?: CallHistoryFilterMode.ALL
        val filteredLogs = logs.filter { log ->
            val username = log.remoteAddress.username.orEmpty()
            val isTCall = username.startsWith("T_", ignoreCase = true) ||
                username.startsWith("tac_", ignoreCase = true) ||
                username.startsWith("tvc_", ignoreCase = true) ||
                TranslationCallHistoryMetadata.isTranslationCall(
                    coreContext.context,
                    log.callId ?: log.refKey
                )
            when (mode) {
                CallHistoryFilterMode.ALL -> true
                CallHistoryFilterMode.REGULAR -> !isTCall
                CallHistoryFilterMode.T_CALLS -> isTCall
            }
        }

        for (callLog in filteredLogs) {
            val model = CallLogModel(callLog)
            val alreadyDisplayed = list.any { wrapper ->
                wrapper.callLogModel?.let { displayedModel ->
                    displayedModel.address.weakEqual(model.address) &&
                        displayedModel.wasConference == model.wasConference &&
                        (
                            !model.wasConference ||
                            displayedModel.isVideoCall == model.isVideoCall
                        )
                } == true
            }
            if (!alreadyDisplayed && isCallLogMatchingFilter(model, filter)) {
                list.add(CallLogModelWrapper(model))
            }
        }

        Log.i("$TAG Fetched [${list.size}] call log(s)")
        if (isFilterEmpty) {
            callLogs.postValue(list)
        } else {
            fetchInProgress.postValue(true)
            tempCallLogsList.clear()
            tempCallLogsList.addAll(list)
        }
    }

    @WorkerThread
    private fun isCallLogMatchingFilter(model: CallLogModel, filter: String): Boolean {
        if (filter.isEmpty()) return true

        val friendName = model.avatarModel.friend.name ?: LinphoneUtils.getDisplayName(model.address)
        return friendName.contains(filter, ignoreCase = true) || model.address.asStringUriOnly().contains(filter, ignoreCase = true)
    }

    @WorkerThread
    private fun processMagicSearchResults(results: Array<SearchResult>) {
        Log.i("$TAG Processing [${results.size}] results")

        val contactsList = arrayListOf<CallLogModelWrapper>()
        val suggestionsList = arrayListOf<CallLogModelWrapper>()
        val requestList = arrayListOf<CallLogModelWrapper>()

        val defaultAccountDomain = LinphoneUtils.getDefaultAccount()?.params?.domain
        for (result in results) {
            val address = result.address
            val friend = result.friend
            if (friend != null) {
                val found = contactsList.find { it.contactModel?.friend == friend }
                if (found != null) continue

                val mainAddress = address ?: LinphoneUtils.getFirstAvailableAddressForFriend(friend)
                if (mainAddress != null) {
                    val model = ConversationContactOrSuggestionModel(mainAddress, friend = friend)
                    val avatarModel = coreContext.contactsManager.getContactAvatarModelForFriend(
                        friend
                    )
                    model.avatarModel.postValue(avatarModel)
                    contactsList.add(CallLogModelWrapper(null, model))
                } else {
                    Log.w("$TAG Found friend [${friend.name}] in search results but no Address could be found, skipping it")
                }
            } else if (address != null) {
                if (result.sourceFlags == MagicSearch.Source.Request.toInt()) {
                    val model = ConversationContactOrSuggestionModel(address) {
                        coreContext.startAudioCall(address)
                    }
                    val avatarModel = getContactAvatarModelForAddress(address)
                    model.avatarModel.postValue(avatarModel)
                    requestList.add(CallLogModelWrapper(null, model))
                    continue
                }

                val defaultAccountAddress = coreContext.core.defaultAccount?.params?.identityAddress
                if (defaultAccountAddress != null && address.weakEqual(defaultAccountAddress)) {
                    Log.i("$TAG Removing from suggestions current default account address")
                    continue
                }

                val model = ConversationContactOrSuggestionModel(address, defaultAccountDomain = defaultAccountDomain) {
                    coreContext.startAudioCall(address)
                }
                val avatarModel = getContactAvatarModelForAddress(address)
                model.avatarModel.postValue(avatarModel)
                suggestionsList.add(CallLogModelWrapper(null, model))
            }
        }

        val collator = Collator.getInstance(Locale.getDefault())
        contactsList.sortWith { model1, model2 ->
            collator.compare(model1.contactModel?.name, model2.contactModel?.name)
        }
        suggestionsList.sortWith { model1, model2 ->
            collator.compare(model1.contactModel?.name, model2.contactModel?.name)
        }

        val list = arrayListOf<CallLogModelWrapper>()
        list.addAll(tempCallLogsList)
        list.addAll(contactsList)
        list.addAll(suggestionsList)
        list.addAll(requestList)
        callLogs.postValue(list)
        Log.i(
            "$TAG Processed [${results.size}] results: [${contactsList.size}] contacts and [${suggestionsList.size}] suggestions"
        )
    }

    @WorkerThread
    private fun getContactAvatarModelForAddress(address: Address): ContactAvatarModel {
        val fakeFriend = coreContext.core.createFriend()
        fakeFriend.name = LinphoneUtils.getDisplayName(address)
        fakeFriend.address = address
        return ContactAvatarModel(fakeFriend)
    }
}
