package com.petcare.app.ui.pet

import com.petcare.app.data.db.PetDao
import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.db.TaskCategory
import com.petcare.app.data.db.TaskDao
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.db.TaskFrequency
import com.petcare.app.data.repository.PetRepository
import com.petcare.app.data.repository.PetSnapshot
import com.petcare.app.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory test double for [PetDao].
 */
class FakePetDao : PetDao {
    val pets = mutableMapOf<Long, PetEntity>()
    private val petsFlow = MutableStateFlow<List<PetEntity>>(emptyList())
    private var nextId = 1L

    fun setPets(petList: List<PetEntity>) {
        pets.clear()
        petList.forEach { pets[it.id] = it }
        petsFlow.value = pets.values.toList()
    }

    override suspend fun insert(pet: PetEntity): Long {
        val id = nextId++
        val saved = pet.copy(id = id)
        pets[id] = saved
        petsFlow.value = pets.values.toList()
        return id
    }

    override suspend fun insertWithId(pet: PetEntity): Long {
        pets[pet.id] = pet
        petsFlow.value = pets.values.toList()
        return pet.id
    }

    override suspend fun update(pet: PetEntity) {
        pets[pet.id] = pet
        petsFlow.value = pets.values.toList()
    }

    override suspend fun deleteById(petId: Long) {
        pets.remove(petId)
        petsFlow.value = pets.values.toList()
    }

    override fun getAllForUser(userId: Long): Flow<List<PetEntity>> = petsFlow

    override suspend fun findById(petId: Long): PetEntity? = pets[petId]
}

/**
 * In-memory test double for [TaskDao].
 */
class FakeTaskDao : TaskDao {
    val tasks = mutableMapOf<Long, TaskEntity>()
    private val tasksFlow = MutableStateFlow<List<TaskEntity>>(emptyList())

    override suspend fun insert(task: TaskEntity): Long {
        tasks[task.id] = task
        tasksFlow.value = tasks.values.toList()
        return task.id
    }

    override suspend fun insertWithId(task: TaskEntity): Long {
        tasks[task.id] = task
        tasksFlow.value = tasks.values.toList()
        return task.id
    }

    override suspend fun update(task: TaskEntity) {
        tasks[task.id] = task
        tasksFlow.value = tasks.values.toList()
    }

    override suspend fun deleteById(taskId: Long) {
        tasks.remove(taskId)
        tasksFlow.value = tasks.values.toList()
    }

    override fun getAllTasks(): Flow<List<TaskEntity>> = tasksFlow

    override suspend fun getAllEnabledTasks(): List<TaskEntity> =
        tasks.values.filter { it.reminderEnabled }

    override fun getAllForPet(petId: Long): Flow<List<TaskEntity>> = tasksFlow

    override suspend fun getAllForPetOnce(petId: Long): List<TaskEntity> =
        tasks.values.filter { it.petId == petId }

    override suspend fun findById(taskId: Long): TaskEntity? = tasks[taskId]

    override suspend fun updateLastCompletedDate(taskId: Long, completedDate: String?) {
        tasks[taskId]?.let { tasks[taskId] = it.copy(lastCompletedDate = completedDate) }
        tasksFlow.value = tasks.values.toList()
    }

    override suspend fun resetChecklistForPet(petId: Long) {
        tasks.filter { it.value.petId == petId }.forEach { (id, task) ->
            tasks[id] = task.copy(lastCompletedDate = null)
        }
    }
}

/**
 * Fake implementation of [ReminderScheduler] for recording scheduled tasks.
 */
class FakeReminderScheduler : ReminderScheduler {
    val scheduledTasks = mutableListOf<TaskEntity>()
    val cancelledTaskIds = mutableListOf<Long>()
    val cancelledPetIds = mutableListOf<Long>()

    override fun schedule(task: TaskEntity) {
        scheduledTasks.add(task)
    }

    override fun cancel(taskId: Long) {
        cancelledTaskIds.add(taskId)
    }

    override fun cancelForPet(petId: Long) {
        cancelledPetIds.add(petId)
    }
}

/**
 * Unit tests for [PetListViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PetListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePetDao: FakePetDao
    private lateinit var fakeTaskDao: FakeTaskDao
    private lateinit var petRepository: PetRepository
    private lateinit var fakeScheduler: FakeReminderScheduler
    private lateinit var viewModel: PetListViewModel

    private val samplePet = PetEntity(
        id = 1L,
        userId = 100L,
        name = "Milo",
        species = "Cat",
        breed = "Tabby",
        ageYears = 2f,
        weightKg = 4.5f,
        diet = "Dry kibble",
        allergies = "None",
        vaccinations = "Rabies",
        favouriteToys = "Laser pointer",
        notes = "Friendly"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakePetDao = FakePetDao()
        fakeTaskDao = FakeTaskDao()
        petRepository = PetRepository(
            database = null,
            petDao = fakePetDao,
            taskDao = fakeTaskDao
        )
        fakeScheduler = FakeReminderScheduler()
        fakePetDao.setPets(listOf(samplePet))

        viewModel = PetListViewModel(
            userId = 100L,
            petRepository = petRepository,
            reminderScheduler = fakeScheduler
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `deletePet emits PetDeleted event with snapshot and petName`() = runTest {
        viewModel.deletePet(samplePet)
        advanceUntilIdle()

        val event = viewModel.event.value
        assertTrue(event is PetListEvent.PetDeleted)
        val deletedEvent = event as PetListEvent.PetDeleted
        assertEquals("Milo", deletedEvent.petName)
        assertEquals(1L, deletedEvent.snapshot.pet.id)
    }

    @Test
    fun `restorePet calls repository restore and reschedules reminders`() = runTest {
        val task = TaskEntity(
            id = 10L,
            petId = 1L,
            name = "Feed Milo",
            category = TaskCategory.FEEDING,
            frequency = TaskFrequency.DAILY,
            hour = 8,
            minute = 0,
            reminderEnabled = true
        )
        val snapshot = PetSnapshot(pet = samplePet, tasks = listOf(task))

        viewModel.restorePet(snapshot)
        advanceUntilIdle()

        assertEquals(samplePet, fakePetDao.findById(1L))
        assertEquals(1, fakeScheduler.scheduledTasks.size)
        assertEquals(10L, fakeScheduler.scheduledTasks[0].id)
    }

    @Test
    fun `clearEvent resets event to null`() = runTest {
        viewModel.deletePet(samplePet)
        advanceUntilIdle()

        assertNotNull(viewModel.event.value)
        viewModel.clearEvent()
        assertEquals(null, viewModel.event.value)
    }

    @Test
    fun `batchDeletePets deletes all selected pets and emits BatchPetsDeleted event`() = runTest {
        val pet2 = samplePet.copy(id = 2L, name = "Bella")
        fakePetDao.pets[2L] = pet2

        viewModel.batchDeletePets(listOf(samplePet, pet2))
        advanceUntilIdle()

        assertEquals(null, fakePetDao.findById(1L))
        assertEquals(null, fakePetDao.findById(2L))

        val event = viewModel.event.value
        assertTrue(event is PetListEvent.BatchPetsDeleted)
        val batchEvent = event as PetListEvent.BatchPetsDeleted
        assertEquals(2, batchEvent.snapshots.size)

        // Restore
        viewModel.restoreBatchPets(batchEvent.snapshots)
        advanceUntilIdle()

        assertEquals("Milo", fakePetDao.findById(1L)?.name)
        assertEquals("Bella", fakePetDao.findById(2L)?.name)
    }
}
