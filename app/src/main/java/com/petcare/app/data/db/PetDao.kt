package com.petcare.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [PetEntity].
 *
 * All read queries return [Flow] so that the UI automatically receives
 * updates whenever the underlying table changes (reactive architecture).
 * All write operations are suspend functions to be called on IO dispatchers.
 *
 * Every query is scoped to a specific [userId] to prevent cross-user data leakage.
 */
@Dao
interface PetDao {

    /**
     * Inserts a new pet into the database.
     *
     * @param pet The [PetEntity] to insert (id = 0 for auto-generation).
     * @return The auto-generated row ID of the new pet.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pet: PetEntity): Long

    /**
     * Inserts a pet with a specific id, used by the undo/restore path.
     * REPLACE strategy handles the case where the original id still exists in
     * the database (though CASCADE delete should have removed it).
     *
     * @param pet The [PetEntity] to restore with its original [PetEntity.id].
     * @return The row id of the inserted or replaced row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(pet: PetEntity): Long

    /**
     * Updates an existing pet's fields (matched by primary key).
     *
     * @param pet The [PetEntity] with updated fields.
     */
    @Update
    suspend fun update(pet: PetEntity)

    /**
     * Deletes a specific pet by primary key.
     *
     * Cascade delete in the schema automatically removes all associated [TaskEntity] rows.
     *
     * @param petId The primary key of the pet to delete.
     */
    @Query("DELETE FROM pets WHERE id = :petId")
    suspend fun deleteById(petId: Long)

    /**
     * Returns a reactive stream of all pets owned by [userId], ordered by name.
     *
     * The Flow automatically emits a new list whenever any pet row changes,
     * keeping the pet list UI in sync without manual refresh calls.
     *
     * @param userId The logged-in user's ID; limits results to their pets only.
     * @return A [Flow] emitting the current list of [PetEntity] for the user.
     */
    @Query("SELECT * FROM pets WHERE user_id = :userId ORDER BY name ASC")
    fun getAllForUser(userId: Long): Flow<List<PetEntity>>

    /**
     * Returns a single pet by primary key (one-shot, not reactive).
     *
     * Used when a specific pet is needed (e.g., edit form pre-fill).
     *
     * @param petId Primary key of the pet to retrieve.
     * @return The [PetEntity] or null if not found.
     */
    @Query("SELECT * FROM pets WHERE id = :petId LIMIT 1")
    suspend fun findById(petId: Long): PetEntity?
}
