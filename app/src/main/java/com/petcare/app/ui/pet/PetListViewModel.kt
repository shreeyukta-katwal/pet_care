package com.petcare.app.ui.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.db.TaskFrequency
import com.petcare.app.data.repository.PetRepository
import com.petcare.app.data.repository.PetSnapshot
import com.petcare.app.data.repository.TaskRepository
import com.petcare.app.reminder.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * UI model representing a pet row on the pet list dashboard with today's checklist progress.
 */
data class PetItemUiState(
    val pet: PetEntity,
    val completedCount: Int = 0,
    val totalCount: Int = 0
) {
    val progressText: String
        get() = when {
            totalCount == 0 -> ""
            completedCount == totalCount -> "• All done!"
            else -> "• $completedCount/$totalCount done"
        }
}

// ── One-shot event sealed class ───────────────────────────────────────────────

/**
 * Sealed class representing one-shot UI events emitted by [PetListViewModel].
 */
sealed class PetListEvent {
    data class PetDeleted(val snapshot: PetSnapshot, val petName: String) : PetListEvent()
    data class BatchPetsDeleted(val snapshots: List<PetSnapshot>) : PetListEvent()
    data class ShowError(val message: String) : PetListEvent()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * ViewModel for [PetListFragment].
 */
class PetListViewModel(
    userId: Long,
    private val petRepository: PetRepository,
    private val reminderScheduler: ReminderScheduler,
    private val taskRepository: TaskRepository? = null
) : ViewModel() {

    // ── Pet list state with progress ──────────────────────────────────────

    val pets: StateFlow<List<PetEntity>> = petRepository.getAllForUser(userId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val petsWithProgress: StateFlow<List<PetItemUiState>> = if (taskRepository != null) {
        combine(petRepository.getAllForUser(userId), taskRepository.getAllTasks()) { petList, allTasks ->
            val today = LocalDate.now()
            val dayOfWeekBit = 1 shl (today.dayOfWeek.value - 1)
            val todayStr = today.toString()

            petList.map { pet ->
                val petTasks = allTasks.filter { it.petId == pet.id }
                val todaysTasks = petTasks.filter { task ->
                    when (task.frequency) {
                        TaskFrequency.DAILY -> true
                        TaskFrequency.WEEKLY -> (task.daysOfWeek and dayOfWeekBit) != 0
                    }
                }
                val completed = todaysTasks.count { it.lastCompletedDate == todayStr }
                PetItemUiState(pet = pet, completedCount = completed, totalCount = todaysTasks.size)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )
    } else {
        pets.map { list -> list.map { PetItemUiState(pet = it) } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )
    }

    // ── One-shot event channel ────────────────────────────────────────────

    /**
     * One-shot events consumed once by the Fragment.
     *
     * Null when no pending event; set to a [PetListEvent] instance when an
     * action (delete/error) occurs; reset to null after the Fragment consumes it.
     */
    private val _event = MutableStateFlow<PetListEvent?>(null)
    val event: StateFlow<PetListEvent?> = _event.asStateFlow()

    // ── Delete ────────────────────────────────────────────────────────────

    /**
     * Deletes the given pet and all its tasks inside a single Room transaction.
     *
     * Emits a [PetListEvent.PetDeleted] event so the Fragment can show an Undo
     * Snackbar. If the pet no longer exists (already deleted), emits an error.
     *
     * The photo file is NOT deleted here; deletion is deferred until the Snackbar
     * times out without Undo (handled in the Fragment).
     *
     * @param pet The [PetEntity] to delete.
     */
    fun deletePet(pet: PetEntity) {
        viewModelScope.launch {
            val snapshot = petRepository.deletePetWithTasks(pet.id)
            if (snapshot != null) {
                _event.value = PetListEvent.PetDeleted(snapshot = snapshot, petName = pet.name)
            } else {
                _event.value = PetListEvent.ShowError("Pet not found or already deleted.")
            }
        }
    }

    // ── Restore (Undo) ────────────────────────────────────────────────────

    /**
     * Restores a previously deleted pet and all its tasks using original IDs.
     *
     * Called when the user taps "Undo" on the delete Snackbar. After restore,
     * any tasks with a set reminder are re-scheduled via [ReminderScheduler].
     *
     * @param snapshot The [PetSnapshot] returned by the delete operation.
     */
    fun restorePet(snapshot: PetSnapshot) {
        viewModelScope.launch {
            petRepository.restore(snapshot)
            // Re-schedule reminders for any tasks that had them
            snapshot.tasks.forEach { task ->
                reminderScheduler.schedule(task)
            }
        }
    }

    /**
     * Batch deletes multiple selected pets inside a coroutine.
     * Cancels reminders for each pet and preserves snapshots for undo.
     */
    fun batchDeletePets(petsToDelete: List<PetEntity>) {
        viewModelScope.launch {
            val snapshots = mutableListOf<PetSnapshot>()
            for (pet in petsToDelete) {
                val snapshot = petRepository.deletePetWithTasks(pet.id)
                if (snapshot != null) {
                    reminderScheduler.cancelForPet(pet.id)
                    snapshots.add(snapshot)
                }
            }
            if (snapshots.isNotEmpty()) {
                _event.value = PetListEvent.BatchPetsDeleted(snapshots)
            }
        }
    }

    /**
     * Restores a batch of deleted pets and their reminders.
     */
    fun restoreBatchPets(snapshots: List<PetSnapshot>) {
        viewModelScope.launch {
            for (snapshot in snapshots) {
                petRepository.restore(snapshot)
                snapshot.tasks.forEach { task ->
                    if (task.reminderEnabled) {
                        reminderScheduler.schedule(task)
                    }
                }
            }
        }
    }

    // ── Event consumption ─────────────────────────────────────────────────

    /**
     * Called by the Fragment after it has consumed the latest [event].
     * Resets the event to null so it is not re-delivered after rotation.
     */
    fun clearEvent() {
        _event.value = null
    }

    // ── Factory ───────────────────────────────────────────────────────────

    /**
     * Factory for creating [PetListViewModel] with constructor parameters.
     *
     * Used because ViewModelProvider does not support constructor injection out
     * of the box (Hilt/Dagger is prohibited by the assignment rules).
     *
     * @param userId            Logged-in user's database ID.
     * @param petRepository     Pet profile repository.
     * @param reminderScheduler Care-task reminder scheduler.
     */
    class Factory(
        private val userId: Long,
        private val petRepository: PetRepository,
        private val reminderScheduler: ReminderScheduler,
        private val taskRepository: TaskRepository? = null
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == PetListViewModel::class.java) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return PetListViewModel(
                userId = userId,
                petRepository = petRepository,
                reminderScheduler = reminderScheduler,
                taskRepository = taskRepository
            ) as T
        }
    }
}
