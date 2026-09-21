package com.petcare.app.data.repository

import com.petcare.app.data.db.TaskDao
import com.petcare.app.data.db.TaskEntity
import kotlinx.coroutines.flow.Flow

import com.petcare.app.data.db.TaskFrequency
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Snapshot holding a single task for undo-delete support.
 *
 * Created before a task is deleted so that [TaskRepository.restoreTaskSnapshot]
 * can re-insert it with the same primary key.
 *
 * @property task The [TaskEntity] that was deleted.
 */
data class TaskSnapshot(val task: TaskEntity)

/**
 * Repository for care task operations.
 *
 * Sits between [TaskDao] and the ViewModels. Provides reactive reads via [Flow]
 * and suspend functions for all write operations.
 *
 * Mirrors the undo-delete pattern used in [PetRepository]:
 * [deleteTaskWithSnapshot] captures the task before removal and
 * [restoreTaskSnapshot] puts it back with its original id.
 *
 * @param taskDao The DAO for direct database access.
 */
open class TaskRepository(private val taskDao: TaskDao) {

    /**
     * Returns a reactive stream of all tasks across all pets.
     *
     * @return A [Flow] emitting the complete list of [TaskEntity] rows.
     */
    open fun getAllTasks(): Flow<List<TaskEntity>> = taskDao.getAllTasks()

    /**
     * Retrieves all tasks that have reminders enabled (one-shot, non-reactive).
     *
     * @return A list of [TaskEntity] with reminderEnabled == true.
     */
    open suspend fun getAllEnabledTasks(): List<TaskEntity> = taskDao.getAllEnabledTasks()

    /**
     * Returns a reactive stream of all tasks for a given pet.
     *
     * The [Flow] emits a new list whenever any task row for this pet changes.
     * Results are ordered by category then name (matches checklist grouping).
     *
     * @param petId The pet's primary key.
     * @return A [Flow] emitting the current task list.
     */
    open fun getAllForPet(petId: Long): Flow<List<TaskEntity>> =
        taskDao.getAllForPet(petId)

    /**
     * Inserts a new task, returning its auto-generated ID.
     *
     * @param task A [TaskEntity] with id=0 and a valid [TaskEntity.petId].
     * @return The new task's auto-generated row ID.
     */
    open suspend fun insert(task: TaskEntity): Long = taskDao.insert(task)

    /**
     * Updates an existing task's fields.
     *
     * @param task The [TaskEntity] with updated values (matched by primary key).
     */
    open suspend fun update(task: TaskEntity) = taskDao.update(task)

    /**
     * Retrieves a single task by primary key (one-shot, non-reactive).
     *
     * @param taskId The task's primary key.
     * @return The [TaskEntity] or null if not found.
     */
    open suspend fun findById(taskId: Long): TaskEntity? = taskDao.findById(taskId)

    /**
     * Captures a snapshot of the task then deletes it.
     *
     * Used for swipe-left delete with Snackbar undo in the task list / checklist.
     *
     * @param taskId The primary key of the task to delete.
     * @return A [TaskSnapshot] for undo, or null if the task was not found.
     */
    open suspend fun deleteTaskWithSnapshot(taskId: Long): TaskSnapshot? {
        val task = taskDao.findById(taskId) ?: return null
        taskDao.deleteById(taskId)
        return TaskSnapshot(task)
    }

    /**
     * Restores a previously deleted task using its original primary key.
     *
     * @param snapshot The [TaskSnapshot] returned by [deleteTaskWithSnapshot].
     */
    open suspend fun restoreTaskSnapshot(snapshot: TaskSnapshot) {
        taskDao.insertWithId(snapshot.task)
    }

    /**
     * Marks a task as completed today by setting [TaskEntity.lastCompletedDate]
     * to today's ISO date string.
     *
     * "Done today" is determined by comparing [TaskEntity.lastCompletedDate] to
     * the current ISO date; there is no midnight reset job.
     *
     * @param taskId        The primary key of the task to mark done.
     * @param todayIsoDate  Today's date as yyyy-MM-dd (caller provides this).
     */
    open suspend fun markDone(taskId: Long, todayIsoDate: String) {
        taskDao.updateLastCompletedDate(taskId, todayIsoDate)
    }

    /**
     * Clears the completion state for a task (marks it undone).
     *
     * @param taskId The primary key of the task to un-mark.
     */
    open suspend fun markUndone(taskId: Long) {
        taskDao.updateLastCompletedDate(taskId, null)
    }

    /**
     * Resets the entire checklist for a pet by clearing all completion dates.
     *
     * Called by the shake-gesture "reset today's checklist" feature.
     *
     * @param petId The pet's primary key whose checklist should be reset.
     */
    open suspend fun resetChecklist(petId: Long) {
        taskDao.resetChecklistForPet(petId)
    }

    /**
     * Automatic checklist generation:
     * Returns a reactive stream of care tasks active for [petId] on [today].
     *
     * Filtering rule:
     * - [TaskFrequency.DAILY] tasks are always included.
     * - [TaskFrequency.WEEKLY] tasks are included if the bit corresponding to [today]'s
     *   weekday is set in [TaskEntity.daysOfWeek]
     *   (Mon = 1 (1 shl 0), Tue = 2 (1 shl 1), ..., Sun = 64 (1 shl 6)).
     *
     * Tasks are ordered by category, then by scheduled time ([TaskEntity.hour], [TaskEntity.minute]),
     * then by name.
     *
     * @param petId Primary key of the pet.
     * @param today Current date for checklist evaluation (defaults to [LocalDate.now]).
     * @return A [Flow] emitting the list of tasks scheduled for today.
     */
    open fun todaysTasksForPet(
        petId: Long,
        today: LocalDate = LocalDate.now()
    ): Flow<List<TaskEntity>> {
        val dayOfWeekBit = 1 shl (today.dayOfWeek.value - 1) // Mon=1..Sun=7 -> 1..64
        return taskDao.getAllForPet(petId).map { tasks ->
            tasks.filter { task ->
                when (task.frequency) {
                    TaskFrequency.DAILY -> true
                    TaskFrequency.WEEKLY -> (task.daysOfWeek and dayOfWeekBit) != 0
                }
            }.sortedWith(
                compareBy<TaskEntity> { it.category }
                    .thenBy { it.hour }
                    .thenBy { it.minute }
                    .thenBy { it.name }
            )
        }
    }
}
