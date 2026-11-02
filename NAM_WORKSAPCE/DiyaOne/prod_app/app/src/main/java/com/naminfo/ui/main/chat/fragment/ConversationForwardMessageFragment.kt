/*
 * Copyright (c) 2010-2024 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.naminfo.ui.main.chat.fragment

import android.Manifest
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.naminfo.R
import com.naminfo.contacts.PhoneContactNumbers
import org.linphone.core.tools.Log
import com.naminfo.databinding.ChatMessageForwardFragmentBinding
import com.naminfo.ui.main.adapter.ConversationsContactsAndSuggestionsListAdapter
import com.naminfo.ui.main.chat.viewmodel.ConversationForwardMessageViewModel
import com.naminfo.ui.main.contacts.viewmodel.MatchedContactsViewModel
import com.naminfo.ui.main.contacts.model.ContactNumberOrAddressModel
import com.naminfo.ui.main.contacts.model.NumberOrAddressPickerDialogModel
import com.naminfo.ui.main.fragment.SlidingPaneChildFragment
import com.naminfo.utils.DialogUtils
import com.naminfo.utils.RecyclerViewHeaderDecoration

@UiThread
class ConversationForwardMessageFragment : SlidingPaneChildFragment() {
    companion object {
        private const val TAG = "[Conversation Forward Message Fragment]"
        private const val PERMISSION_REQUESTED = "forward_contacts_permission_requested"
    }

    private lateinit var binding: ChatMessageForwardFragmentBinding

    private lateinit var viewModel: ConversationForwardMessageViewModel

    private lateinit var matchedViewModel: MatchedContactsViewModel

    private lateinit var adapter: ConversationsContactsAndSuggestionsListAdapter

    private var numberOrAddressPickerDialog: Dialog? = null

    private var disableConsumingEventOnPause = false

    private var contactsPermissionRequested = false
    private var permissionRequestInProgress = false

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permissionRequestInProgress = false
        if (::matchedViewModel.isInitialized) matchedViewModel.reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapter = ConversationsContactsAndSuggestionsListAdapter()
        contactsPermissionRequested = savedInstanceState
            ?.getBoolean(PERMISSION_REQUESTED)
            ?: false
    }

    override fun goBack(): Boolean {
        sharedViewModel.messageToForwardEvent.value?.consume {
            Log.w("$TAG Cancelling message forward")
            viewModel.showRedToast(R.string.conversation_message_forward_cancelled_toast, R.drawable.forward)
        }

        return findNavController().popBackStack()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ChatMessageForwardFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        postponeEnterTransition()
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner

        viewModel = ViewModelProvider(this)[ConversationForwardMessageViewModel::class.java]
        matchedViewModel = ViewModelProvider(requireActivity())[MatchedContactsViewModel::class.java]
        binding.viewModel = viewModel
        observeToastEvents(viewModel)

        binding.setBackClickListener {
            goBack()
        }

        binding.contactsList.setHasFixedSize(true)
        binding.contactsList.layoutManager = LinearLayoutManager(requireContext())

        val headerItemDecoration = RecyclerViewHeaderDecoration(requireContext(), adapter)
        binding.contactsList.addItemDecoration(headerItemDecoration)

        viewModel.modelsList.observe(
            viewLifecycleOwner
        ) {
            Log.i(
                "$TAG Conversations, contacts & suggestions list is ready with [${it.size}] items"
            )
            adapter.submitList(it)

            // Wait for adapter to have items before setting it in the RecyclerView,
            // otherwise scroll position isn't retained
            if (binding.contactsList.adapter != adapter) {
                binding.contactsList.adapter = adapter
            }

            (view.parent as? ViewGroup)?.doOnPreDraw {
                startPostponedEnterTransition()
            }
        }

        matchedViewModel.contacts.observe(viewLifecycleOwner) { contacts ->
            viewModel.setMatchedContacts(contacts)
        }

        viewModel.chatRoomCreatedEvent.observe(viewLifecycleOwner) {
            it.consume { conversationId ->
                Log.i("$TAG Navigating to conversation [$conversationId]")
                if (findNavController().currentDestination?.id == R.id.conversationForwardMessageFragment) {
                    val action = ConversationForwardMessageFragmentDirections.actionConversationForwardMessageFragmentToConversationFragment(
                        conversationId
                    )
                    disableConsumingEventOnPause = true
                    findNavController().navigate(action)
                }
            }
        }

        adapter.onClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                viewModel.handleClickOnModel(model)
            }
        }

        viewModel.searchFilter.observe(viewLifecycleOwner) { filter ->
            val trimmed = filter.trim()
            viewModel.applyFilter(trimmed)
        }

        viewModel.showNumberOrAddressPickerDialogEvent.observe(viewLifecycleOwner) {
            it.consume { list ->
                showNumberOrAddressPickerDialog(list)
            }
        }

        viewModel.dismissNumberOrAddressPickerDialogEvent.observe(viewLifecycleOwner) {
            it.consume {
                numberOrAddressPickerDialog?.dismiss()
                numberOrAddressPickerDialog = null
            }
        }
    }

    override fun onPause() {
        super.onPause()

        numberOrAddressPickerDialog?.dismiss()
        numberOrAddressPickerDialog = null

        if (!disableConsumingEventOnPause) {
            sharedViewModel.messageToForwardEvent.value?.consume {
                Log.w(
                    "$TAG Fragment is pausing, consuming forward event to prevent it from being used later"
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::matchedViewModel.isInitialized || permissionRequestInProgress) return

        if (PhoneContactNumbers.hasPermission(requireContext())) {
            matchedViewModel.reload()
        } else if (!contactsPermissionRequested) {
            contactsPermissionRequested = true
            permissionRequestInProgress = true
            matchedViewModel.reload()
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        } else {
            matchedViewModel.reload()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PERMISSION_REQUESTED, contactsPermissionRequested)
        super.onSaveInstanceState(outState)
    }

    private fun showNumberOrAddressPickerDialog(list: List<ContactNumberOrAddressModel>) {
        val numberOrAddressModel = NumberOrAddressPickerDialogModel(list)
        val dialog =
            DialogUtils.getNumberOrAddressPickerDialog(
                requireActivity(),
                numberOrAddressModel
            )
        numberOrAddressPickerDialog = dialog

        numberOrAddressModel.dismissEvent.observe(viewLifecycleOwner) { event ->
            event.consume {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

}
