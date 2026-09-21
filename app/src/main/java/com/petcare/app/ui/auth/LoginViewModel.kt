package com.petcare.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.petcare.app.R
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
 * UI state representing the Login screen.
 *
 * @property email             The currently entered email (survives rotation).
 * @property rememberMe        Whether "Remember me" checkbox is checked.
 * @property emailError        Resource ID for email field error, or null.
 * @property passwordError     Resource ID for password field error, or null.
 * @property isLoading         True while verifying credentials against DB and hasher.
 * @property navigateToPetList One-time navigation event trigger on successful login.
 */
data class LoginUiState(
    val email: String = "",
    val rememberMe: Boolean = true,
    val emailError: Int? = null,
    val passwordError: Int? = null,
    val isLoading: Boolean = false,
    val navigateToPetList: Boolean = false
)

/**
 * ViewModel for [LoginFragment].
 *
 * Enforces security and validation rules:
 * - Empty fields are highlighted with appropriate errors.
 * - Non-existent email OR wrong password produces ONE generic message ("Incorrect email or password")
 *   to completely prevent account enumeration attacks.
 * - Password verification is off the main thread using [PasswordHasher.verify] on [ioDispatcher].
 * - "Remember me":
 *     - Checked (`persistent = true`): saves to SharedPreferences (survives app restarts).
 *     - Unchecked (`persistent = false`): memory-only session for the current process.
 * - Never logs or persists plain-text passwords.
 */
class LoginViewModel(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /**
     * Updates typed email in state and clears field errors.
     *
     * @param email The updated email string.
     */
    fun onEmailChanged(email: String) {
        _uiState.update { it.copy(email = email, emailError = null, passwordError = null) }
    }

    /**
     * Clears password error when the user edits the password field.
     */
    fun onPasswordChanged() {
        _uiState.update { it.copy(passwordError = null) }
    }

    /**
     * Updates the "Remember me" checkbox state.
     *
     * @param checked Current checkbox checked state.
     */
    fun onRememberMeChanged(checked: Boolean) {
        _uiState.update { it.copy(rememberMe = checked) }
    }

    /**
     * Validates email on focus loss.
     */
    fun onEmailFocusLost() {
        if (_uiState.value.email.trim().isEmpty()) {
            _uiState.update { it.copy(emailError = R.string.error_email_required) }
        }
    }

    /**
     * Validates password on focus loss.
     *
     * @param password Currently typed password.
     */
    fun onPasswordFocusLost(password: String) {
        if (password.isEmpty()) {
            _uiState.update { it.copy(passwordError = R.string.error_password_required) }
        }
    }

    /**
     * Attempts to log in with the provided credentials.
     *
     * @param password The candidate plaintext password.
     */
    fun login(password: String) {
        val trimmedEmail = _uiState.value.email.trim().lowercase()

        // 1. Validate non-empty fields
        val emailError = if (trimmedEmail.isEmpty()) R.string.error_email_required else null
        val passwordError = if (password.isEmpty()) R.string.error_password_required else null

        if (emailError != null || passwordError != null) {
            _uiState.update {
                it.copy(emailError = emailError, passwordError = passwordError)
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, emailError = null, passwordError = null) }

            // 2. Lookup user by email in database
            val user = userRepository.findByEmail(trimmedEmail)

            // Generic error helper: show the exact same error if user not found or password fails
            if (user == null) {
                _uiState.update {
                    it.copy(
                        passwordError = R.string.error_invalid_credentials,
                        isLoading = false
                    )
                }
                return@launch
            }

            // 3. Verify password hash using constant-time comparison
            val isMatch = PasswordHasher.verify(
                candidatePassword = password,
                expectedHashBase64 = user.passwordHash,
                saltBase64 = user.salt,
                dispatcher = ioDispatcher
            )

            if (!isMatch) {
                // Show generic error message to prevent enumeration
                _uiState.update {
                    it.copy(
                        passwordError = R.string.error_invalid_credentials,
                        isLoading = false
                    )
                }
                return@launch
            }

            // 4. Save session with user's persistent preference
            sessionManager.saveSession(user.id, persistent = _uiState.value.rememberMe)

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
 * Factory for creating [LoginViewModel] instances.
 */
class LoginViewModelFactory(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            return LoginViewModel(userRepository, sessionManager, ioDispatcher) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
