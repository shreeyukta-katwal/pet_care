package com.petcare.app.ui.checklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.db.TaskCategory
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.repository.PetRepository
import com.petcare.app.data.repository.TaskRepository
import com.petcare.app.data.repository.TaskSnapshot
import com.petcare.app.reminder.ReminderScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Filter mode for the segmented toggle control on the checklist screen.
 */
enum class ChecklistFilterMode {
    TODAY,
    ALL_ROUTINES
}

/**
 * UI State holding everything needed to render [PetChecklistFragment].
 */
data class PetChecklistUiState(
    val pet: PetEntity? = null,
    val filterMode: ChecklistFilterMode = ChecklistFilterMode.TODAY,
    val items: List<ChecklistItem> = emptyList(),
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val formattedDate: String = "",
    val isAllDone: Boolean = false,
    val isEmptyState: Boolean = false,
    val isNoTasksToday: Boolean = false
) {
    val progressPercentage: Int
        get() = if (totalCount == 0) 0 else (completedCount * 100 / totalCount)
}

/**
 * One-shot UI events emitted by [PetChecklistViewModel].
 */
sealed class ChecklistEvent {
    data class TaskDeleted(val snapshot: TaskSnapshot, val taskName: String) : ChecklistEvent()
    data class ChecklistReset(val previousCompletions: Map<Long, String?>) : ChecklistEvent()
    data class BatchTasksDeleted(val snapshots: List<TaskSnapshot>) : ChecklistEvent()
    data class BatchTasksMarkedDone(val count: Int) : ChecklistEvent()
    data class ShowError(val message: String) : ChecklistEvent()
}

