package com.petcare.app.ui.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.petcare.app.data.repository.UserRepository
import com.petcare.app.data.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Represents the UI state of the [WelcomeFragment].
 */
sealed interface WelcomeUiState {
    /**
     * Initial state while checking whether an active user session exists in local storage.
     * UI action buttons remain hidden during this state to prevent flickering.
     */
    data object Loading : WelcomeUiState

    /**
     * User has an active, valid session; the fragment should immediately navigate to the pet list.
     */
    data object NavigateToPetList : WelcomeUiState

    /**
     * No active session found (or stored session was invalid); the welcome screen UI should be displayed.
     */
    data object ShowWelcome : WelcomeUiState
}

/**
 * ViewModel for [WelcomeFragment].
 *
 * Checks on initialisation whether a user session is active via [SessionManager].
 * If a stored session exists, it validates that the user is still present in the Room database.
 * If valid, it triggers navigation to the pet list screen; if invalid or absent, it displays
 * the welcome screen with Login and Sign Up options.
 *
 * @property sessionManager Coordinates session persistence and retrieval.
 * @property userRepository Accesses user records in the local database.
 */
class WelcomeViewModel(
    private val sessionManager: SessionManager,
    private val userRepository: UserRepository
) : ViewModel() {

    /** Mutable backing property for [uiState]. */
    private val _uiState = MutableStateFlow<WelcomeUiState>(WelcomeUiState.Loading)

    /**
     * Exposes the current [WelcomeUiState] as an immutable [StateFlow].
     */
    val uiState: StateFlow<WelcomeUiState> = _uiState.asStateFlow()

    init {
        checkSession()
    }

    /**
     * Checks if a valid user session is stored.
     *
     * Runs asynchronously on a coroutine off the main thread:
     * 1. Reads userId from [SessionManager].
     * 2. If valid (`!= NO_USER`), verifies the user still exists in [UserRepository].
     * 3. If the user exists, transitions to [WelcomeUiState.NavigateToPetList].
     * 4. If the user is missing from DB (e.g. deleted), clears the stale session and transitions to [WelcomeUiState.ShowWelcome].
     * 5. If no session is saved, transitions to [WelcomeUiState.ShowWelcome].
     */
    fun checkSession() {
        viewModelScope.launch {
            val userId = sessionManager.getUserId()
            if (userId != SessionManager.NO_USER) {
                val user = userRepository.findById(userId)
                if (user != null) {
                    _uiState.value = WelcomeUiState.NavigateToPetList
                } else {
                    // Stale session pointing to a deleted or non-existent user
                    sessionManager.clear()
                    _uiState.value = WelcomeUiState.ShowWelcome
                }
            } else {
                _uiState.value = WelcomeUiState.ShowWelcome
            }
        }
    }
}

/**
 * Factory class to instantiate [WelcomeViewModel] with dependencies from [com.petcare.app.AppContainer].
 *
 * @param sessionManager The application session manager.
 * @param userRepository The user repository.
 */
class WelcomeViewModelFactory(
    private val sessionManager: SessionManager,
    private val userRepository: UserRepository
) : ViewModelProvider.Factory {

    /**
     * Creates a new instance of [WelcomeViewModel].
     *
     * @param modelClass Target class to instantiate.
     * @return An instance of [T].
     * @throws IllegalArgumentException If [modelClass] is not assignable to [WelcomeViewModel].
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WelcomeViewModel::class.java)) {
            return WelcomeViewModel(sessionManager, userRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
