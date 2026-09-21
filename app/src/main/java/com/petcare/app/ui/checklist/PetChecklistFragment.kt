package com.petcare.app.ui.checklist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.HapticFeedbackConstants
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
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.petcare.app.PetCareApp
import com.petcare.app.R
import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.repository.TaskSnapshot
import com.petcare.app.databinding.FragmentPetChecklistBinding
import com.petcare.app.gesture.ChecklistSwipeCallback
import com.petcare.app.gesture.DoubleTapPhotoListener
import com.petcare.app.gesture.PhotoViewerDialog
import com.petcare.app.gesture.ShakeDetector
import kotlinx.coroutines.launch

/**
 * PetChecklistFragment – Daily task checklist and care routine dashboard for a single pet.
 *
 * ## Gesture Controls & Sensors (Step 9 - Desirable Feature):
 * 1. **Swipe Left to Delete**: Dragging any task row leftwards paints a red background with a trash icon
 *    and deletes the task immediately with an Undo [Snackbar]. Reminders are safely cleaned up and restored.
 * 2. **Swipe Right to Complete**: Dragging any task row rightwards paints a green background with a check icon,
 *    toggles its completion status for today, snaps back into place, and offers an Undo [Snackbar].
 * 3. **Shake Device to Reset (Hardware Sensor)**: Shaking the device activates the [ShakeDetector] via
 *    the device's hardware accelerometer, provides tactile haptic feedback, and prompts a confirmation
 *    dialog to reset all completed tasks for today (with full Undo restoration).
 * 4. **Long-Press Multi-Select (ActionMode)**: Long-pressing any task row triggers [HapticFeedbackConstants.LONG_PRESS]
 *    and enters contextual multi-selection mode, enabling batch-complete and batch-delete operations.
 * 5. **Double-Tap Photo to Enlarge**: Double-tapping the hero pet avatar launches [PhotoViewerDialog]
 *    in an immersive full-screen view with swipe-down dismissal.
 * 6. **Gesture Discoverability**: A "Gestures guide" dialog in the toolbar overflow menu documents all 5
 *    gestures, and a one-time onboarding hint Snackbar greets first-time visitors.
 */
class PetChecklistFragment : Fragment() {

    private var _binding: FragmentPetChecklistBinding? = null
    private val binding get() = _binding!!

    private val args: PetChecklistFragmentArgs by navArgs()

    private val viewModel: PetChecklistViewModel by viewModels {
        val app = requireActivity().application as PetCareApp
        val userId = app.container.sessionManager.getUserId()
        PetChecklistViewModel.Factory(
            petId = args.petId,
            userId = userId,
            petRepository = app.container.petRepository,
            taskRepository = app.container.taskRepository,
            reminderScheduler = app.container.reminderScheduler
        )
    }

    private lateinit var checklistAdapter: ChecklistAdapter

    /** Hardware accelerometer shake detector for resetting the daily checklist. */
    private var shakeDetector: ShakeDetector? = null

    /** Active contextual ActionMode when multi-selection is enabled. */
    private var actionMode: ActionMode? = null
    private val selectedTaskIds = mutableSetOf<Long>()

    /** BroadcastReceiver listening for system midnight date changes. */
    private val dateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModel.updateTodayDate()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPetChecklistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupSegmentedControl()
        setupButtons()
        setupGestures()
        showOnboardingGestureTipIfNeeded()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // Refresh date on resume in case midnight passed while app was paused
        viewModel.updateTodayDate()

        val filter = IntentFilter(Intent.ACTION_DATE_CHANGED)
        requireContext().registerReceiver(dateChangeReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(dateChangeReceiver)
        } catch (ignored: IllegalArgumentException) {
            // Already unregistered
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        finishActionMode()
        shakeDetector?.stop()
        shakeDetector?.let { lifecycle.removeObserver(it) }
        shakeDetector = null
        binding.recyclerChecklist.adapter = null
        _binding = null
    }

