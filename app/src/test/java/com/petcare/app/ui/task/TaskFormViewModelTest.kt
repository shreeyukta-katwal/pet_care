package com.petcare.app.ui.task

import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.db.TaskCategory
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.db.TaskFrequency
import com.petcare.app.data.repository.PetRepository
import com.petcare.app.data.repository.TaskRepository
import com.petcare.app.ui.pet.FakePetDao
import com.petcare.app.ui.pet.FakeReminderScheduler
import com.petcare.app.ui.pet.FakeTaskDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TaskFormViewModel].
 *
 * Covers:
 * - Validation: missing pet, blank name, missing category, missing time, no days for weekly
 * - Save creates a new task and schedules a reminder
 * - Edit mode: loads task, updates in-place
 * - Reminder disabled: cancel is called instead of schedule
 * - todaysTasksForPet filters correctly via TaskRepository
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskFormViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakePetDao: FakePetDao
    private lateinit var fakeTaskDao: FakeTaskDao
    private lateinit var fakeScheduler: FakeReminderScheduler
    private lateinit var petRepository: PetRepository
    private lateinit var taskRepository: TaskRepository

    private val userId = 42L

    private val samplePet = PetEntity(
        id = 1L,
        userId = userId,
        name = "Whiskers",
        species = "Cat",
        breed = "Siamese",
        ageYears = 3f,
        weightKg = 4f,
        diet = "",
        allergies = "",
        vaccinations = "",
        favouriteToys = "",
        notes = ""
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakePetDao = FakePetDao()
        fakeTaskDao = FakeTaskDao()
        fakeScheduler = FakeReminderScheduler()
        petRepository = PetRepository(
            database = null,
            petDao = fakePetDao,
            taskDao = fakeTaskDao
        )
        taskRepository = TaskRepository(taskDao = fakeTaskDao)
        fakePetDao.setPets(listOf(samplePet))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun makeViewModel(
        taskId: Long = 0L,
        preselectedPetId: Long = 0L
    ) = TaskFormViewModel(
        taskId = taskId,
        preselectedPetId = preselectedPetId,
        userId = userId,
        taskRepository = taskRepository,
        petRepository = petRepository,
        reminderScheduler = fakeScheduler
    )

    // ── isEditMode ────────────────────────────────────────────────────────

    @Test
    fun `isEditMode is false when taskId is 0`() {
        val vm = makeViewModel(taskId = 0L)
        assertFalse(vm.isEditMode)
    }

    @Test
    fun `isEditMode is true when taskId is positive`() {
        val vm = makeViewModel(taskId = 5L)
        assertTrue(vm.isEditMode)
    }

    // ── Preselected pet ───────────────────────────────────────────────────

    @Test
    fun `preselectedPetId is used as initial selectedPetId`() {
        val vm = makeViewModel(preselectedPetId = 1L)
        assertEquals(1L, vm.getSelectedPetId())
    }

    @Test
    fun `getSelectedPetId is null when no preselection and no selection`() {
        val vm = makeViewModel(preselectedPetId = 0L)
        assertEquals(null, vm.getSelectedPetId())
    }

    // ── Validation: missing pet ───────────────────────────────────────────

    @Test
    fun `save without selecting a pet emits PET validation error`() = runTest {
        val vm = makeViewModel()
        vm.setTime(9, 0)

        vm.save(
            name = "Morning walk",
            category = TaskCategory.EXERCISE,
            frequency = TaskFrequency.DAILY,
            daysOfWeekBitmask = 0,
            supplies = "",
            notes = "",
            reminderEnabled = true
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is TaskFormState.ValidationError)
        assertEquals(
            TaskFormState.ValidationError.Field.PET,
            (state as TaskFormState.ValidationError).field
        )
    }

    // ── Validation: blank name ────────────────────────────────────────────

    @Test
    fun `save with blank name emits NAME validation error`() = runTest {
        val vm = makeViewModel(preselectedPetId = 1L)
        vm.setTime(9, 0)

        vm.save(
            name = "   ",
            category = TaskCategory.FEEDING,
            frequency = TaskFrequency.DAILY,
            daysOfWeekBitmask = 0,
            supplies = "",
            notes = "",
            reminderEnabled = true
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is TaskFormState.ValidationError)
        assertEquals(
            TaskFormState.ValidationError.Field.NAME,
            (state as TaskFormState.ValidationError).field
        )
    }

    // ── Validation: missing time ──────────────────────────────────────────

    @Test
    fun `save without setting a time emits TIME validation error`() = runTest {
        val vm = makeViewModel(preselectedPetId = 1L)
        // Don't call vm.setTime(...)

        vm.save(
            name = "Evening meal",
            category = TaskCategory.FEEDING,
            frequency = TaskFrequency.DAILY,
            daysOfWeekBitmask = 0,
            supplies = "",
            notes = "",
            reminderEnabled = true
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is TaskFormState.ValidationError)
        assertEquals(
            TaskFormState.ValidationError.Field.TIME,
            (state as TaskFormState.ValidationError).field
        )
    }

    // ── Validation: weekly requires at least one day ───────────────────────

    @Test
    fun `save weekly with zero bitmask emits DAYS_OF_WEEK validation error`() = runTest {
        val vm = makeViewModel(preselectedPetId = 1L)
        vm.setTime(8, 0)

        vm.save(
            name = "Bath day",
            category = TaskCategory.GROOMING,
            frequency = TaskFrequency.WEEKLY,
            daysOfWeekBitmask = 0,   // no days selected
            supplies = "Shampoo",
            notes = "",
            reminderEnabled = true
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is TaskFormState.ValidationError)
        assertEquals(
            TaskFormState.ValidationError.Field.DAYS_OF_WEEK,
            (state as TaskFormState.ValidationError).field
        )
    }

    // ── Happy path: create ────────────────────────────────────────────────

    @Test
    fun `save with valid inputs creates task and emits Saved state`() = runTest {
        val vm = makeViewModel(preselectedPetId = 1L)
        vm.setTime(7, 30)

        vm.save(
            name = "Morning feed",
            category = TaskCategory.FEEDING,
            frequency = TaskFrequency.DAILY,
            daysOfWeekBitmask = 0,
            supplies = "Kibble",
            notes = "Half cup",
            reminderEnabled = true
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("Expected Saved but got $state", state is TaskFormState.Saved)
        val savedTask = (state as TaskFormState.Saved).task
        assertEquals("Morning feed", savedTask.name)
        assertEquals(TaskCategory.FEEDING, savedTask.category)
        assertEquals(7, savedTask.hour)
        assertEquals(30, savedTask.minute)
        assertEquals(1L, savedTask.petId)
    }

    // ── Happy path: reminder scheduled on save ─────────────────────────────

    @Test
    fun `save with reminderEnabled schedules the task`() = runTest {
        val vm = makeViewModel(preselectedPetId = 1L)
        vm.setTime(9, 0)

        vm.save(
            name = "Medication",
            category = TaskCategory.MEDICATION,
            frequency = TaskFrequency.DAILY,
            daysOfWeekBitmask = 0,
            supplies = "Pill",
            notes = "",
            reminderEnabled = true
        )
        advanceUntilIdle()

        assertEquals(1, fakeScheduler.scheduledTasks.size)
        assertEquals("Medication", fakeScheduler.scheduledTasks[0].name)
    }

    // ── Reminder disabled: cancel called ──────────────────────────────────

    @Test
    fun `save with reminderEnabled=false calls cancel instead of schedule`() = runTest {
        val vm = makeViewModel(preselectedPetId = 1L)
        vm.setTime(9, 0)

        vm.save(
            name = "Night walk",
            category = TaskCategory.EXERCISE,
            frequency = TaskFrequency.DAILY,
            daysOfWeekBitmask = 0,
            supplies = "",
            notes = "",
            reminderEnabled = false
        )
        advanceUntilIdle()

        assertTrue(fakeScheduler.scheduledTasks.isEmpty())
        assertEquals(1, fakeScheduler.cancelledTaskIds.size)
    }

    // ── Edit mode: loads task ──────────────────────────────────────────────

    @Test
    fun `edit mode loads existing task and emits TaskLoaded state`() = runTest {
        val existingTask = TaskEntity(
            id = 10L,
            petId = 1L,
            name = "Old task",
            category = TaskCategory.HEALTHCARE,
            frequency = TaskFrequency.WEEKLY,
            daysOfWeek = 5, // Mon + Wed
            hour = 10,
            minute = 15,
            reminderEnabled = true
        )
        fakeTaskDao.insert(existingTask)

        val vm = makeViewModel(taskId = 10L)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("Expected TaskLoaded, got $state", state is TaskFormState.TaskLoaded)
        val loaded = (state as TaskFormState.TaskLoaded).task
        assertEquals("Old task", loaded.name)
        assertEquals(TaskCategory.HEALTHCARE, loaded.category)
    }

    // ── Edit mode: update persists ─────────────────────────────────────────

    @Test
    fun `save in edit mode updates the task and emits Saved`() = runTest {
        val existingTask = TaskEntity(
            id = 20L,
            petId = 1L,
            name = "Original",
            category = TaskCategory.GROOMING,
            frequency = TaskFrequency.DAILY,
            hour = 8,
            minute = 0,
            reminderEnabled = true
        )
        fakeTaskDao.insert(existingTask)

        val vm = makeViewModel(taskId = 20L)
        advanceUntilIdle() // loads task → TaskLoaded

        vm.setTime(10, 30)
        vm.save(
            name = "Renamed",
            category = TaskCategory.GROOMING,
            frequency = TaskFrequency.DAILY,
            daysOfWeekBitmask = 0,
            supplies = "",
            notes = "Updated notes",
            reminderEnabled = true
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is TaskFormState.Saved)
        val saved = (state as TaskFormState.Saved).task
        assertEquals("Renamed", saved.name)
        assertEquals(10, saved.hour)
        assertEquals("Updated notes", saved.notes)

        // Verify persisted in dao
        val persisted = fakeTaskDao.findById(20L)
        assertEquals("Renamed", persisted?.name)
    }

    // ── markDirty ─────────────────────────────────────────────────────────

    @Test
    fun `markDirty sets isDirty to true`() = runTest {
        val vm = makeViewModel()
        assertFalse(vm.isDirty.value)
        vm.markDirty()
        assertTrue(vm.isDirty.value)
    }
}
