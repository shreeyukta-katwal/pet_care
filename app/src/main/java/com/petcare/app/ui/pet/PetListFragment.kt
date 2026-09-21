package com.petcare.app.ui.pet

import android.os.Bundle
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.petcare.app.PetCareApp
import com.petcare.app.R
import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.repository.PetSnapshot
import com.petcare.app.databinding.FragmentPetListBinding
import com.petcare.app.gesture.PetListSwipeCallback
import com.petcare.app.gesture.PhotoViewerDialog
import com.petcare.app.util.PhotoStorageHelper
import kotlinx.coroutines.launch

/**
 * PetListFragment – Primary dashboard showing all pets for the logged-in user.
 *
 * ## Gesture Controls & Sensors (Step 9 - Desirable Feature):
 * 1. **Swipe Left to Delete**: Dragging a pet card leftwards draws a red background with a trash icon
 *    and deletes the pet immediately with an Undo [Snackbar]. Reminders are cancelled and restored on undo.
 * 4. **Long-Press Multi-Select (ActionMode)**: Long-pressing any pet card triggers haptic feedback and
 *    opens contextual action mode ("N selected"), allowing batch deletion.
 * 5. **Double-Tap Photo to Enlarge**: Double-tapping a pet photo thumbnail opens [PhotoViewerDialog]
 *    with a scale animation and swipe-down-to-dismiss gesture.
 * 6. **Gesture Discoverability**: Accessible "Gestures guide" option in the top app bar overflow menu.
 */
class PetListFragment : Fragment() {

    // ── View binding ──────────────────────────────────────────────────────
    private var _binding: FragmentPetListBinding? = null
    private val binding get() = _binding!!

    // ── ViewModel ─────────────────────────────────────────────────────────
    private val viewModel: PetListViewModel by viewModels {
        val container = (requireActivity().application as PetCareApp).container
        PetListViewModel.Factory(
            userId = container.sessionManager.getUserId()
                ?: error("PetListFragment reached with no active session"),
            petRepository = container.petRepository,
            reminderScheduler = container.reminderScheduler,
            taskRepository = container.taskRepository
        )
    }

    // ── Adapter ───────────────────────────────────────────────────────────
    private val adapter by lazy {
        PetListAdapter(
            onCardClick       = { petId -> navigateToChecklist(petId) },
            onEditClick       = { petId -> navigateToPetForm(petId) },
            onDeleteClick     = { pet   -> showDeleteConfirmationDialog(pet) },
            onAddRoutineClick = { petId -> navigateToTaskForm(preselectedPetId = petId) }
        )
    }

    /** Active contextual ActionMode when multi-selection is enabled. */
    private var actionMode: ActionMode? = null
    private val selectedPetIds = mutableSetOf<Long>()

