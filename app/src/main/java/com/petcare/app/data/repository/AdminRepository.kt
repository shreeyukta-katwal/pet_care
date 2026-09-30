package com.petcare.app.data.repository

import androidx.room.withTransaction
import com.petcare.app.data.db.AppStats
import com.petcare.app.data.db.PetCareDatabase
import com.petcare.app.data.db.PetDao
import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.db.TaskDao
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.db.UserDao
import com.petcare.app.data.db.UserWithCounts
import com.petcare.app.data.security.PasswordHasher
import com.petcare.app.reminder.ReminderScheduler
import java.security.SecureRandom
import java.time.LocalDate

/**
 * Privileged repository for the small, read-only administration surface.
 *
 * This is deliberately separate from owner-facing repositories so cross-user
 * queries cannot accidentally be introduced into normal application screens.
 */
class AdminRepository(
    private val database: PetCareDatabase,
    private val userDao: UserDao,
    private val petDao: PetDao,
    private val taskDao: TaskDao,
    private val reminderScheduler: ReminderScheduler
) {
    /** Returns all accounts with their aggregate pet and task counts. */
    suspend fun getAllUsersWithCounts(): List<UserWithCounts> = userDao.getAllWithCounts()

    /** Returns app-wide totals; this is intentionally an administrator-only query. */
    suspend fun getAppStats(): AppStats = userDao.getAppStats(LocalDate.now().toString())

    /** Returns every pet for the requested account for the admin's read-only detail screen. */
    suspend fun getPetsForUser(userId: Long): List<PetEntity> = petDao.getAllForUserOnce(userId)

    /** Returns all of a user's tasks grouped by their pet in the caller's presentation layer. */
    suspend fun getTasksForPet(petId: Long): List<TaskEntity> = taskDao.getAllForPetOnce(petId)

    /**
     * Cancels reminders before the account's cascading delete removes its tasks.
     * The actual account/pet/task deletion runs atomically in Room.
     */
    suspend fun deleteUserAccount(userId: Long) {
        taskDao.getAllForUserOnce(userId).forEach { reminderScheduler.cancel(it.id) }
        database.withTransaction { userDao.deleteById(userId) }
    }

    /** Replaces an account's credential with a newly generated temporary password. */
    suspend fun resetUserPassword(userId: Long): String {
        val user = userDao.findById(userId) ?: error("User account no longer exists")
        val temporaryPassword = buildTemporaryPassword()
        val hash = PasswordHasher.hash(temporaryPassword)
        userDao.update(user.copy(passwordHash = hash.hashBase64, salt = hash.saltBase64))
        return temporaryPassword
    }

    /** Generates a validation-compliant, non-predictable temporary password. */
    private fun buildTemporaryPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return buildString {
            append(('A'.code + random.nextInt(26)).toChar())
            append(('a'.code + random.nextInt(26)).toChar())
            append(('0'.code + random.nextInt(10)).toChar())
            repeat(13) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
}
