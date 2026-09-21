package com.petcare.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.petcare.app.R
import com.petcare.app.data.db.UserEntity
import com.petcare.app.data.repository.UserRepository
import com.petcare.app.data.security.PasswordHasher
import com.petcare.app.data.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state representing the Signup screen.
 *
 * @property email                 The current entered email (survives rotation).
 * @property emailError            Resource ID of the email error to display, or null.
 * @property passwordError         Resource ID of the password error to display, or null.
 * @property confirmPasswordError  Resource ID of the confirm-password error, or null.
 * @property isLoading             True when hashing and creating account, disabling inputs.
 * @property navigateToPetList     One-time navigation event trigger on successful registration.
 */
data class SignupUiState(
    val email: String = "",
    val emailError: Int? = null,
    val passwordError: Int? = null,
    val confirmPasswordError: Int? = null,
    val isLoading: Boolean = false,
    val navigateToPetList: Boolean = false
)

/**
 * ViewModel for [SignupFragment].
 *
 * Enforces strict input validation:
 * - Email: required, trimmed, valid format.
 * - Password: minimum 8 characters, must contain at least one letter and one digit.
 * - Confirm password: required and must match password.
 * - Duplicate email: verified against [UserRepository.findByEmail].
 *
 * Password security:
 * - Passwords are NEVER stored in ViewModel state beyond the transient method parameters.
 * - Passwords are never logged or stored in plaintext.
 * - Hashed with PBKDF2WithHmacSHA256 via [PasswordHasher] on [ioDispatcher].
 *
 * Session:
 * - Successful registration saves a persistent session (`persistent = true`).
 */
class SignupViewModel(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignupUiState())
    val uiState: StateFlow<SignupUiState> = _uiState.asStateFlow()

    companion object {
        /** Regex pattern validating standard email formats across Android and JVM tests. */
        private val EMAIL_REGEX = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$".toRegex()
    }

    /**
     * Updates the typed email in state and clears any previous email error.
     *
     * @param email The updated email string.
     */
    fun onEmailChanged(email: String) {
        _uiState.update { it.copy(email = email, emailError = null) }
    }

    /**
     * Clears the password error when the user edits the password field.
     */
    fun onPasswordChanged() {
        _uiState.update { it.copy(passwordError = null) }
    }

    /**
     * Clears the confirm-password error when the user edits the confirm field.
     */
    fun onConfirmPasswordChanged() {
        _uiState.update { it.copy(confirmPasswordError = null) }
    }

    /**
     * Validates the email field on blur (loss of focus).
     */
    fun onEmailFocusLost() {
        val trimmed = _uiState.value.email.trim()
        val error = when {
            trimmed.isEmpty() -> R.string.error_email_required
            !EMAIL_REGEX.matches(trimmed) -> R.string.error_email_invalid
            else -> null
        }
        _uiState.update { it.copy(emailError = error) }
    }

    /**
     * Validates the password field on blur.
     *
     * @param password The currently typed password.
     */
    fun onPasswordFocusLost(password: String) {
        val error = when {
            password.isEmpty() -> R.string.error_password_required
            password.length < 8 -> R.string.error_password_length
            !password.any { it.isLetter() } || !password.any { it.isDigit() } -> R.string.error_password_complexity
            else -> null
        }
        _uiState.update { it.copy(passwordError = error) }
    }

    /**
     * Validates the confirm password field on blur.
     *
     * @param password The currently typed password.
     * @param confirm  The currently typed confirmation password.
     */
    fun onConfirmPasswordFocusLost(password: String, confirm: String) {
        val error = when {
            confirm.isEmpty() -> R.string.error_password_confirm_required
            confirm != password -> R.string.error_passwords_do_not_match
            else -> null
        }
        _uiState.update { it.copy(confirmPasswordError = error) }
    }

    /**
     * Attempts to register a new user account with the supplied credentials.
     *
     * Validates all fields:
     * 1. Format and complexity constraints.
     * 2. Checks database for duplicate email address.
     * 3. Hashes password using [PasswordHasher.hash].
     * 4. Inserts into database and saves persistent session.
     * 5. Emits navigation event to pet list screen.
     *
     * @param password The plaintext password to validate and hash.
     * @param confirm  The confirmation password.
     */
    fun register(password: String, confirm: String) {
        val trimmedEmail = _uiState.value.email.trim().lowercase()

        // 1. Validate email format
        val emailError = when {
            trimmedEmail.isEmpty() -> R.string.error_email_required
            !EMAIL_REGEX.matches(trimmedEmail) -> R.string.error_email_invalid
            else -> null
        }

        // 2. Validate password rules
        val passwordError = when {
            password.isEmpty() -> R.string.error_password_required
            password.length < 8 -> R.string.error_password_length
            !password.any { it.isLetter() } || !password.any { it.isDigit() } -> R.string.error_password_complexity
            else -> null
        }

        // 3. Validate confirmation matches
        val confirmError = when {
            confirm.isEmpty() -> R.string.error_password_confirm_required
            confirm != password -> R.string.error_passwords_do_not_match
            else -> null
        }

        if (emailError != null || passwordError != null || confirmError != null) {
            _uiState.update {
                it.copy(
                    emailError = emailError,
                    passwordError = passwordError,
                    confirmPasswordError = confirmError
                )
            }
            return
        }

        // Proceed to duplicate check and database insertion
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Check for existing account with same email
            val existing = userRepository.findByEmail(trimmedEmail)
            if (existing != null) {
                _uiState.update {
                    it.copy(
                        emailError = R.string.error_email_duplicate,
                        isLoading = false
                    )
                }
                return@launch
            }

            // Cryptographically hash password (off main thread)
            val hashResult = PasswordHasher.hash(password, ioDispatcher)

            // Insert new user
            val newUserId = userRepository.insert(
                UserEntity(
                    email = trimmedEmail,
                    passwordHash = hashResult.hashBase64,
                    salt = hashResult.saltBase64
                )
            )

            // Signup automatically defaults to remember-me (persistent session)
            sessionManager.saveSession(newUserId, persistent = true)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    navigateToPetList = true
                )
            }
        }
    }

    /**
     * Resets the navigation trigger once consumed by the Fragment.
     */
    fun onNavigated() {
        _uiState.update { it.copy(navigateToPetList = false) }
    }
}

/**
 * Factory for creating [SignupViewModel] instances.
 */
class SignupViewModelFactory(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SignupViewModel::class.java)) {
            return SignupViewModel(userRepository, sessionManager, ioDispatcher) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