    // ── Fragment lifecycle ────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPetListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupGestures()
        setupFab()
        setupEmptyStateButton()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        finishActionMode()
        binding.recyclerPets.adapter = null
        _binding = null
    }

    // ── Setup helpers ─────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.menu_pet_list)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_logout -> {
                    showLogoutConfirmationDialog()
                    true
                }
                R.id.action_gestures_guide -> {
                    showGesturesGuideDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        // Multi-selection callbacks
        adapter.onPetSelectToggle = { pet ->
            togglePetSelection(pet.id)
        }
        adapter.onPetLongClick = { pet ->
            if (actionMode == null) {
                startMultiSelection(pet.id)
                true
            } else {
                togglePetSelection(pet.id)
                true
            }
        }
        // Double-tap photo callback
        adapter.onPhotoDoubleTap = { pet ->
            PhotoViewerDialog.newInstance(pet.name, pet.photoUri)
                .show(childFragmentManager, PhotoViewerDialog.TAG)
        }

        binding.recyclerPets.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PetListFragment.adapter
            setHasFixedSize(false)
        }
    }

    private fun setupGestures() {
        // Swipe Left to Delete for Pet Cards
        val swipeCallback = PetListSwipeCallback(
            context = requireContext(),
            isSelectionModeActive = { actionMode != null },
            onSwipeDelete = { position ->
                val pet = adapter.getPetAt(position)
                if (pet != null) {
                    viewModel.deletePet(pet)
                }
            }
        )
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerPets)
    }

    private fun setupFab() {
        binding.fabAddPet.setOnClickListener {
            navigateToPetForm(petId = 0L)
        }
    }

    private fun setupEmptyStateButton() {
        binding.buttonEmptyAdd.setOnClickListener {
            navigateToPetForm(petId = 0L)
        }
    }

    // ── Multi-Selection ActionMode (Gesture 4) ────────────────────────────

    private fun startMultiSelection(initialPetId: Long) {
        if (actionMode != null) return

        selectedPetIds.clear()
        selectedPetIds.add(initialPetId)
        adapter.isSelectionMode = true
        adapter.selectedIds = selectedPetIds
        adapter.notifyDataSetChanged()

        val callback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                mode.menuInflater.inflate(R.menu.menu_pet_list_action_mode, menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                updateActionModeTitle(mode)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    R.id.action_delete_selected_pets -> {
                        val count = selectedPetIds.size
                        val petsToDelete = adapter.currentList
                            .map { it.pet }
                            .filter { it.id in selectedPetIds }

                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.dialog_delete_selected_pets_title)
                            .setMessage(getString(R.string.dialog_delete_selected_pets_message, count))
                            .setPositiveButton(R.string.btn_delete) { _, _ ->
                                viewModel.batchDeletePets(petsToDelete)
                                mode.finish()
                            }
                            .setNegativeButton(R.string.dialog_logout_cancel, null)
                            .show()
                        return true
                    }
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                selectedPetIds.clear()
                adapter.isSelectionMode = false
                adapter.selectedIds = emptySet()
                adapter.notifyDataSetChanged()
            }
        }

        actionMode = requireActivity().startActionMode(callback)
        actionMode?.let { updateActionModeTitle(it) }
    }

    private fun togglePetSelection(petId: Long) {
        if (selectedPetIds.contains(petId)) {
            selectedPetIds.remove(petId)
        } else {
            selectedPetIds.add(petId)
        }

        if (selectedPetIds.isEmpty()) {
            finishActionMode()
        } else {
            adapter.selectedIds = selectedPetIds
            adapter.notifyDataSetChanged()
            actionMode?.let { updateActionModeTitle(it) }
        }
    }

    private fun updateActionModeTitle(mode: ActionMode) {
        mode.title = getString(R.string.action_mode_selected_count, selectedPetIds.size)
    }

    private fun finishActionMode() {
        actionMode?.finish()
        actionMode = null
        selectedPetIds.clear()
        adapter.isSelectionMode = false
        adapter.selectedIds = emptySet()
        adapter.notifyDataSetChanged()
    }

    private fun showGesturesGuideDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_gestures_guide_title)
            .setMessage(R.string.dialog_gestures_guide_message)
            .setPositiveButton(R.string.btn_got_it, null)
            .show()
    }

    // ── ViewModel observation ─────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Pet list with progress
                launch {
                    viewModel.petsWithProgress.collect { petItems ->
                        adapter.submitList(petItems)
                        updateEmptyState(petItems.isEmpty())
                    }
                }

                // One-shot events
                launch {
                    viewModel.event.collect { event ->
                        event ?: return@collect
                        handleEvent(event)
                        viewModel.clearEvent()
                    }
                }
            }
        }
    }

    private fun handleEvent(event: PetListEvent) {
        when (event) {
            is PetListEvent.PetDeleted -> showUndoSnackbar(event.snapshot, event.petName)
            is PetListEvent.BatchPetsDeleted -> {
                val count = event.snapshots.size
                Snackbar.make(
                    binding.root,
                    getString(R.string.snackbar_pets_batch_deleted, count),
                    Snackbar.LENGTH_LONG
                )
                    .setAnchorView(binding.fabAddPet)
                    .setAction(R.string.action_undo) {
                        viewModel.restoreBatchPets(event.snapshots)
                    }
                    .show()
            }
            is PetListEvent.ShowError -> showErrorSnackbar(event.message)
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            binding.recyclerPets.visibility = View.GONE
            binding.imageEmpty.visibility = View.VISIBLE
            binding.textEmptyTitle.visibility = View.VISIBLE
            binding.textEmptySubtitle.visibility = View.VISIBLE
            binding.buttonEmptyAdd.visibility = View.VISIBLE
        } else {
            binding.recyclerPets.visibility = View.VISIBLE
            binding.imageEmpty.visibility = View.GONE
            binding.textEmptyTitle.visibility = View.GONE
            binding.textEmptySubtitle.visibility = View.GONE
            binding.buttonEmptyAdd.visibility = View.GONE
        }
    }

    private fun showDeleteConfirmationDialog(pet: PetEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_delete_pet_title, pet.name))
            .setMessage(getString(R.string.dialog_delete_pet_message, pet.name))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                viewModel.deletePet(pet)
            }
            .setNegativeButton(R.string.dialog_logout_cancel, null)
            .show()
    }

    private fun showUndoSnackbar(snapshot: PetSnapshot, petName: String) {
        var undoTapped = false

        Snackbar.make(
            binding.root,
            getString(R.string.snackbar_pet_deleted, petName),
            Snackbar.LENGTH_LONG
        )
            .setAnchorView(binding.fabAddPet)
            .setAction(R.string.action_undo) {
                undoTapped = true
                viewModel.restorePet(snapshot)
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (!undoTapped && snapshot.pet.photoUri != null) {
                        PhotoStorageHelper.deletePhotoFile(snapshot.pet.photoUri)
                    }
                }
            })
            .show()
    }

    private fun showErrorSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.fabAddPet)
            .show()
    }

    private fun showLogoutConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_logout_title)
            .setMessage(R.string.dialog_logout_message)
            .setPositiveButton(R.string.action_logout) { _, _ ->
                // Clear the session directly — PetListViewModel does not own auth state
                val container = (requireActivity().application as PetCareApp).container
                container.sessionManager.clear()
                navigateToWelcome()
            }
            .setNegativeButton(R.string.dialog_logout_cancel, null)
            .show()
    }

    // ── Navigation helpers ────────────────────────────────────────────────

    private fun navigateToChecklist(petId: Long) {
        val action = PetListFragmentDirections.actionPetListToChecklist(petId = petId)
        findNavController().navigate(action)
    }

    private fun navigateToPetForm(petId: Long) {
        val action = PetListFragmentDirections.actionPetListToPetForm(petId = petId)
        findNavController().navigate(action)
    }

    private fun navigateToTaskForm(preselectedPetId: Long) {
        val action = PetListFragmentDirections.actionPetListToTaskForm(
            taskId = 0L,
            preselectedPetId = preselectedPetId
        )
        findNavController().navigate(action)
    }

    private fun navigateToWelcome() {
        findNavController().navigate(R.id.action_petList_to_welcome)
    }
}
