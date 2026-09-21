package com.petcare.app.ui.checklist

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
import kotlinx.coroutines.launch
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
import java.time.LocalDate

/**
 * Unit tests for [PetChecklistViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PetChecklistViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakePetDao: FakePetDao
    private lateinit var fakeTaskDao: FakeTaskDao
    private lateinit var fakeScheduler: FakeReminderScheduler
    private lateinit var petRepository: PetRepository
    private lateinit var taskRepository: TaskRepository

    private val userId = 100L
    private val petId = 1L

    private val samplePet = PetEntity(
        id = petId,
        userId = userId,
        name = "Buddy",
        species = "Dog",
        breed = "Golden Retriever",
        ageYears = 3f,
        weightKg = 25f,
        diet = "Dry food",
        allergies = "None",
        vaccinations = "Rabies",
        favouriteToys = "Ball",
        notes = "Energetic"
    )

    private val task1 = TaskEntity(
        id = 10L,
        petId = petId,
        name = "Breakfast",
        category = TaskCategory.FEEDING,
        frequency = TaskFrequency.DAILY,
        hour = 8,
        minute = 0,
        reminderEnabled = true
    )

    private val task2 = TaskEntity(
        id = 20L,
        petId = petId,
        name = "Evening Walk",
        category = TaskCategory.EXERCISE,
        frequency = TaskFrequency.DAILY,
        hour = 17,
        minute = 30,
        reminderEnabled = true
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

    private fun createViewModel() = PetChecklistViewModel(
        petId = petId,
        userId = userId,
        petRepository = petRepository,
        taskRepository = taskRepository,
        reminderScheduler = fakeScheduler
    )

    @Test
    fun `initial state loads pet profile and tasks categorized`() = runTest {
        fakeTaskDao.insert(task1)
        fakeTaskDao.insert(task2)

        val vm = createViewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Buddy", state.pet?.name)
        assertEquals(2, state.totalCount)
        assertEquals(0, state.completedCount)
        assertFalse(state.isAllDone)

        // 2 headers + 2 task items = 4 total items
        assertEquals(4, state.items.size)
        assertTrue(state.items[0] is ChecklistItem.Header)
        assertEquals(TaskCategory.FEEDING, (state.items[0] as ChecklistItem.Header).category)
        assertTrue(state.items[1] is ChecklistItem.Task)
        assertEquals("Breakfast", ((state.items[1] as ChecklistItem.Task).task).name)
    }

    @Test
    fun `toggleTaskDone updates lastCompletedDate in database and calculates progress`() = runTest {
        fakeTaskDao.insert(task1)
        fakeTaskDao.insert(task2)

        val vm = createViewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        // Check task1 as done
        vm.toggleTaskDone(task1, isChecked = true)
        advanceUntilIdle()

        val todayStr = LocalDate.now().toString()
        val updatedTask1 = fakeTaskDao.findById(10L)
        assertEquals(todayStr, updatedTask1?.lastCompletedDate)

        val state = vm.uiState.value
        assertEquals(1, state.completedCount)
        assertEquals(2, state.totalCount)
        assertEquals(50, state.progressPercentage)
        assertFalse(state.isAllDone)

        // Check task2 as done -> 100% all done
        vm.toggleTaskDone(task2, isChecked = true)
        advanceUntilIdle()

        val finalState = vm.uiState.value
        assertEquals(2, finalState.completedCount)
        assertEquals(100, finalState.progressPercentage)
        assertTrue(finalState.isAllDone)
    }

    @Test
    fun `toggleTaskDone unchecking resets lastCompletedDate to null`() = runTest {
        val doneTask = task1.copy(lastCompletedDate = LocalDate.now().toString())
        fakeTaskDao.insert(doneTask)

        val vm = createViewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleTaskDone(doneTask, isChecked = false)
        advanceUntilIdle()

        val updated = fakeTaskDao.findById(10L)
        assertEquals(null, updated?.lastCompletedDate)

        val state = vm.uiState.value
        assertEquals(0, state.completedCount)
    }

    @Test
    fun `deleteTask removes task and cancels reminder`() = runTest {
        fakeTaskDao.insert(task1)

        val vm = createViewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.deleteTask(task1)
        advanceUntilIdle()

        assertEquals(null, fakeTaskDao.findById(10L))
        assertEquals(1, fakeScheduler.cancelledTaskIds.size)
        assertEquals(10L, fakeScheduler.cancelledTaskIds[0])

        val event = vm.event.value
        assertTrue(event is ChecklistEvent.TaskDeleted)
        assertEquals("Breakfast", (event as ChecklistEvent.TaskDeleted).taskName)
    }

    @Test
    fun `restoreTask re-inserts task and reschedules reminder`() = runTest {
        fakeTaskDao.insert(task1)
        val vm = createViewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.deleteTask(task1)
        advanceUntilIdle()

        val deletedEvent = vm.event.value as ChecklistEvent.TaskDeleted
        vm.restoreTask(deletedEvent.snapshot)
        advanceUntilIdle()

        val restored = fakeTaskDao.findById(10L)
        assertEquals("Breakfast", restored?.name)
        assertEquals(1, fakeScheduler.scheduledTasks.size)
    }

    @Test
    fun `filterMode toggling between TODAY and ALL_ROUTINES works`() = runTest {
        fakeTaskDao.insert(task1)

        val vm = createViewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(ChecklistFilterMode.TODAY, vm.uiState.value.filterMode)

        vm.setFilterMode(ChecklistFilterMode.ALL_ROUTINES)
        advanceUntilIdle()

        assertEquals(ChecklistFilterMode.ALL_ROUTINES, vm.uiState.value.filterMode)
    }

    @Test
    fun `resetTodayChecklist clears completed dates and emits ChecklistReset event with backup`() = runTest {
        val completedTask = task1.copy(lastCompletedDate = LocalDate.now().toString())
        fakeTaskDao.insert(completedTask)

        val vm = createViewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.completedCount)

        vm.resetTodayChecklist()
        advanceUntilIdle()

        val event = vm.event.value
        assertTrue(event is ChecklistEvent.ChecklistReset)
        val resetEvent = event as ChecklistEvent.ChecklistReset
        assertEquals(LocalDate.now().toString(), resetEvent.previousCompletions[task1.id])

        // Task in DAO is now undone
        val inDb = fakeTaskDao.findById(task1.id)
        assertEquals(null, inDb?.lastCompletedDate)

        // Restore using backup
        vm.restoreResetChecklist(resetEvent.previousCompletions)
        advanceUntilIdle()

        val restored = fakeTaskDao.findById(task1.id)
        assertEquals(LocalDate.now().toString(), restored?.lastCompletedDate)
    }

    @Test
    fun `batchDeleteTasks deletes all selected tasks and cancels reminders`() = runTest {
        fakeTaskDao.insert(task1)
        fakeTaskDao.insert(task2)

        val vm = createViewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.batchDeleteTasks(setOf(task1.id, task2.id))
        advanceUntilIdle()

        assertEquals(null, fakeTaskDao.findById(task1.id))
        assertEquals(null, fakeTaskDao.findById(task2.id))

        val event = vm.event.value
        assertTrue(event is ChecklistEvent.BatchTasksDeleted)
        val batchEvent = event as ChecklistEvent.BatchTasksDeleted
        assertEquals(2, batchEvent.snapshots.size)

        // Undo batch restore
        vm.restoreBatchTasks(batchEvent.snapshots)
        advanceUntilIdle()

        assertEquals("Breakfast", fakeTaskDao.findById(task1.id)?.name)
        assertEquals("Evening Walk", fakeTaskDao.findById(task2.id)?.name)
    }

    @Test
    fun `batchMarkDone completes all selected tasks for today`() = runTest {
        fakeTaskDao.insert(task1)
        fakeTaskDao.insert(task2)

        val vm = createViewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.batchMarkDone(setOf(task1.id, task2.id))
        advanceUntilIdle()

        val todayIso = LocalDate.now().toString()
        assertEquals(todayIso, fakeTaskDao.findById(task1.id)?.lastCompletedDate)
        assertEquals(todayIso, fakeTaskDao.findById(task2.id)?.lastCompletedDate)

        val event = vm.event.value
        assertTrue(event is ChecklistEvent.BatchTasksMarkedDone)
        assertEquals(2, (event as ChecklistEvent.BatchTasksMarkedDone).count)
    }
}

