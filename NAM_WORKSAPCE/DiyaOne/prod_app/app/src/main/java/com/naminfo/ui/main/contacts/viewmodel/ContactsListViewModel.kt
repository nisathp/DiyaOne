package com.naminfo.ui.main.contacts.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.Collator
import java.util.ArrayList
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.DiyaOneApplication.Companion.corePreferences
import com.naminfo.R
import com.naminfo.contacts.ContactsManager.ContactsListener
import com.naminfo.contacts.MobionContactsService
import com.naminfo.contacts.PhoneContactNumbers
import org.linphone.core.Friend
import org.linphone.core.FriendList
import org.linphone.core.FriendListListenerStub
import org.linphone.core.Factory
import org.linphone.core.MagicSearch
import org.linphone.core.MagicSearchListenerStub
import org.linphone.core.SearchResult
import org.linphone.core.tools.Log
import com.naminfo.ui.main.contacts.model.ContactAvatarModel
import com.naminfo.ui.main.viewmodel.AbstractMainViewModel
import com.naminfo.utils.Event
import com.naminfo.utils.FileUtils

class ContactsListViewModel
    @UiThread
    constructor() : AbstractMainViewModel() {
    companion object {
        private const val TAG = "[Contacts List ViewModel]"
        private const val MOBION_FRIEND_LIST = "Mobion contacts"
        private const val CONFERENCE_FRIEND_LIST = "Conference"

        @JvmStatic
        fun resetMobionSessionCache() {
            MobionContactsService.clearContactsCache()
        }
    }

    val contactsList = MutableLiveData<ArrayList<ContactAvatarModel>>()

    val conferenceList = MutableLiveData<ArrayList<ContactAvatarModel>>()

    val favouritesList = MutableLiveData<ArrayList<ContactAvatarModel>>()

    val fetchInProgress = MutableLiveData<Boolean>()

    val showFavourites = MutableLiveData<Boolean>()

    val showFilter = MutableLiveData<Boolean>()

    val isListFiltered = MutableLiveData<Boolean>()

    val areAllContactsDisplayed = MutableLiveData<Boolean>()

    val searchInProgress = MutableLiveData<Boolean>()

    val isDefaultAccountLinphone = MutableLiveData<Boolean>()

    val showResultsLimitReached = MutableLiveData<Boolean>()

    val disableAddContact = MutableLiveData<Boolean>()

    val vCardTerminatedEvent: MutableLiveData<Event<Pair<String, File>>> by lazy {
        MutableLiveData()
    }

    val cardDavSynchronizationCompletedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    private var previousFilter = "NotSet"
    private var domainFilter = ""

    private lateinit var magicSearch: MagicSearch

    private lateinit var favouritesMagicSearch: MagicSearch

    private var firstLoad = true

    private val contactModels = hashMapOf<Friend, ContactAvatarModel>()

    private val mobionContactsRefreshObserver = Observer<Long> {
        refreshMobionContacts(forceRefresh = true)
    }

    private val magicSearchListener = object : MagicSearchListenerStub() {
        @WorkerThread
        override fun onSearchResultsReceived(magicSearch: MagicSearch) {
            Log.i("$TAG Magic search contacts available")
            processMagicSearchResults(magicSearch.lastSearch, favourites = false)
        }

        @WorkerThread
        override fun onResultsLimitReached(magicSearch: MagicSearch, sourcesFlag: Int) {
            Log.w("$TAG Results limit reached (configured limit is [${magicSearch.searchLimit}]) for source(s) [$sourcesFlag], user should refine it's search")
            if (searchFilter.value.orEmpty().isNotEmpty()) {
                showResultsLimitReached.postValue(true)
            }
        }
    }

    private val favouritesMagicSearchListener = object : MagicSearchListenerStub() {
        @WorkerThread
        override fun onSearchResultsReceived(magicSearch: MagicSearch) {
            Log.i("$TAG Magic search favourites contacts available")
            processMagicSearchResults(magicSearch.lastSearch, favourites = true)
        }
    }

    private val friendListListener = object : FriendListListenerStub() {
        @WorkerThread
        override fun onSyncStatusChanged(
            friendList: FriendList,
            status: FriendList.SyncStatus?,
            message: String?
        ) {
            Log.i("$TAG Synchronization status changed to [$status] for friend list [${friendList.displayName}] with message [$message]")
            if (status == FriendList.SyncStatus.Successful || status == FriendList.SyncStatus.Failure) {
                friendList.removeListener(this)
                cardDavSynchronizationCompletedEvent.postValue(Event(true))
            }
            // TODO FIXME: alert user when failure ?
        }
    }

    private val contactsListener = object : ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
            Log.i("$TAG Contacts have been (re)loaded, updating list")
            magicSearch.resetSearchCache()
            favouritesMagicSearch.resetSearchCache()

            applyFilter(
                currentFilter,
                domainFilter,
                true
            )
        }

        @WorkerThread
        override fun onContactFoundInRemoteDirectory(friend: Friend) { }
    }

    init {
        fetchInProgress.value = true
        showFavourites.value = corePreferences.showFavoriteContacts
        showFilter.value = false
        disableAddContact.value = corePreferences.disableAddContact
        isListFiltered.value = false

        coreContext.postOnCoreThread { core ->
            ensurePermanentConferenceContacts()
            domainFilter = ""
            areAllContactsDisplayed.postValue(true)
            checkIfDefaultAccountOnDefaultDomain()

            coreContext.contactsManager.addListener(contactsListener)
            magicSearch = core.createMagicSearch()
            magicSearch.limitedSearch = false
            magicSearch.searchLimit = corePreferences.magicSearchResultsLimit
            magicSearch.addListener(magicSearchListener)

            favouritesMagicSearch = core.createMagicSearch()
            favouritesMagicSearch.limitedSearch = false
            favouritesMagicSearch.addListener(favouritesMagicSearchListener)

            applyFilter(currentFilter, domainFilter)
        }

        refreshMobionContacts()
        MobionContactsService.contactsRefreshRequested.observeForever(mobionContactsRefreshObserver)
        // refreshConferenceContacts()
    }

    @UiThread
    override fun onCleared() {
        MobionContactsService.contactsRefreshRequested.removeObserver(mobionContactsRefreshObserver)
        coreContext.postOnCoreThread {
            magicSearch.removeListener(magicSearchListener)
            favouritesMagicSearch.removeListener(favouritesMagicSearchListener)
            coreContext.contactsManager.removeListener(contactsListener)
            contactModels.values.forEach(ContactAvatarModel::destroy)
            contactModels.clear()
        }
        super.onCleared()
    }

    @UiThread
    override fun filter() {
        isListFiltered.value = currentFilter.isNotEmpty()
        coreContext.postOnCoreThread {
            applyFilter(currentFilter, domainFilter)
        }
    }

    @UiThread
    fun applyCurrentDefaultAccountFilter() {
        refreshMobionContacts()
        // refreshConferenceContacts()
        coreContext.postOnCoreThread {
            ensurePermanentConferenceContacts()
            domainFilter = ""
            areAllContactsDisplayed.postValue(true)
            checkIfDefaultAccountOnDefaultDomain()

            applyFilter(currentFilter, domainFilter)
        }
    }

    @UiThread
    fun changeContactsFilter(onlyLinphoneContacts: Boolean, onlySipContacts: Boolean) {
        coreContext.postOnCoreThread {
            domainFilter = if (onlyLinphoneContacts) {
                corePreferences.defaultDomain
            } else if (onlySipContacts) {
                "*"
            } else {
                ""
            }
            areAllContactsDisplayed.postValue(domainFilter.isEmpty())
            corePreferences.contactsFilter = domainFilter
            Log.i("$TAG Newly set filter is [${corePreferences.contactsFilter}]")

            coreContext.postOnCoreThread {
                applyFilter(currentFilter, domainFilter, filterChanged = true)
            }
        }
    }

    @UiThread
    fun toggleFavouritesVisibility() {
        val show = showFavourites.value == false
        showFavourites.value = show
        
        coreContext.postOnCoreThread {
            corePreferences.showFavoriteContacts = show
        }
    }

    @UiThread
    fun exportContactAsVCard(friend: Friend) {
        coreContext.postOnCoreThread {
            val vCard = friend.dumpVcard()
            if (!vCard.isNullOrEmpty()) {
                Log.i("$TAG Friend has been successfully dumped as vCard string")
                val fileName = friend.name.orEmpty().replace(" ", "_").lowercase(
                    Locale.getDefault()
                )
                val file = FileUtils.getFileStorageCacheDir(
                    "$fileName.vcf",
                    overrideExisting = true
                )
                viewModelScope.launch {
                    if (FileUtils.dumpStringToFile(vCard, file)) {
                        Log.i("$TAG vCard string saved as file in cache folder")
                        vCardTerminatedEvent.postValue(Event(Pair(friend.name.orEmpty(), file)))
                    } else {
                        Log.e("$TAG Failed to save vCard string as file in cache folder")
                    }
                }
            } else {
                Log.e("$TAG Failed to dump contact as vCard string")
            }
        }
    }

    @UiThread
    fun toggleContactFavoriteFlag(contactModel: ContactAvatarModel) {
        coreContext.postOnCoreThread {
            contactModel.friend.edit()
            val starred = !contactModel.friend.starred
            Log.i(
                "$TAG Friend [${contactModel.name.value}] will be [${if (starred) "added to" else "removed from"}] favourites"
            )
            contactModel.friend.starred = starred
            contactModel.friend.done()
            coreContext.contactsManager.notifyContactsListChanged()
        }
    }

    @UiThread
    fun deleteContact(contactModel: ContactAvatarModel) {
        coreContext.postOnCoreThread {
            if (contactModel.friend.refKey.orEmpty().startsWith("conference:permanent:")) return@postOnCoreThread
            Log.w("$TAG Removing friend [${contactModel.contactName}]")
            coreContext.contactsManager.contactRemoved(contactModel.friend)
            contactModel.friend.remove()
            coreContext.contactsManager.notifyContactsListChanged()
            showGreenToast(R.string.contact_deleted_toast, R.drawable.warning_circle)
        }
    }

    @UiThread
    fun refreshCardDavContacts() {
        coreContext.postOnCoreThread { core ->
            for (friendList in core.friendsLists) {
                if (friendList.type == FriendList.Type.CardDAV) {
                    Log.i("$TAG Found CardDAV friend list [${friendList.displayName}], starting update")
                    friendList.addListener(friendListListener)
                    friendList.synchronizeFriendsFromServer()
                }
            }
        }
    }

    private fun refreshMobionContacts(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            try {
                val (contacts, nativePhoneNumbers) = withContext(Dispatchers.IO) {
                    val phoneNumbers = if (PhoneContactNumbers.hasPermission(coreContext.context)) {
                        PhoneContactNumbers.read(coreContext.context)
                    } else {
                        emptySet()
                    }
                    MobionContactsService.fetchContacts(forceRefresh) to phoneNumbers
                }
                val matchedContacts = contacts.filter { it.mobileNumber in nativePhoneNumbers }
                Log.i("$TAG Fetched [${contacts.size}] contacts from Mobion, phonebook matches=[${matchedContacts.size}]")

                if (matchedContacts.isEmpty()) {
                    Log.w(
                        "$TAG Mobion returned an empty dataset; keeping the existing cached contacts"
                    )
                    return@launch
                }

                coreContext.postOnCoreThread { core ->
                    val friendList = core.getFriendListByName(MOBION_FRIEND_LIST)
                        ?: core.createFriendList().also { list ->
                            list.displayName = MOBION_FRIEND_LIST
                            list.isDatabaseStorageEnabled = true
                            list.type = FriendList.Type.Default
                            core.addFriendList(list)
                        }
                    friendList.isSubscriptionsEnabled = true

                    val sipDomain = core.defaultAccount?.params?.identityAddress?.domain
                        ?: corePreferences.defaultDomain

                    Log.i(
                        "$TAG PRESENCE ACCOUNT: " +
                                "identity=[${core.defaultAccount?.params?.identityAddress?.asStringUriOnly()}], " +
                                "domain=[$sipDomain]"
                    )

                    val friends = matchedContacts.mapNotNull { contact ->
                        val address = core.interpretUrl(
                            "sip:${contact.mobileNumber}@$sipDomain",
                            false
                        ) ?: return@mapNotNull null

                        core.createFriend().apply {
                            name = contact.name
                            refKey = "mobion:${contact.mobileNumber}"
                            isSubscribesEnabled = true
                            // A null URL is meaningful after a remote delete; clear any old
                            // local Friend photo instead of keeping a stale image.
                            photo = contact.profilePictureUrl
                            addAddress(address)
                            addPhoneNumberWithLabel(
                                Factory.instance().createFriendPhoneNumber(
                                    contact.mobileNumber,
                                    "Mobile"
                                )
                            )
                        }
                    }.toTypedArray()

                    friendList.synchronizeFriendsWith(friends)
                    Log.i(
                        "$TAG PRESENCE: Mobion FriendList " +
                                "friends=${friendList.friends.size}, " +
                                "subscriptionsEnabled=${friendList.isSubscriptionsEnabled}"
                    )

                    for (friend in friendList.friends) {

                        friend.isSubscribesEnabled = true

                        Log.i(
                            "$TAG PRESENCE: friend=[${friend.name}] " +
                                    "refKey=[${friend.refKey}] " +
                                    "address=[${friend.address?.asStringUriOnly()}] " +
                                    "subscribe=[${friend.isSubscribesEnabled}] " +
                                    "presence=[${friend.consolidatedPresence}]"
                        )
                    }

                    Log.i("$TAG PRESENCE: calling Mobion updateSubscriptions()")

                    friendList.updateSubscriptions()
                    core.config.sync()
                    coreContext.contactsManager.notifyContactsListChanged()

                    // The first search is started before the remote contacts request completes.
                    // ContactsManager notifications can be coalesced during startup, so make sure
                    // this ViewModel also invalidates its own search and publishes the new list.
                    refreshSearchResults()
                }

            } catch (exception: Exception) {
                Log.e("$TAG Failed to fetch Mobion contacts: $exception")
            }
        }
    }

    @WorkerThread
    private fun ensurePermanentConferenceContacts() {
        val core = coreContext.core
        val listName = "Permanent Conferences"
        val friendList = core.getFriendListByName(listName)
            ?: core.createFriendList().also {
                it.displayName = listName
                it.isDatabaseStorageEnabled = true
                it.type = FriendList.Type.Default
                core.addFriendList(it)
            }
        val domain = core.defaultAccount?.params?.identityAddress?.domain
            ?: corePreferences.defaultDomain
        val friends = listOf("3500" to "Video Conference", "5500" to "Audio Conference")
            .mapNotNull { (number, label) ->
                val address = core.interpretUrl("sip:$number@$domain", false)
                    ?: return@mapNotNull null
                core.createFriend().apply {
                    name = label
                    refKey = "conference:permanent:$number"
                    addAddress(address)
                }
            }.toTypedArray()
        friendList.synchronizeFriendsWith(friends)
        core.config.sync()
    }

    private fun refreshConferenceContacts() {
        viewModelScope.launch {
            val registeredNumber = coreContext.core.defaultAccount
                ?.params
                ?.identityAddress
                ?.username
                ?.filter(Char::isDigit)
                .orEmpty()
            if (registeredNumber.isEmpty()) {
                Log.w("$TAG No registered number available, skipping Conference contacts")
                return@launch
            }

            try {
                val conferences = withContext(Dispatchers.IO) {
                    MobionContactsService.fetchConferences(registeredNumber)
                }
                Log.i("$TAG Fetched [${conferences.size}] conferences for [$registeredNumber]")

                coreContext.postOnCoreThread { core ->
                    val friendList = core.getFriendListByName(CONFERENCE_FRIEND_LIST)
                        ?: core.createFriendList().also { list ->
                            list.displayName = CONFERENCE_FRIEND_LIST
                            list.isDatabaseStorageEnabled = true
                            list.type = FriendList.Type.Default
                            core.addFriendList(list)
                        }

                    val sipDomain = core.defaultAccount?.params?.identityAddress?.domain
                        ?: corePreferences.defaultDomain
                    val friends = conferences.mapNotNull { conference ->
                        if (conference.groupNumber in setOf("3500", "5500")) return@mapNotNull null
                        val address = core.interpretUrl(
                            "sip:${conference.groupNumber}@$sipDomain",
                            false
                        ) ?: return@mapNotNull null

                        core.createFriend().apply {
                            name = conference.groupName
                            refKey = "conference:${conference.id}"
                            addAddress(address)
                            addPhoneNumberWithLabel(
                                Factory.instance().createFriendPhoneNumber(
                                    conference.groupNumber,
                                    conference.type.ifEmpty { CONFERENCE_FRIEND_LIST }
                                )
                            )
                        }
                    }.toTypedArray()

                    friendList.synchronizeFriendsWith(friends)
                    friendList.updateSubscriptions()
                    core.config.sync()
                    coreContext.contactsManager.notifyContactsListChanged()
                    refreshSearchResults()
                }
            } catch (exception: Exception) {
                Log.e("$TAG Failed to fetch Conference contacts: $exception")
            }
        }
    }

    @WorkerThread
    private fun applyFilter(
        filter: String,
        domain: String,
        filterChanged: Boolean = false
    ) {
        if (contactsList.value.orEmpty().isEmpty()) {
            fetchInProgress.postValue(true)
        }

        if (previousFilter.isNotEmpty() && (
            previousFilter.length > filter.length ||
                (previousFilter.length == filter.length && previousFilter != filter)
            )
        ) {
            magicSearch.resetSearchCache()
        }
        currentFilter = filter
        previousFilter = filter

        Log.i(
            "$TAG Asking Magic search for contacts matching filter [$filter], domain [$domain] and in sources Friends/LDAP/CardDAV"
        )
        searchInProgress.postValue(filter.isNotEmpty())
        showResultsLimitReached.postValue(false)

        if (filter.isEmpty() && (favouritesList.value.orEmpty().isEmpty() || filterChanged)) {
            favouritesMagicSearch.getContactsListAsync(
                filter,
                domain,
                MagicSearch.Source.FavoriteFriends.toInt(),
                MagicSearch.Aggregation.Friend
            )
        }

        magicSearch.getContactsListAsync(
            filter,
            domain,
            MagicSearch.Source.Friends.toInt() or MagicSearch.Source.LdapServers.toInt() or MagicSearch.Source.RemoteCardDAV.toInt(),
            MagicSearch.Aggregation.Friend
        )
    }

    @WorkerThread
    private fun refreshSearchResults() {
        magicSearch.resetSearchCache()
        favouritesMagicSearch.resetSearchCache()
        applyFilter(currentFilter, domainFilter, filterChanged = true)
    }

    @WorkerThread
    private fun processMagicSearchResults(results: Array<SearchResult>, favourites: Boolean) {
        // Reuse models so presence updates do not attach duplicate friend listeners.
        Log.i("$TAG Processing [${results.size}] results, favourites is [$favourites]")

        val list = arrayListOf<ContactAvatarModel>()
        val conferences = arrayListOf<ContactAvatarModel>()
        val collator = Collator.getInstance(Locale.getDefault())
        val hideEmptyContacts = corePreferences.hideContactsWithoutPhoneNumberOrSipAddress
        for (result in results) {
            val friend = result.friend ?: continue
            if (friend.refKey.orEmpty().startsWith("conference:permanent:")) {
                continue
            }
            if (friend != null) {
                val refKey = friend.refKey.orEmpty()
                val isMobionContact = refKey.startsWith("mobion:")
                val isConferenceContact = refKey.startsWith("conference:")
                if (!isMobionContact && !isConferenceContact) {
                    continue
                }
                if (hideEmptyContacts && friend.addresses.isEmpty() && friend.phoneNumbers.isEmpty()) {
                    Log.i("$TAG Friend [${friend.name}] has no SIP address nor phone number, do not show it")
                    continue
                }

                if (friend.refKey.orEmpty().isEmpty()) {
                    if (friend.vcard != null) {
                        friend.vcard?.generateUniqueId()
                        friend.refKey = friend.vcard?.uid
                    } else {
                        Log.w(
                            "$TAG Friend [${friend.name}] found in SearchResults doesn't have a refKey, using name instead"
                        )
                        friend.refKey = friend.name
                    }
                }
            }

            val model = if (friend != null) {
                contactModels.getOrPut(friend) {
                    coreContext.contactsManager.getContactAvatarModelForFriend(friend)
                }.also { it.update(result.address) }
            } else {
                coreContext.contactsManager.getContactAvatarModelForAddress(result.address)
            }
            if (friend?.refKey.orEmpty().startsWith("conference:")) {
                model.forceConferenceIcon.postValue(true)
            }
            model.refreshSortingName()
            val starred = friend?.starred == true
            model.isFavourite.postValue(starred)

            if (friend?.refKey.orEmpty().startsWith("conference:")) {
                conferences.add(model)
            } else {
                list.add(model)
            }
        }

        list.sortWith { model1, model2 ->
            val online1 = model1.friend.consolidatedPresence == org.linphone.core.ConsolidatedPresence.Online
            val online2 = model2.friend.consolidatedPresence == org.linphone.core.ConsolidatedPresence.Online
            if (online1 != online2) {
                if (online1) -1 else 1
            } else {
                collator.compare(model1.getNameToUseForSorting(), model2.getNameToUseForSorting())
            }
        }
        conferences.sortWith { model1, model2 ->
            collator.compare(model1.getNameToUseForSorting(), model2.getNameToUseForSorting())
        }

        searchInProgress.postValue(false)
        if (favourites) {
            favouritesList.postValue(list)
        } else {
            // contactsList.postValue(list)
            conferenceList.postValue(conferences)
            firstLoad = false
        }

        Log.i("$TAG Processed [${results.size}] results into [${list.size} contacts]")
    }

    @WorkerThread
    private fun checkIfDefaultAccountOnDefaultDomain() {
        val defaultAccount = coreContext.core.defaultAccount
        val defaultDomain = corePreferences.defaultDomain
        val isAccountOnDefaultDomain = defaultAccount?.params?.domain == defaultDomain
        isDefaultAccountLinphone.postValue(isAccountOnDefaultDomain)
    }
}