    // ── Setup Helpers ─────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.toolbar.inflateMenu(R.menu.menu_checklist)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_delegate -> {
                    if (findNavController().currentDestination?.id == R.id.petChecklistFragment) {
                        val action = PetChecklistFragmentDirections.actionChecklistToDelegate(args.petId)
                        findNavController().navigate(action)
                    }
                    true
                }
                R.id.action_edit_pet -> {
                    navigateToPetForm()
                    true
                }
                R.id.action_reset_checklist -> {
                    confirmAndResetChecklist()
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
        checklistAdapter = ChecklistAdapter(
            onTaskCheckChanged = { task, isChecked ->
                viewModel.toggleTaskDone(task, isChecked)
            },
            onTaskEditClick = { taskId ->
                navigateToTaskForm(taskId)
            },
            onTaskDeleteClick = { task ->
                showDeleteTaskDialog(task)
            }
        )

        // Multi-selection callbacks
        checklistAdapter.onTaskSelectToggle = { task ->
            toggleTaskSelection(task.id)
        }
        checklistAdapter.onTaskLongClick = { task ->
            if (actionMode == null) {
                startMultiSelection(task.id)
                true
            } else {
                toggleTaskSelection(task.id)
                true
            }
        }

        binding.recyclerChecklist.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = checklistAdapter
            setHasFixedSize(false)
        }
    }

    private fun setupSegmentedControl() {
        binding.toggleFilterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.button_tab_today -> viewModel.setFilterMode(ChecklistFilterMode.TODAY)
                    R.id.button_tab_all -> viewModel.setFilterMode(ChecklistFilterMode.ALL_ROUTINES)
                }
            }
        }
    }

    private fun setupButtons() {
        // Edit pet in header card
        binding.buttonEditPet.setOnClickListener {
            navigateToPetForm()
        }

        // FAB Add routine
        binding.fabAddRoutine.setOnClickListener {
            navigateToTaskForm(taskId = 0L)
        }

        // Empty state add button
        binding.buttonEmptyAdd.setOnClickListener {
            navigateToTaskForm(taskId = 0L)
        }
    }

    // ── Gesture Controls Setup (Step 9) ───────────────────────────────────

    private fun setupGestures() {
        // 1 & 2. Swipe Left to Delete and Swipe Right to Mark Done
        val swipeCallback = ChecklistSwipeCallback(
            context = requireContext(),
            isSelectionModeActive = { actionMode != null },
            onSwipeDelete = { position ->
                val item = checklistAdapter.getItemAt(position)
                if (item is ChecklistItem.Task) {
                    viewModel.deleteTask(item.task)
                }
            },
            onSwipeToggleDone = { position ->
                val item = checklistAdapter.getItemAt(position)
                if (item is ChecklistItem.Task) {
                    val newDone = !item.isDoneToday
                    viewModel.toggleTaskDone(item.task, newDone)
                    checklistAdapter.notifyItemChanged(position)

                    val statusMsg = if (newDone) {
                        "Completed \"${item.task.name}\""
                    } else {
                        "Marked \"${item.task.name}\" pending"
                    }
                    Snackbar.make(binding.root, statusMsg, Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.fabAddRoutine)
                        .setAction(R.string.action_undo) {
                            viewModel.toggleTaskDone(item.task, !newDone)
                        }
                        .show()
                }
            }
        )
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerChecklist)

        // 3. Accelerometer Shake Detector to Reset Today's Checklist
        shakeDetector = ShakeDetector(requireContext()) {
            binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            confirmAndResetChecklist()
        }
        shakeDetector?.let { lifecycle.addObserver(it) }

        // 5. Double-Tap Pet Photo to Enlarge
        binding.imagePetPhoto.setOnTouchListener(
            DoubleTapPhotoListener(
                context = requireContext(),
                onDoubleTap = {
                    val pet = viewModel.pet.value
                    if (pet != null) {
                        PhotoViewerDialog.newInstance(pet.name, pet.photoUri)
                            .show(childFragmentManager, PhotoViewerDialog.TAG)
                    }
                },
                onSingleTap = {
                    navigateToPetForm()
                }
            )
        )
    }

    // ── Multi-Selection ActionMode (Gesture 4) ────────────────────────────

    private fun startMultiSelection(initialTaskId: Long) {
        if (actionMode != null) return

        selectedTaskIds.clear()
        selectedTaskIds.add(initialTaskId)
        checklistAdapter.isSelectionMode = true
        checklistAdapter.selectedIds = selectedTaskIds
        checklistAdapter.notifyDataSetChanged()

        val callback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                mode.menuInflater.inflate(R.menu.menu_checklist_action_mode, menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                updateActionModeTitle(mode)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    R.id.action_mark_done_selected -> {
                        val idsToMark = selectedTaskIds.toSet()
                        viewModel.batchMarkDone(idsToMark)
                        mode.finish()
                        return true
                    }
                    R.id.action_delete_selected -> {
                        val idsToDelete = selectedTaskIds.toSet()
                        val count = idsToDelete.size
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.dialog_delete_selected_tasks_title)
                            .setMessage(getString(R.string.dialog_delete_selected_tasks_message, count))
                            .setPositiveButton(R.string.btn_delete) { _, _ ->
                                viewModel.batchDeleteTasks(idsToDelete)
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
                selectedTaskIds.clear()
                checklistAdapter.isSelectionMode = false
                checklistAdapter.selectedIds = emptySet()
                checklistAdapter.notifyDataSetChanged()
            }
        }

        actionMode = requireActivity().startActionMode(callback)
        actionMode?.let { updateActionModeTitle(it) }
    }

    private fun toggleTaskSelection(taskId: Long) {
        if (selectedTaskIds.contains(taskId)) {
            selectedTaskIds.remove(taskId)
        } else {
            selectedTaskIds.add(taskId)
        }

        if (selectedTaskIds.isEmpty()) {
            finishActionMode()
        } else {
            checklistAdapter.selectedIds = selectedTaskIds
            checklistAdapter.notifyDataSetChanged()
            actionMode?.let { updateActionModeTitle(it) }
        }
    }

    private fun updateActionModeTitle(mode: ActionMode) {
        mode.title = getString(R.string.action_mode_selected_count, selectedTaskIds.size)
    }

    private fun finishActionMode() {
        actionMode?.finish()
        actionMode = null
        selectedTaskIds.clear()
        checklistAdapter.isSelectionMode = false
        checklistAdapter.selectedIds = emptySet()
        checklistAdapter.notifyDataSetChanged()
    }

    // ── Shake Reset Checklist Confirmation ────────────────────────────────

    private fun confirmAndResetChecklist() {
        val petName = viewModel.pet.value?.name ?: "your pet"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_reset_checklist_title)
            .setMessage(getString(R.string.dialog_reset_checklist_message, petName))
            .setPositiveButton(R.string.btn_reset) { _, _ ->
                viewModel.resetTodayChecklist()
            }
            .setNegativeButton(R.string.dialog_logout_cancel, null)
            .show()
    }

    // ── Gestures Guide & Discoverability ──────────────────────────────────

    private fun showGesturesGuideDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_gestures_guide_title)
            .setMessage(R.string.dialog_gestures_guide_message)
            .setPositiveButton(R.string.btn_got_it, null)
            .show()
    }

    private fun showOnboardingGestureTipIfNeeded() {
        val prefs = requireContext().getSharedPreferences("pet_care_prefs", Context.MODE_PRIVATE)
        val hasShown = prefs.getBoolean("tip_gesture_shown", false)
        if (!hasShown) {
            Snackbar.make(binding.root, R.string.tip_gesture_hint, Snackbar.LENGTH_INDEFINITE)
                .setAnchorView(binding.fabAddRoutine)
                .setAction(R.string.btn_got_it) {
                    prefs.edit().putBoolean("tip_gesture_shown", true).apply()
                }
                .show()
        }
    }

    // ── Reactive State Observation ────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Collect UI state
                launch {
                    viewModel.uiState.collect { state ->
                        renderUiState(state)
                    }
                }

                // Collect one-shot events
                launch {
                    viewModel.event.collect { event ->
                        event?.let {
                            handleEvent(it)
                            viewModel.clearEvent()
                        }
                    }
                }
            }
        }
    }

    private fun renderUiState(state: PetChecklistUiState) {
        val pet = state.pet

        // ── Pet Header Card ────────────────────────────────────────────────
        if (pet != null) {
            binding.textPetName.text = pet.name

            val speciesBreed = buildString {
                append(pet.species)
                if (pet.breed.isNotBlank()) {
                    append(" · ")
                    append(pet.breed)
                }
            }
            binding.textSpeciesBreed.text = speciesBreed

            if (!pet.photoUri.isNullOrBlank()) {
                try {
                    binding.imagePetPhoto.setImageURI(Uri.parse(pet.photoUri))
                } catch (e: Exception) {
                    binding.imagePetPhoto.setImageResource(R.drawable.ic_pet_placeholder)
                }
            } else {
                binding.imagePetPhoto.setImageResource(R.drawable.ic_pet_placeholder)
            }
        }

        // Today's Formatted Date
        binding.textTodayDate.text = state.formattedDate

        // Daily Progress (Only visible in "Today" mode)
        if (state.filterMode == ChecklistFilterMode.TODAY) {
            binding.progressIndicatorDaily.visibility = View.VISIBLE
            binding.textProgressStatus.visibility = View.VISIBLE
            binding.progressIndicatorDaily.setProgress(state.progressPercentage, true)

            if (state.isAllDone) {
                binding.textProgressStatus.text = getString(R.string.progress_all_done)
            } else {
                binding.textProgressStatus.text = getString(
                    R.string.progress_x_of_y_done,
                    state.completedCount,
                    state.totalCount
                )
            }
        } else {
            // "All routines" mode
            binding.progressIndicatorDaily.visibility = View.GONE
            binding.textProgressStatus.visibility = View.GONE
        }

        // Empty States vs List
        val petName = pet?.name ?: "your pet"
        if (state.isEmptyState) {
            binding.recyclerChecklist.visibility = View.GONE
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.textEmptyTitle.text = getString(R.string.empty_no_tasks_title)
            binding.textEmptySubtitle.text = getString(R.string.empty_no_tasks_subtitle, petName)
            binding.buttonEmptyAdd.visibility = View.VISIBLE
        } else if (state.isNoTasksToday) {
            binding.recyclerChecklist.visibility = View.GONE
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.textEmptyTitle.text = getString(R.string.empty_no_tasks_today_title)
            binding.textEmptySubtitle.text = getString(R.string.empty_no_tasks_today_subtitle, petName)
            binding.buttonEmptyAdd.visibility = View.VISIBLE
        } else {
            binding.recyclerChecklist.visibility = View.VISIBLE
            binding.layoutEmptyState.visibility = View.GONE
            checklistAdapter.submitList(state.items)
        }
    }

    private fun handleEvent(event: ChecklistEvent) {
        when (event) {
            is ChecklistEvent.TaskDeleted -> showUndoSnackbar(event.snapshot, event.taskName)
            is ChecklistEvent.ChecklistReset -> {
                if (event.previousCompletions.isNotEmpty()) {
                    Snackbar.make(binding.root, R.string.snackbar_checklist_reset, Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.fabAddRoutine)
                        .setAction(R.string.action_undo) {
                            viewModel.restoreResetChecklist(event.previousCompletions)
                        }
                        .show()
                } else {
                    Snackbar.make(binding.root, R.string.snackbar_checklist_reset, Snackbar.LENGTH_SHORT)
                        .setAnchorView(binding.fabAddRoutine)
                        .show()
                }
            }
            is ChecklistEvent.BatchTasksDeleted -> {
                val count = event.snapshots.size
                Snackbar.make(
                    binding.root,
                    getString(R.string.snackbar_tasks_batch_deleted, count),
                    Snackbar.LENGTH_LONG
                )
                    .setAnchorView(binding.fabAddRoutine)
                    .setAction(R.string.action_undo) {
                        viewModel.restoreBatchTasks(event.snapshots)
                    }
                    .show()
            }
            is ChecklistEvent.BatchTasksMarkedDone -> {
                Snackbar.make(
                    binding.root,
                    getString(R.string.snackbar_tasks_batch_completed, event.count),
                    Snackbar.LENGTH_SHORT
                )
                    .setAnchorView(binding.fabAddRoutine)
                    .show()
            }
            is ChecklistEvent.ShowError -> showErrorSnackbar(event.message)
        }
    }

    // ── Dialog & Snackbar Helpers ─────────────────────────────────────────

    private fun showDeleteTaskDialog(task: TaskEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_delete_task_title)
            .setMessage(getString(R.string.dialog_delete_task_message, task.name))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                viewModel.deleteTask(task)
            }
            .setNegativeButton(R.string.dialog_logout_cancel, null)
            .show()
    }

    private fun showUndoSnackbar(snapshot: TaskSnapshot, taskName: String) {
        Snackbar.make(
            binding.root,
            getString(R.string.snackbar_task_deleted, taskName),
            Snackbar.LENGTH_LONG
        )
            .setAnchorView(binding.fabAddRoutine)
            .setAction(R.string.action_undo) {
                viewModel.restoreTask(snapshot)
            }
            .show()
    }

    private fun showErrorSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.fabAddRoutine)
            .show()
    }

    // ── Navigation Helpers ────────────────────────────────────────────────

    private fun navigateToPetForm() {
        if (findNavController().currentDestination?.id == R.id.petChecklistFragment) {
            val action = PetChecklistFragmentDirections.actionChecklistToPetForm(petId = args.petId)
            findNavController().navigate(action)
        }
    }

    private fun navigateToTaskForm(taskId: Long) {
        if (findNavController().currentDestination?.id == R.id.petChecklistFragment) {
            val action = PetChecklistFragmentDirections.actionChecklistToTaskForm(
                taskId = taskId,
                preselectedPetId = args.petId
            )
            findNavController().navigate(action)
        }
    }
}
