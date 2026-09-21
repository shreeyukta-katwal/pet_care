package com.petcare.app.ui.welcome

import com.petcare.app.data.db.UserDao
import com.petcare.app.data.db.UserEntity
import com.petcare.app.data.repository.UserRepository
import com.petcare.app.data.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory test double for [SessionManager].
 */
class FakeSessionManager(private var userId: Long = SessionManager.NO_USER) : SessionManager {
    var clearCalled: Boolean = false
        private set

    override fun saveSession(userId: Long, persistent: Boolean) {
        this.userId = userId
    }

    override fun getUserId(): Long = userId

    override fun clear() {
        userId = SessionManager.NO_USER
        clearCalled = true
    }
}

/**
 * In-memory test double for [UserDao].
 */
class FakeUserDao : UserDao {
    private val users = mutableMapOf<Long, UserEntity>()

    fun addUser(user: UserEntity) {
        users[user.id] = user
    }

    override suspend fun insert(user: UserEntity): Long {
        val nextId = (users.keys.maxOrNull() ?: 0L) + 1L
        users[nextId] = user.copy(id = nextId)
        return nextId
    }

    override suspend fun insertWithId(user: UserEntity): Long {
        users[user.id] = user
        return user.id
    }

    override suspend fun findByEmail(email: String): UserEntity? =
        users.values.find { it.email == email }

    override suspend fun findById(id: Long): UserEntity? =
        users[id]

    override suspend fun update(user: UserEntity) {
        users[user.id] = user
    }

    override suspend fun deleteById(id: Long) {
        users.remove(id)
    }
}

/**
 * Unit tests for [WelcomeViewModel].
 *
 * Validates the auto-skip and session verification logic under different conditions:
 * - Fresh install / no session -> displays welcome UI.
 * - Stored session with valid database user -> automatically navigates to pet list.
 * - Stored session with missing/deleted database user -> clears session and displays welcome UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeDao: FakeUserDao
    private lateinit var userRepository: UserRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeUserDao()
        userRepository = UserRepository(fakeDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * When no user session exists, the ViewModel should transition to [WelcomeUiState.ShowWelcome].
     */
    @Test
    fun noSession_showsWelcome() = runTest(testDispatcher) {
        val sessionManager = FakeSessionManager(userId = SessionManager.NO_USER)
        val viewModel = WelcomeViewModel(sessionManager, userRepository)

        advanceUntilIdle()

        assertEquals(WelcomeUiState.ShowWelcome, viewModel.uiState.value)
    }

    /**
     * When a valid user session exists and the user record is present in the database,
     * the ViewModel should transition to [WelcomeUiState.NavigateToPetList].
     */
    @Test
    fun validSession_navigatesToPetList() = runTest(testDispatcher) {
        val user = UserEntity(id = 42L, email = "owner@example.com", passwordHash = "hash", salt = "salt")
        fakeDao.addUser(user)

        val sessionManager = FakeSessionManager(userId = 42L)
        val viewModel = WelcomeViewModel(sessionManager, userRepository)

        advanceUntilIdle()

        assertEquals(WelcomeUiState.NavigateToPetList, viewModel.uiState.value)
    }

    /**
     * When a session points to a non-existent user, the ViewModel should clear the stale session
     * and transition to [WelcomeUiState.ShowWelcome].
     */
    @Test
    fun staleSession_clearsSessionAndShowsWelcome() = runTest(testDispatcher) {
        // No user in fakeDao for ID 99L
        val sessionManager = FakeSessionManager(userId = 99L)
        val viewModel = WelcomeViewModel(sessionManager, userRepository)

        advanceUntilIdle()

        assertTrue("Stale session should have been cleared", sessionManager.clearCalled)
        assertEquals(WelcomeUiState.ShowWelcome, viewModel.uiState.value)
    }
}