/**
 * ViewModel for [PetChecklistFragment].
 *
 * Handles:
 * - Reactive stream of pet profile info.
 * - Categorized checklist creation (Feeding, Exercise, Grooming, Medication, Healthcare).
 * - Segmented filtering ("Today" vs "All routines").
 * - Marking tasks done/undone backed by Room database.
 * - Live progress tracking (LinearProgressIndicator + "4/6 done").
 * - Dynamic midnight date-update support via [updateTodayDate].
 * - Undo-safe task deletion with [TaskSnapshot] and [ReminderScheduler] management.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PetChecklistViewModel(
    private val petId: Long,
    userId: Long,
    private val petRepository: PetRepository,
    private val taskRepository: TaskRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    private val _filterMode = MutableStateFlow(ChecklistFilterMode.TODAY)
    val filterMode: StateFlow<ChecklistFilterMode> = _filterMode.asStateFlow()

    private val _todayDate = MutableStateFlow(LocalDate.now())

    private val _event = MutableStateFlow<ChecklistEvent?>(null)
    val event: StateFlow<ChecklistEvent?> = _event.asStateFlow()

    // ── Pet stream ────────────────────────────────────────────────────────
    val pet: StateFlow<PetEntity?> = petRepository.getAllForUser(userId)
        .combine(MutableStateFlow(petId)) { petList, id -> petList.find { it.id == id } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    // ── Tasks stream based on filterMode and todayDate ──────────────────
    private val rawTasks = combine(_filterMode, _todayDate) { mode, today ->
        Pair(mode, today)
    }.flatMapLatest { (mode, today) ->
        when (mode) {
            ChecklistFilterMode.TODAY -> taskRepository.todaysTasksForPet(petId, today)
            ChecklistFilterMode.ALL_ROUTINES -> taskRepository.getAllForPet(petId)
        }
    }

    // ── Combined UI State ────────────────────────────────────────────────
    val uiState: StateFlow<PetChecklistUiState> = combine(
        pet,
        rawTasks,
        _filterMode,
        _todayDate
    ) { currentPet, taskList, mode, today ->

        val todayIso = today.toString()
        val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())
        val formattedDateStr = today.format(dateFormatter)

        // 1. Categorize tasks by category enum in fixed order
        val categoryOrder = listOf(
            TaskCategory.FEEDING,
            TaskCategory.EXERCISE,
            TaskCategory.GROOMING,
            TaskCategory.MEDICATION,
            TaskCategory.HEALTHCARE
        )

        val checklistItems = mutableListOf<ChecklistItem>()
        var completedCount = 0
        val totalCount = taskList.size

        categoryOrder.forEach { category ->
            val tasksInCategory = taskList.filter { it.category == category }
                .sortedWith(compareBy<TaskEntity> { it.hour }.thenBy { it.minute }.thenBy { it.name })

            if (tasksInCategory.isNotEmpty()) {
                checklistItems.add(ChecklistItem.Header(category))
                tasksInCategory.forEach { task ->
                    val isDone = (task.lastCompletedDate == todayIso)
                    if (isDone) completedCount++
                    checklistItems.add(ChecklistItem.Task(task, isDoneToday = isDone))
                }
            }
        }

        val allDone = totalCount > 0 && completedCount == totalCount
        val noTasksExist = (mode == ChecklistFilterMode.ALL_ROUTINES && taskList.isEmpty())
        val noTasksToday = (mode == ChecklistFilterMode.TODAY && taskList.isEmpty())

        PetChecklistUiState(
            pet = currentPet,
            filterMode = mode,
            items = checklistItems,
            completedCount = completedCount,
            totalCount = totalCount,
            formattedDate = formattedDateStr,
            isAllDone = allDone,
            isEmptyState = noTasksExist,
            isNoTasksToday = noTasksToday
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PetChecklistUiState()
    )

    // ── Filter mode control ──────────────────────────────────────────────
    fun setFilterMode(mode: ChecklistFilterMode) {
        _filterMode.value = mode
    }

    /**
     * Updates the current date for checklist evaluation.
     * Called on [PetChecklistFragment.onResume] or when receiving [Intent.ACTION_DATE_CHANGED].
     */
    fun updateTodayDate() {
        _todayDate.value = LocalDate.now()
    }

    // ── Mark done / undone ───────────────────────────────────────────────
    fun toggleTaskDone(task: TaskEntity, isChecked: Boolean) {
        viewModelScope.launch {
            if (isChecked) {
                taskRepository.markDone(task.id, _todayDate.value.toString())
            } else {
                taskRepository.markUndone(task.id)
            }
        }
    }

    // ── Delete task ──────────────────────────────────────────────────────
    fun deleteTask(task: TaskEntity) {
        viewModelScope.launch {
            val snapshot = taskRepository.deleteTaskWithSnapshot(task.id)
            if (snapshot != null) {
                reminderScheduler.cancel(task.id)
                _event.value = ChecklistEvent.TaskDeleted(snapshot, task.name)
            } else {
                _event.value = ChecklistEvent.ShowError("Task not found")
            }
        }
    }

    // ── Restore task (Undo) ──────────────────────────────────────────────
    fun restoreTask(snapshot: TaskSnapshot) {
        viewModelScope.launch {
            taskRepository.restoreTaskSnapshot(snapshot)
            if (snapshot.task.reminderEnabled) {
                reminderScheduler.schedule(snapshot.task)
            }
        }
    }

    // ── Reset today's checklist (Shake gesture / Menu action) ────────────
    /**
     * Resets all completed tasks for today, preserving previous states for Undo.
     */
    fun resetTodayChecklist() {
        viewModelScope.launch {
            val currentTasks = (uiState.value.items.filterIsInstance<ChecklistItem.Task>())
                .filter { it.isDoneToday }
            if (currentTasks.isEmpty()) {
                _event.value = ChecklistEvent.ChecklistReset(emptyMap())
                return@launch
            }

            val backup = currentTasks.associate { it.task.id to it.task.lastCompletedDate }
            taskRepository.resetChecklist(petId)
            _event.value = ChecklistEvent.ChecklistReset(backup)
        }
    }

    /**
     * Restores previous completion dates after a reset undo action.
     */
    fun restoreResetChecklist(backup: Map<Long, String?>) {
        viewModelScope.launch {
            backup.forEach { (taskId, date) ->
                if (date != null) {
                    taskRepository.markDone(taskId, date)
                }
            }
        }
    }

    // ── Batch operations (Long-press multi-select ActionMode) ─────────────
    /**
     * Deletes multiple selected tasks with undo snapshot retention.
     */
    fun batchDeleteTasks(taskIds: Set<Long>) {
        viewModelScope.launch {
            val snapshots = mutableListOf<TaskSnapshot>()
            for (id in taskIds) {
                val snapshot = taskRepository.deleteTaskWithSnapshot(id)
                if (snapshot != null) {
                    reminderScheduler.cancel(id)
                    snapshots.add(snapshot)
                }
            }
            if (snapshots.isNotEmpty()) {
                _event.value = ChecklistEvent.BatchTasksDeleted(snapshots)
            }
        }
    }

    /**
     * Restores a batch of deleted tasks and their reminders.
     */
    fun restoreBatchTasks(snapshots: List<TaskSnapshot>) {
        viewModelScope.launch {
            for (snapshot in snapshots) {
                taskRepository.restoreTaskSnapshot(snapshot)
                if (snapshot.task.reminderEnabled) {
                    reminderScheduler.schedule(snapshot.task)
                }
            }
        }
    }

    /**
     * Marks all selected tasks as completed today.
     */
    fun batchMarkDone(taskIds: Set<Long>) {
        viewModelScope.launch {
            val todayStr = _todayDate.value.toString()
            for (id in taskIds) {
                taskRepository.markDone(id, todayStr)
            }
            _event.value = ChecklistEvent.BatchTasksMarkedDone(taskIds.size)
        }
    }

    fun clearEvent() {
        _event.value = null
    }

    // ── Factory ───────────────────────────────────────────────────────────
    class Factory(
        private val petId: Long,
        private val userId: Long,
        private val petRepository: PetRepository,
        private val taskRepository: TaskRepository,
        private val reminderScheduler: ReminderScheduler
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == PetChecklistViewModel::class.java) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return PetChecklistViewModel(
                petId = petId,
                userId = userId,
                petRepository = petRepository,
                taskRepository = taskRepository,
                reminderScheduler = reminderScheduler
            ) as T
        }
    }
}
