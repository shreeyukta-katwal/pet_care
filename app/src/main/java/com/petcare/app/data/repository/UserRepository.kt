package com.petcare.app.data.repository

import com.petcare.app.data.db.UserDao
import com.petcare.app.data.db.UserEntity

/**
 * Repository for user account operations.
 *
 * Acts as the single source of truth for user data, sitting between the
 * [UserDao] and the ViewModels. Business logic (email normalisation,
 * password hashing) will be added in Step 2; this layer focuses on the
 * data access contract.
 *
 * All functions are suspend so they must be called from a coroutine context
 * (typically [kotlinx.coroutines.Dispatchers.IO] in the ViewModel).
 *
 * @param userDao The DAO for direct database access.
 */
class UserRepository(private val userDao: UserDao) {

    /**
     * Inserts a new user record into the database.
     *
     * The caller is responsible for pre-processing:
     * - Email must already be lowercased and trimmed.
     * - [user.passwordHash] and [user.salt] must already be set correctly.
     *
     * Throws [android.database.sqlite.SQLiteConstraintException] if the email
     * already exists (unique index violation). The ViewModel catches this.
     *
     * @param user A [UserEntity] with id=0 (auto-generate) and prepared fields.
     * @return The auto-generated row ID of the new user.
     */
    suspend fun insert(user: UserEntity): Long = userDao.insert(user)

    /**
     * Inserts a user preserving its original [UserEntity.id].
     *
     * Used exclusively by the undo/restore path to re-insert a previously
     * deleted user with the same primary key, maintaining referential integrity
     * with any associated pets that might be restored afterward.
     *
     * @param user The [UserEntity] to restore.
     */
    suspend fun insertWithId(user: UserEntity) = userDao.insertWithId(user)

    /**
     * Finds a user by their email address.
     *
     * @param email Normalised (lowercase, trimmed) email to look up.
     * @return The [UserEntity] if found, or null if no account exists.
     */
    suspend fun findByEmail(email: String): UserEntity? = userDao.findByEmail(email)

    /**
     * Finds a user by their primary key.
     *
     * Used by [com.petcare.app.data.session.SessionManager] to reload the
     * current user's record after the app restarts.
     *
     * @param id The user's primary key.
     * @return The [UserEntity] if found, or null if the id is stale/invalid.
     */
    suspend fun findById(id: Long): UserEntity? = userDao.findById(id)

    /**
     * Updates an existing user's record.
     *
     * @param user The [UserEntity] with updated fields (matched by primary key).
     */
    suspend fun update(user: UserEntity) = userDao.update(user)
}
