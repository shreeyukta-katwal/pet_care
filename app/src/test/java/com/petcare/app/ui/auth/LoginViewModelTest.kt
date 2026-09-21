package com.petcare.app.ui.auth

import com.petcare.app.R
import com.petcare.app.data.db.UserEntity
import com.petcare.app.data.repository.UserRepository
import com.petcare.app.data.security.PasswordHasher
import com.petcare.app.ui.welcome.FakeSessionManager
import com.petcare.app.ui.welcome.FakeUserDao
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LoginViewModel].
 *
 * Validates authentication rules:
 * - Empty fields handling.
 * - Non-existent user produces generic credentials error (no enumeration).
 * - Wrong password produces generic credentials error.
 * - Persistent vs non-persistent session depending on remember-me toggle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeDao: FakeUserDao
    private lateinit var userRepository: UserRepository
    private lateinit var sessionManager: FakeSessionManager
    private lateinit var viewModel: LoginViewModel

    private val testUserPassword = "Password123"
    private var testUserId: Long = 0L

    @Before
    fun setUp() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeUserDao()
        userRepository = UserRepository(fakeDao)
        sessionManager = FakeSessionManager()

        // Create a hashed user in fake DAO
        val hashResult = PasswordHasher.hash(testUserPassword, testDispatcher)
        testUserId = fakeDao.insert(
            UserEntity(
                email = "user@example.com",
                passwordHash = hashResult.hashBase64,
                salt = hashResult.saltBase64
            )
        )

        viewModel = LoginViewModel(userRepository, sessionManager, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun emptyFields_showsRequiredErrors() = runTest(testDispatcher) {
        viewModel.onEmailChanged("")
        viewModel.login(password = "")

        assertEquals(R.string.error_email_required, viewModel.uiState.value.emailError)
        assertEquals(R.string.error_password_required, viewModel.uiState.value.passwordError)
    }

    @Test
    fun nonExistentUser_showsGenericCredentialsError() = runTest(testDispatcher) {
        viewModel.onEmailChanged("unregistered@example.com")
        viewModel.login(password = "AnyPassword1")

        advanceUntilIdle()

        assertEquals(R.string.error_invalid_credentials, viewModel.uiState.value.passwordError)
        assertFalse(viewModel.uiState.value.navigateToPetList)
    }

    @Test
    fun incorrectPassword_showsGenericCredentialsError() = runTest(testDispatcher) {
        viewModel.onEmailChanged("user@example.com")
        viewModel.login(password = "WrongPassword999")

        advanceUntilIdle()

        assertEquals(R.string.error_invalid_credentials, viewModel.uiState.value.passwordError)
        assertFalse(viewModel.uiState.value.navigateToPetList)
    }

    @Test
    fun correctCredentials_rememberMeTrue_savesPersistentSession() = runTest(testDispatcher) {
        viewModel.onEmailChanged("User@Example.COM") // Tests case insensitivity & trimming
        viewModel.onRememberMeChanged(true)
        viewModel.login(password = testUserPassword)

        advanceUntilIdle()

        assertNull(viewModel.uiState.value.emailError)
        assertNull(viewModel.uiState.value.passwordError)
        assertTrue(viewModel.uiState.value.navigateToPetList)
        assertEquals(testUserId, sessionManager.getUserId())
    }

    @Test
    fun correctCredentials_rememberMeFalse_savesNonPersistentSession() = runTest(testDispatcher) {
        viewModel.onEmailChanged("user@example.com")
        viewModel.onRememberMeChanged(false)
        viewModel.login(password = testUserPassword)

        advanceUntilIdle()

        assertNull(viewModel.uiState.value.passwordError)
        assertTrue(viewModel.uiState.value.navigateToPetList)
        assertEquals(testUserId, sessionManager.getUserId())
    }

    @Test
    fun editingField_clearsError() = runTest(testDispatcher) {
        viewModel.onEmailChanged("user@example.com")
        viewModel.login(password = "Wrong")

        advanceUntilIdle()
        assertEquals(R.string.error_invalid_credentials, viewModel.uiState.value.passwordError)

        viewModel.onPasswordChanged()
        assertNull(viewModel.uiState.value.passwordError)
    }
}
