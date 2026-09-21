package com.petcare.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for [UserEntity].
 *
 * Provides all database operations related to user accounts.
 * All suspend functions must be called from a coroutine (never from the main thread).
 * There are no Flow-returning queries here because user state is loaded once on login
 * and held in [SessionManager], not observed continuously.
 */
@Dao
interface UserDao {

    /**
     * Inserts a new user into the database.
     *
     * Uses [OnConflictStrategy.ABORT] so that inserting a duplicate email
     * throws a [android.database.sqlite.SQLiteConstraintException], which the
     * repository layer catches and converts into a typed error result.
     *
     * @param user The [UserEntity] to insert (id should be 0 for auto-generation).
     * @return The auto-generated row ID of the newly inserted user.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: UserEntity): Long

    /**
     * Inserts a user with a specific id (used by the undo/restore path).
     * REPLACE strategy overwrites any row with the same id, which is safe
     * because the restore operation always uses the original id.
     *
     * @param user The [UserEntity] to restore, including its original [UserEntity.id].
     * @return The row id of the inserted or replaced row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(user: UserEntity): Long

    /**
     * Looks up a user by email address.
     *
     * Email is stored lowercase and trimmed; callers must normalise before calling.
     * Returns null if no matching user exists (used to detect unregistered email).
     *
     * @param email Lowercase, trimmed email address.
     * @return The matching [UserEntity], or null if not found.
     */
    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun findByEmail(email: String): UserEntity?

    /**
     * Looks up a user by their primary key.
     *
     * Used by [SessionManager] to reload the current user after app restart.
     *
     * @param id The user's primary key.
     * @return The [UserEntity] with the given id, or null if not found.
     */
    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): UserEntity?

    /**
     * Updates an existing user record (e.g., password change in a future step).
     *
     * @param user The [UserEntity] with updated fields (matched by primary key).
     */
    @Update
    suspend fun update(user: UserEntity)

    /**
     * Deletes a user by primary key.
     *
     * Foreign key constraints with CASCADE will automatically delete all associated
     * [PetEntity] records, which in turn CASCADE deletes all [TaskEntity] records.
     *
     * @param id The user's primary key.
     */
    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteById(id: Long)
}
