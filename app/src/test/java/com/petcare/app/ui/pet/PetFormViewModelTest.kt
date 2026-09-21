package com.petcare.app.ui.pet

import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.repository.PetRepository
import com.petcare.app.data.session.SessionManager
import com.petcare.app.ui.welcome.FakeSessionManager
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PetFormViewModel].
 *
 * Validates:
 * - Empty name validation error.
 * - Empty species validation error.
 * - Age out-of-bounds validation error.
 * - Weight out-of-bounds validation error.
 * - Create mode inserts new pet into repository.
 * - Edit mode loads existing pet and updates it on save.
 * - Dirty state transitions when text or photo changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PetFormViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePetDao: FakePetDao
    private lateinit var fakeTaskDao: FakeTaskDao
    private lateinit var petRepository: PetRepository
    private lateinit var sessionManager: SessionManager

    private val existingPet = PetEntity(
        id = 42L,
        userId = 1L,
        name = "Luna",
        species = "Dog",
        breed = "Husky",
        ageYears = 3f,
        weightKg = 22f,
        diet = "Raw meat",
        allergies = "Chicken",
        vaccinations = "Up to date",
        favouriteToys = "Rope",
        notes = "Energetic",
        photoUri = null
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
        sessionManager = FakeSessionManager(userId = 1L)
        fakePetDao.setPets(listOf(existingPet))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `save with blank name triggers ValidationError for NAME`() = runTest {
        val viewModel = PetFormViewModel(
            petId = 0L,
            petRepository = petRepository,
            sessionManager = sessionManager,
            appContext = null
        )

        viewModel.save(
            name = "   ",
            species = "Dog",
            breed = "Pug",
            ageText = "2",
            weightText = "8",
            diet = "",
            allergies = "",
            vaccinations = "",
            favouriteToys = "",
            notes = ""
        )

        val state = viewModel.state.value
        assertTrue(state is PetFormState.ValidationError)
        assertEquals(
            PetFormState.ValidationError.Field.NAME,
            (state as PetFormState.ValidationError).field
        )
    }

    @Test
    fun `save with blank species triggers ValidationError for SPECIES`() = runTest {
        val viewModel = PetFormViewModel(
            petId = 0L,
            petRepository = petRepository,
            sessionManager = sessionManager,
            appContext = null
        )

        viewModel.save(
            name = "Barnaby",
            species = "   ",
            breed = "",
            ageText = "1",
            weightText = "2",
            diet = "",
            allergies = "",
            vaccinations = "",
            favouriteToys = "",
            notes = ""
        )

        val state = viewModel.state.value
        assertTrue(state is PetFormState.ValidationError)
        assertEquals(
            PetFormState.ValidationError.Field.SPECIES,
            (state as PetFormState.ValidationError).field
        )
    }

    @Test
    fun `save with invalid age triggers ValidationError for AGE`() = runTest {
        val viewModel = PetFormViewModel(
            petId = 0L,
            petRepository = petRepository,
            sessionManager = sessionManager,
            appContext = null
        )

        viewModel.save(
            name = "Barnaby",
            species = "Turtle",
            breed = "",
            ageText = "55",
            weightText = "2",
            diet = "",
            allergies = "",
            vaccinations = "",
            favouriteToys = "",
            notes = ""
        )

        val state = viewModel.state.value
        assertTrue(state is PetFormState.ValidationError)
        assertEquals(
            PetFormState.ValidationError.Field.AGE,
            (state as PetFormState.ValidationError).field
        )
    }

    @Test
    fun `save with invalid weight triggers ValidationError for WEIGHT`() = runTest {
        val viewModel = PetFormViewModel(
            petId = 0L,
            petRepository = petRepository,
            sessionManager = sessionManager,
            appContext = null
        )

        viewModel.save(
            name = "Barnaby",
            species = "Dog",
            breed = "",
            ageText = "3",
            weightText = "200",
            diet = "",
            allergies = "",
            vaccinations = "",
            favouriteToys = "",
            notes = ""
        )

        val state = viewModel.state.value
        assertTrue(state is PetFormState.ValidationError)
        assertEquals(
            PetFormState.ValidationError.Field.WEIGHT,
            (state as PetFormState.ValidationError).field
        )
    }

    @Test
    fun `create mode saves valid pet and enters Saved state`() = runTest {
        val viewModel = PetFormViewModel(
            petId = 0L,
            petRepository = petRepository,
            sessionManager = sessionManager,
            appContext = null
        )

        viewModel.save(
            name = "Rocky",
            species = "Dog",
            breed = "Boxer",
            ageText = "4",
            weightText = "28",
            diet = "Kibble",
            allergies = "None",
            vaccinations = "Rabies, DHPP",
            favouriteToys = "Tennis ball",
            notes = "Loves fetch"
        )
        advanceUntilIdle()

        assertEquals(PetFormState.Saved, viewModel.state.value)
        assertFalse(viewModel.isDirty.value)

        val savedPet = fakePetDao.pets.values.find { it.name == "Rocky" }
        assertNotNull(savedPet)
        assertEquals("Dog", savedPet?.species)
        assertEquals("Boxer", savedPet?.breed)
        assertEquals(4f, savedPet?.ageYears)
        assertEquals(28f, savedPet?.weightKg)
    }

    @Test
    fun `edit mode loads existing pet and emits PetLoaded state`() = runTest {
        val viewModel = PetFormViewModel(
            petId = 42L,
            petRepository = petRepository,
            sessionManager = sessionManager,
            appContext = null
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is PetFormState.PetLoaded)
        val loaded = (state as PetFormState.PetLoaded).pet
        assertEquals("Luna", loaded.name)
        assertEquals(42L, loaded.id)
    }

    @Test
    fun `edit mode updates pet and preserves pet id on save`() = runTest {
        val viewModel = PetFormViewModel(
            petId = 42L,
            petRepository = petRepository,
            sessionManager = sessionManager,
            appContext = null
        )
        advanceUntilIdle()

        viewModel.save(
            name = "Luna Updated",
            species = "Dog",
            breed = "Siberian Husky",
            ageText = "4",
            weightText = "24",
            diet = "Raw food",
            allergies = "None",
            vaccinations = "All",
            favouriteToys = "Chew rope",
            notes = "Gentle giant"
        )
        advanceUntilIdle()

        assertEquals(PetFormState.Saved, viewModel.state.value)

        val updated = fakePetDao.findById(42L)
        assertNotNull(updated)
        assertEquals("Luna Updated", updated?.name)
        assertEquals("Siberian Husky", updated?.breed)
        assertEquals(4f, updated?.ageYears)
        assertEquals(24f, updated?.weightKg)
    }

    @Test
    fun `markDirty transitions isDirty to true`() = runTest {
        val viewModel = PetFormViewModel(
            petId = 0L,
            petRepository = petRepository,
            sessionManager = sessionManager,
            appContext = null
        )

        assertFalse(viewModel.isDirty.value)
        viewModel.markDirty()
        assertTrue(viewModel.isDirty.value)
    }
}
