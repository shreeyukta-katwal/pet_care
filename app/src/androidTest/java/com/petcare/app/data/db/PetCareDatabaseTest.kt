package com.petcare.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for [PetCareDatabase], [UserDao], [PetDao], and [TaskDao].
 *
 * Runs on an Android device/emulator using an **in-memory** Room database so that:
 * - No data persists between test runs (isolated, repeatable).
 * - Tests are fast (no disk I/O).
 * - The real Room schema is validated against the entity definitions.
 *
 * Test scenario:
 * 1. Insert a [UserEntity].
 * 2. Insert a [PetEntity] for that user.
 * 3. Insert a [TaskEntity] for that pet.
 * 4. Assert all three records can be read back.
 * 5. Delete the user and assert cascade removes the pet and task.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PetCareDatabaseTest {

    /** The in-memory database under test. */
    private lateinit var db: PetCareDatabase

    /** DAO references obtained from the test database. */
    private lateinit var userDao: UserDao
    private lateinit var petDao: PetDao
    private lateinit var taskDao: TaskDao

    /**
     * Sets up a fresh in-memory [PetCareDatabase] before each test.
     *
     * Suspend functions in Room automatically dispatch off the main thread,
     * ensuring safe execution inside [runTest].
     */
    @Before
    fun createDb() {
        val context: Context = ApplicationProvider.getApplicationContext()

        // Build a fully in-memory database; discarded when the process ends.
        // Room suspend functions dispatch off the main thread automatically.
        db = Room.inMemoryDatabaseBuilder(context, PetCareDatabase::class.java)
            .build()

        userDao = db.userDao()
        petDao  = db.petDao()
        taskDao = db.taskDao()
    }

    /**
     * Closes the database after each test to release resources.
     */
    @After
    fun closeDb() {
        db.close()
    }

    /**
     * Tests that a user, pet, and task can be inserted and read back,
     * and that deleting the user cascades to remove the pet and task.
     *
     * This validates:
     * - Room entity mappings and TypeConverters work correctly.
     * - Foreign key constraints are enforced with CASCADE delete.
     * - All DAOs return the expected data.
     */
    @Test
    fun insertUserPetTask_thenDeleteUser_cascadesCorrectly() = runTest {
        // ── Step 1: Insert a user ──────────────────────────────────────
        val userId = userDao.insert(
            UserEntity(
                email = "test@example.com",
                passwordHash = "abc123hash",
                salt = "randomsalt"
            )
        )
        // Verify the user was assigned a valid auto-generated id (> 0)
        assert(userId > 0) { "Expected userId > 0 but got $userId" }

        // ── Step 2: Insert a pet for that user ────────────────────────
        val petId = petDao.insert(
            PetEntity(
                userId       = userId,
                name         = "Buddy",
                species      = "Dog",
                breed        = "Labrador",
                ageYears     = 3.0f,
                weightKg     = 25.0f,
                diet         = "Dry kibble",
                allergies    = "None",
                vaccinations = "Up to date",
                favouriteToys = "Ball",
                notes        = "Loves fetch",
                photoUri     = null
            )
        )
        assert(petId > 0) { "Expected petId > 0 but got $petId" }

        // ── Step 3: Insert a task for that pet ────────────────────────
        val taskId = taskDao.insert(
            TaskEntity(
                petId          = petId,
                name           = "Morning walk",
                category       = TaskCategory.EXERCISE,
                frequency      = TaskFrequency.DAILY,
                daysOfWeek     = 0,      // Ignored for DAILY tasks
                hour           = 7,
                minute         = 30,
                supplies       = "Leash, treats",
                notes          = "30 minutes around the park",
                reminderEnabled = true,
                lastCompletedDate = null
            )
        )
        assert(taskId > 0) { "Expected taskId > 0 but got $taskId" }

        // ── Step 4: Verify all records can be read back ───────────────

        // User lookup by email
        val foundUser = userDao.findByEmail("test@example.com")
        assertNotNull("User should be found by email", foundUser)
        assertEquals("test@example.com", foundUser!!.email)

        // User lookup by id
        val foundById = userDao.findById(userId)
        assertNotNull("User should be found by id", foundById)

        // Pet lookup by id
        val foundPet = petDao.findById(petId)
        assertNotNull("Pet should be found by id", foundPet)
        assertEquals("Buddy", foundPet!!.name)
        assertEquals(userId, foundPet.userId)

        // Task lookup by id
        val foundTask = taskDao.findById(taskId)
        assertNotNull("Task should be found by id", foundTask)
        assertEquals("Morning walk", foundTask!!.name)
        // Verify TypeConverter round-trip: enum stored as string and restored correctly
        assertEquals(TaskCategory.EXERCISE, foundTask.category)
        assertEquals(TaskFrequency.DAILY, foundTask.frequency)

        // ── Step 5: Delete the user and verify cascade delete ─────────
        // Deleting the user must trigger CASCADE delete on pets,
        // which in turn triggers CASCADE delete on all tasks.
        userDao.deleteById(userId)

        // Verify user was deleted
        assertNull("User should be null after deletion", userDao.findById(userId))

        // Verify pet was deleted via CASCADE
        assertNull("Pet should be null after parent user was deleted (CASCADE)", petDao.findById(petId))

        // Verify task was deleted via CASCADE
        assertNull("Task should be null after pet was cascade-deleted (CASCADE)", taskDao.findById(taskId))
    }
}
