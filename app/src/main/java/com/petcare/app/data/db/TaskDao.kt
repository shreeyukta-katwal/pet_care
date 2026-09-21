package com.petcare.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [TaskEntity].
 *
 * Provides all CRUD operations for care task records.
 * Read queries return [Flow] for reactive UI updates.
 * Write operations are suspend functions for coroutine use on IO dispatchers.
 */
@Dao
interface TaskDao {

    /**
     * Inserts a new task.
     *
     * @param task The [TaskEntity] to insert (id = 0 for auto-generation).
     * @return The auto-generated row ID of the new task.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity): Long

    /**
     * Inserts a task with a specific id (used by the undo/restore path).
     * REPLACE strategy overwrites any existing row with the same primary key.
     *
     * @param task The [TaskEntity] to restore with its original [TaskEntity.id].
     * @return The row id of the inserted or replaced row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(task: TaskEntity): Long

    /**
     * Updates an existing task's fields (matched by primary key).
     *
     * @param task The [TaskEntity] with updated fields.
     */
    @Update
    suspend fun update(task: TaskEntity)

    /**
     * Deletes a specific task by primary key.
     *
     * @param taskId The primary key of the task to remove.
     */
    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteById(taskId: Long)

    /**
     * Returns a reactive stream of all tasks across all pets.
     *
     * Used by [PetListViewModel] to calculate today's progress for each pet.
     *
     * @return A [Flow] emitting the current list of all [TaskEntity] rows.
     */
    @Query("SELECT * FROM tasks")
    fun getAllTasks(): Flow<List<TaskEntity>>

    /**
     * Returns a one-shot snapshot of all tasks with reminders enabled.
     *
     * Used by [PetCareApp] on app launch to safely reschedule all active WorkManager reminders.
     *
     * @return A list of [TaskEntity] rows where reminderEnabled is true.
     */
    @Query("SELECT * FROM tasks WHERE reminder_enabled = 1")
    suspend fun getAllEnabledTasks(): List<TaskEntity>

    /**
     * Returns a reactive stream of all tasks for a given pet, ordered by category
     * and then by name within each category.
     *
     * This ordering matches the checklist grouping (FEEDING → EXERCISE → GROOMING
     * → MEDICATION → HEALTHCARE) used in the daily checklist screen.
     *
     * @param petId The primary key of the pet whose tasks to retrieve.
     * @return A [Flow] emitting the current list of [TaskEntity] for the pet.
     */
    @Query("""
        SELECT * FROM tasks 
        WHERE pet_id = :petId 
        ORDER BY category ASC, name ASC
    """)
    fun getAllForPet(petId: Long): Flow<List<TaskEntity>>

    /**
     * Returns a one-shot snapshot of all tasks for a pet.
     *
     * Used by the undo snapshot mechanism in [PetRepository] which needs a
     * non-Flow list at a specific point in time before deletion.
     *
     * @param petId The primary key of the pet whose tasks to snapshot.
     * @return A list of [TaskEntity] at the moment of the call.
     */
    @Query("SELECT * FROM tasks WHERE pet_id = :petId")
    suspend fun getAllForPetOnce(petId: Long): List<TaskEntity>

    /**
     * Returns a single task by primary key (one-shot, not reactive).
     *
     * Used when editing a task to pre-fill the form.
     *
     * @param taskId Primary key of the task to retrieve.
     * @return The [TaskEntity] or null if not found.
     */
    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun findById(taskId: Long): TaskEntity?

    /**
     * Updates [TaskEntity.lastCompletedDate] for a specific task.
     *
     * Separating this into its own query avoids loading then re-saving the full
     * entity just to mark a task done, which is important for performance when
     * the checklist has many items.
     *
     * @param taskId        The primary key of the task to mark.
     * @param completedDate The ISO yyyy-MM-dd date string, or null to clear.
     */
    @Query("UPDATE tasks SET last_completed_date = :completedDate WHERE id = :taskId")
    suspend fun updateLastCompletedDate(taskId: Long, completedDate: String?)

    /**
     * Resets [TaskEntity.lastCompletedDate] to null for ALL tasks of a pet.
     *
     * Used by the shake-gesture "reset today's checklist" feature (Step 5).
     * Clears the done-today state so all tasks appear incomplete again.
     *
     * @param petId The primary key of the pet whose checklist to reset.
     */
    @Query("UPDATE tasks SET last_completed_date = NULL WHERE pet_id = :petId")
    suspend fun resetChecklistForPet(petId: Long)
}
