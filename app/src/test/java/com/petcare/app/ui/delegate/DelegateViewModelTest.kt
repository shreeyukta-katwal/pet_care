package com.petcare.app.ui.delegate

import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.db.TaskCategory
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.db.TaskFrequency
import com.petcare.app.data.db.UserEntity
import com.petcare.app.data.repository.PetRepository
import com.petcare.app.data.repository.TaskRepository
import com.petcare.app.data.repository.UserRepository
import com.petcare.app.ui.checklist.ChecklistFilterMode
import com.petcare.app.ui.pet.FakePetDao
import com.petcare.app.ui.pet.FakeTaskDao
import com.petcare.app.ui.welcome.FakeSessionManager
import com.petcare.app.ui.welcome.FakeUserDao
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [DelegateViewModel].
 * Validates reactive state assembly, contact selection, phone number validation,
 * task toggling, filter modes, manual edit mode, and reset to default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DelegateViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakePetDao: FakePetDao
    private lateinit var fakeTaskDao: FakeTaskDao
    private lateinit var fakeUserDao: FakeUserDao
    private lateinit var fakeSessionManager: FakeSessionManager

    private lateinit var petRepository: PetRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var userRepository: UserRepository

    private val petId = 1L
    private val userId = 42L

    private val samplePet = PetEntity(
        id = petId,
        userId = userId,
        name = "Charlie",
        species = "Dog",
        breed = "Beagle",
        ageYears = 4f,
        weightKg = 12f,
        diet = "Kibble",
        allergies = "None",
        vaccinations = "Up to date",
        favouriteToys = "Chew rope",
        notes = "Loves walks"
    )

    private val sampleUser = UserEntity(
        id = userId,
        email = "emily@example.com",
        passwordHash = "hash",
        salt = "salt"
    )

    private val task1 = TaskEntity(
        id = 101L,
        petId = petId,
        name = "Morning meal",
        category = TaskCategory.FEEDING,
        frequency = TaskFrequency.DAILY,
        hour = 8,
        minute = 0,
        supplies = "1 scoop",
        reminderEnabled = false
    )

    private val task2 = TaskEntity(
        id = 102L,
        petId = petId,
        name = "Evening walk",
        category = TaskCategory.EXERCISE,
        frequency = TaskFrequency.DAILY,
        hour = 18,
        minute = 30,
        supplies = "Leash",
        reminderEnabled = false
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        fakePetDao = FakePetDao()
        fakeTaskDao = FakeTaskDao()
        fakeUserDao = FakeUserDao()
        fakeSessionManager = FakeSessionManager(userId)

        fakePetDao.pets[petId] = samplePet
        fakeUserDao.addUser(sampleUser)
        kotlinx.coroutines.runBlocking {
            fakeTaskDao.insertWithId(task1)
            fakeTaskDao.insertWithId(task2)
        }

        petRepository = PetRepository(
            database = null,
            petDao = fakePetDao,
            taskDao = fakeTaskDao
        )
        taskRepository = TaskRepository(fakeTaskDao)
        userRepository = UserRepository(fakeUserDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): DelegateViewModel {
        return DelegateViewModel(
            petId = petId,
            petRepository = petRepository,
            taskRepository = taskRepository,
            userRepository = userRepository,
            sessionManager = fakeSessionManager
        )
    }

    @Test
    fun `initial state loads pet, tasks, and derives sender name from email`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Charlie", state.pet?.name)
        assertEquals("Emily", state.senderName)
        assertEquals(2, state.allTasks.size)
        assertEquals(2, state.displayTasks.size)
        assertEquals(setOf(101L, 102L), state.selectedTaskIds)
        assertTrue(state.previewMessage.contains("Care plan for Charlie (Beagle)"))
        assertTrue(state.previewMessage.contains("Feeding: 08:00 Morning meal (1 scoop)"))
        assertTrue(state.previewMessage.contains("Exercise: 18:30 Evening walk (Leash)"))
        assertTrue(state.previewMessage.contains("Sent by Emily via PetCare"))
    }

    @Test
    fun `setRecipientPhone updates phone and validation flag`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isPhoneValid)
        assertFalse(viewModel.uiState.value.canSend)

        viewModel.setRecipientPhone("+44 7911 123456")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("+44 7911 123456", state.recipientPhone)
        assertTrue(state.isPhoneValid)
        assertTrue(state.canSend)
    }

    @Test
    fun `setContact updates contact name and phone number`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setContact("Daniel Sitter", "+1 555 123 4567")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Daniel Sitter", state.contactName)
        assertEquals("+1 555 123 4567", state.recipientPhone)
        assertTrue(state.isPhoneValid)
    }

    @Test
    fun `toggleTaskSelection updates preview message reactively`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.previewMessage.contains("Evening walk"))

        // Uncheck evening walk
        viewModel.toggleTaskSelection(102L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf(101L), state.selectedTaskIds)
        assertTrue(state.previewMessage.contains("Morning meal"))
        assertFalse(state.previewMessage.contains("Evening walk"))
    }

    @Test
    fun `setExtraInstructions adds notes section to preview`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setExtraInstructions("Give treats after 5pm.")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Give treats after 5pm.", state.extraInstructions)
        assertTrue(state.previewMessage.contains("Notes: Give treats after 5pm."))
    }

    @Test
    fun `manual edit flags isManualEdit and reset restores generated message`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isManualEdit)

        // User types custom message
        val customText = "Custom quick message: please feed Charlie at 8am."
        viewModel.setManualPreview(customText)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isManualEdit)
        assertEquals(customText, viewModel.uiState.value.previewMessage)

        // Reset
        viewModel.resetToGeneratedMessage()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isManualEdit)
        assertTrue(viewModel.uiState.value.previewMessage.contains("Care plan for Charlie"))
    }

    @Test
    fun `clearTaskSelection and selectAllTasks operate correctly`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.selectedTaskIds.size)

        viewModel.clearTaskSelection()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.selectedTaskIds.isEmpty())

        viewModel.selectAllTasks()
        advanceUntilIdle()
        assertEquals(setOf(101L, 102L), viewModel.uiState.value.selectedTaskIds)
    }
}
