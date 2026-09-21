package com.petcare.app.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.db.TaskCategory
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.db.TaskFrequency
import com.petcare.app.data.repository.PetRepository
import com.petcare.app.data.repository.TaskRepository
import com.petcare.app.reminder.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ── UI state sealed hierarchy ────────────────────────────────────────────────

/**
 * UI state for [TaskFormFragment].
 */
sealed class TaskFormState {

    /** Ready for input. */
    object Idle : TaskFormState()

    /** Database or background operation in progress. */
    object Loading : TaskFormState()

    /** Task loaded from database in edit mode; Fragment populates the form. */
    data class TaskLoaded(val task: TaskEntity) : TaskFormState()

    /** Task successfully inserted or updated in Room; Fragment navigates back. */
    data class Saved(val task: TaskEntity) : TaskFormState()

    /** Validation error targeting a specific field. */
    data class ValidationError(val field: Field, val message: String) : TaskFormState() {
        enum class Field {
            PET, NAME, CATEGORY, TIME, DAYS_OF_WEEK
        }
    }

    /** Generic error during save. */
    data class Error(val message: String) : TaskFormState()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * ViewModel for [TaskFormFragment].
 *
 * Handles both "create care routine" (taskId == 0) and "edit routine" (taskId > 0).
 *
 * Responsibilities:
 * - Emits the reactive list of available pets owned by [userId] for the dropdown.
 * - If [preselectedPetId] > 0, sets it as the default selected pet.
 * - In edit mode, loads the existing [TaskEntity] and emits [TaskFormState.TaskLoaded].
 * - Tracks form dirty state to guard back navigation.
 * - Validates:
 *     - Pet is selected (user must have at least one pet and one must be picked).
 *     - Task name is non-blank.
 *     - Category is selected.
 *     - Time is selected (valid hour and minute).
 *     - Weekly frequency requires at least one day selected in the bitmask.
 * - On save: inserts or updates in Room and calls [ReminderScheduler.schedule]
 *   (or [ReminderScheduler.cancel] if reminders are turned off).
 *
 * @param taskId            0 for new task; existing task ID for edit mode.
 * @param preselectedPetId  Optional initial pet selection (e.g. from checklist screen).
 * @param userId            Current logged-in user ID.
 * @param taskRepository    Repository for care task operations.
 * @param petRepository     Repository for pet operations.
 * @param reminderScheduler Scheduler for notifications.
 */
class TaskFormViewModel(
    private val taskId: Long,
    private val preselectedPetId: Long,
    userId: Long,
    private val taskRepository: TaskRepository,
    private val petRepository: PetRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    val isEditMode: Boolean get() = taskId != 0L

    // ── Pets stream for the pet selector ──────────────────────────────────
    val availablePets: StateFlow<List<PetEntity>> = petRepository.getAllForUser(userId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // ── Form State ────────────────────────────────────────────────────────
    private val _state = MutableStateFlow<TaskFormState>(TaskFormState.Idle)
    val state: StateFlow<TaskFormState> = _state.asStateFlow()

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    private var originalTask: TaskEntity? = null

    // Selected pet ID (tracked internally)
    private var selectedPetId: Long? = if (preselectedPetId > 0L) preselectedPetId else null

    // Time components (null if unselected)
    private val _selectedTime = MutableStateFlow<Pair<Int, Int>?>(null)
    val selectedTime: StateFlow<Pair<Int, Int>?> = _selectedTime.asStateFlow()

    init {
        if (isEditMode) {
            loadTask()
        }
    }

    /**
     * Loads the existing task in edit mode.
     */
    private fun loadTask() {
        viewModelScope.launch {
            _state.value = TaskFormState.Loading
            val task = taskRepository.findById(taskId)
            if (task != null) {
                originalTask = task
                selectedPetId = task.petId
                _selectedTime.value = Pair(task.hour, task.minute)
                _state.value = TaskFormState.TaskLoaded(task)
            } else {
                _state.value = TaskFormState.Idle
            }
        }
    }

    /**
     * Sets the selected pet primary key.
     */
    fun selectPet(petId: Long) {
        selectedPetId = petId
        markDirty()
    }

    fun getSelectedPetId(): Long? = selectedPetId

    /**
     * Updates the chosen reminder time.
     *
     * @param hour 0–23
     * @param minute 0–59
     */
    fun setTime(hour: Int, minute: Int) {
        _selectedTime.value = Pair(hour, minute)
        markDirty()
    }

    fun markDirty() {
        _isDirty.value = true
    }

    fun resetState() {
        if (_state.value is TaskFormState.TaskLoaded || _state.value is TaskFormState.ValidationError) {
            _state.value = TaskFormState.Idle
        }
    }

    /**
     * Validates and saves the care routine task.
     *
     * @param name               Name of routine / task.
     * @param category           Chosen [TaskCategory] (or null).
     * @param frequency          [TaskFrequency.DAILY] or [TaskFrequency.WEEKLY].
     * @param daysOfWeekBitmask  Bitmask for weekly days (Mon=1, ..., Sun=64).
     * @param supplies           Required supplies notes.
     * @param notes              Care / veterinary notes.
     * @param reminderEnabled    Whether notifications are enabled.
     */
    fun save(
        name: String,
        category: TaskCategory?,
        frequency: TaskFrequency,
        daysOfWeekBitmask: Int,
        supplies: String,
        notes: String,
        reminderEnabled: Boolean
    ) {
        val petId = selectedPetId
        if (petId == null || petId <= 0L) {
            _state.value = TaskFormState.ValidationError(
                TaskFormState.ValidationError.Field.PET,
                "Please select a pet"
            )
            return
        }

        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            _state.value = TaskFormState.ValidationError(
                TaskFormState.ValidationError.Field.NAME,
                "Routine name is required"
            )
            return
        }

        if (category == null) {
            _state.value = TaskFormState.ValidationError(
                TaskFormState.ValidationError.Field.CATEGORY,
                "Please select a category"
            )
            return
        }

        val time = _selectedTime.value
        if (time == null) {
            _state.value = TaskFormState.ValidationError(
                TaskFormState.ValidationError.Field.TIME,
                "Please choose a time"
            )
            return
        }

        if (frequency == TaskFrequency.WEEKLY && daysOfWeekBitmask == 0) {
            _state.value = TaskFormState.ValidationError(
                TaskFormState.ValidationError.Field.DAYS_OF_WEEK,
                "Select at least one day for weekly routines"
            )
            return
        }

        _state.value = TaskFormState.Loading
        viewModelScope.launch {
            try {
                val savedTask: TaskEntity
                if (isEditMode) {
                    val updated = originalTask!!.copy(
                        petId = petId,
                        name = trimmedName,
                        category = category,
                        frequency = frequency,
                        daysOfWeek = if (frequency == TaskFrequency.WEEKLY) daysOfWeekBitmask else 0,
                        hour = time.first,
                        minute = time.second,
                        supplies = supplies.trim(),
                        notes = notes.trim(),
                        reminderEnabled = reminderEnabled
                    )
                    taskRepository.update(updated)
                    savedTask = updated
                } else {
                    val newTask = TaskEntity(
                        petId = petId,
                        name = trimmedName,
                        category = category,
                        frequency = frequency,
                        daysOfWeek = if (frequency == TaskFrequency.WEEKLY) daysOfWeekBitmask else 0,
                        hour = time.first,
                        minute = time.second,
                        supplies = supplies.trim(),
                        notes = notes.trim(),
                        reminderEnabled = reminderEnabled
                    )
                    val newId = taskRepository.insert(newTask)
                    savedTask = newTask.copy(id = newId)
                }

                // Manage WorkManager reminder
                if (savedTask.reminderEnabled) {
                    reminderScheduler.schedule(savedTask)
                } else {
                    reminderScheduler.cancel(savedTask.id)
                }

                _isDirty.value = false
                _state.value = TaskFormState.Saved(savedTask)

            } catch (e: Exception) {
                _state.value = TaskFormState.Error(
                    e.message ?: "An unexpected error occurred while saving the routine"
                )
            }
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────
    class Factory(
        private val taskId: Long,
        private val preselectedPetId: Long,
        private val userId: Long,
        private val taskRepository: TaskRepository,
        private val petRepository: PetRepository,
        private val reminderScheduler: ReminderScheduler
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == TaskFormViewModel::class.java) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return TaskFormViewModel(
                taskId = taskId,
                preselectedPetId = preselectedPetId,
                userId = userId,
                taskRepository = taskRepository,
                petRepository = petRepository,
                reminderScheduler = reminderScheduler
            ) as T
        }
    }
}
