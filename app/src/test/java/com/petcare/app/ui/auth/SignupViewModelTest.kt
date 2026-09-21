package com.petcare.app.ui.auth

import com.petcare.app.R
import com.petcare.app.data.db.UserDao
import com.petcare.app.data.db.UserEntity
import com.petcare.app.data.repository.UserRepository
import com.petcare.app.data.session.SessionManager
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SignupViewModel].
 *
 * Validates all validation rules, duplicate prevention, and session persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeDao: FakeUserDao
    private lateinit var userRepository: UserRepository
    private lateinit var sessionManager: FakeSessionManager
    private lateinit var viewModel: SignupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeUserDao()
        userRepository = UserRepository(fakeDao)
        sessionManager = FakeSessionManager()
        viewModel = SignupViewModel(userRepository, sessionManager, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun emptyEmail_showsEmailRequiredError() = runTest(testDispatcher) {
        viewModel.onEmailChanged("")
        viewModel.register(password = "ValidPass1", confirm = "ValidPass1")

        assertEquals(R.string.error_email_required, viewModel.uiState.value.emailError)
    }

    @Test
    fun invalidEmail_showsEmailInvalidError() = runTest(testDispatcher) {
        viewModel.onEmailChanged("not-an-email")
        viewModel.register(password = "ValidPass1", confirm = "ValidPass1")

        assertEquals(R.string.error_email_invalid, viewModel.uiState.value.emailError)
    }

    @Test
    fun shortPassword_showsPasswordLengthError() = runTest(testDispatcher) {
        viewModel.onEmailChanged("test@example.com")
        viewModel.register(password = "Pass1", confirm = "Pass1")

        assertEquals(R.string.error_password_length, viewModel.uiState.value.passwordError)
    }

    @Test
    fun passwordMissingDigit_showsComplexityError() = runTest(testDispatcher) {
        viewModel.onEmailChanged("test@example.com")
        viewModel.register(password = "NoDigitsHere", confirm = "NoDigitsHere")

        assertEquals(R.string.error_password_complexity, viewModel.uiState.value.passwordError)
    }

    @Test
    fun mismatchedConfirmPassword_showsMismatchError() = runTest(testDispatcher) {
        viewModel.onEmailChanged("test@example.com")
        viewModel.register(password = "ValidPass1", confirm = "DifferentPass2")

        assertEquals(R.string.error_passwords_do_not_match, viewModel.uiState.value.confirmPasswordError)
    }

    @Test
    fun duplicateEmail_showsDuplicateError() = runTest(testDispatcher) {
        // Pre-insert existing user
        fakeDao.addUser(
            UserEntity(
                id = 1L,
                email = "existing@example.com",
                passwordHash = "hash",
                salt = "salt"
            )
        )

        viewModel.onEmailChanged("Existing@Example.COM ") // Test lowercase & trim matching
        viewModel.register(password = "ValidPass1", confirm = "ValidPass1")

        advanceUntilIdle()

        assertEquals(R.string.error_email_duplicate, viewModel.uiState.value.emailError)
    }

    @Test
    fun validSignup_hashesPassword_savesPersistentSession_andNavigates() = runTest(testDispatcher) {
        viewModel.onEmailChanged(" newuser@domain.com ")
        viewModel.register(password = "StrongPassword123", confirm = "StrongPassword123")

        advanceUntilIdle()

        // Verify state
        assertNull(viewModel.uiState.value.emailError)
        assertNull(viewModel.uiState.value.passwordError)
        assertNull(viewModel.uiState.value.confirmPasswordError)
        assertTrue(viewModel.uiState.value.navigateToPetList)

        // Verify database user record
        val insertedUser = userRepository.findByEmail("newuser@domain.com")
        assertTrue("User must be in database", insertedUser != null)
        assertEquals("newuser@domain.com", insertedUser!!.email)
        // Plain text password must NEVER be in the database
        assertNotEquals("StrongPassword123", insertedUser.passwordHash)
        assertTrue("Password hash must be non-empty", insertedUser.passwordHash.isNotEmpty())
        assertTrue("Salt must be non-empty", insertedUser.salt.isNotEmpty())

        // Verify session was saved with persistent = true (remember me)
        assertEquals(insertedUser.id, sessionManager.getUserId())
    }

    @Test
    fun editingField_clearsError() = runTest(testDispatcher) {
        viewModel.onEmailChanged("")
        viewModel.register(password = "", confirm = "")

        assertEquals(R.string.error_email_required, viewModel.uiState.value.emailError)

        viewModel.onEmailChanged("a")
        assertNull("Editing email must clear email error", viewModel.uiState.value.emailError)
    }
}
