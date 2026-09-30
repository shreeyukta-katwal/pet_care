package com.petcare.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.petcare.app.data.db.AppStats
import com.petcare.app.data.db.UserWithCounts
import com.petcare.app.data.repository.AdminRepository
import com.petcare.app.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Rotation-safe state for the privileged dashboard. */
data class AdminDashboardUiState(
    val authorized: Boolean? = null,
    val stats: AppStats? = null,
    val users: List<UserWithCounts> = emptyList(),
    val deleted: Boolean = false,
    val resetPassword: String? = null
)

/** ViewModel that authorizes first, then exposes the isolated admin data source. */
class AdminDashboardViewModel(
    private val currentUserId: Long,
    private val userRepository: UserRepository,
    private val adminRepository: AdminRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AdminDashboardUiState())
    val uiState: StateFlow<AdminDashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val isAdmin = userRepository.findById(currentUserId)?.isAdmin == true
            _uiState.value = _uiState.value.copy(authorized = isAdmin)
            if (isAdmin) refresh()
        }
    }

    /** Reloads totals and excludes the signed-in administrator from the manageable user list. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                stats = adminRepository.getAppStats(),
                users = adminRepository.getAllUsersWithCounts().filter { it.user.id != currentUserId }
            )
        }
    }

    /** Permanently removes a non-current account after the Fragment obtains confirmation. */
    fun deleteUser(userId: Long) {
        if (userId == currentUserId) return
        viewModelScope.launch {
            adminRepository.deleteUserAccount(userId)
            _uiState.value = _uiState.value.copy(deleted = true)
            refresh()
        }
    }

    /** Resets another account to a one-time temporary password for the admin to share securely. */
    fun resetPassword(userId: Long) {
        if (userId == currentUserId) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(resetPassword = adminRepository.resetUserPassword(userId))
        }
    }

    /** Marks the one-shot deletion confirmation as consumed. */
    fun deletionHandled() { _uiState.value = _uiState.value.copy(deleted = false) }

    /** Removes the plaintext temporary password from rotation-safe UI state after display. */
    fun resetPasswordHandled() { _uiState.value = _uiState.value.copy(resetPassword = null) }

    /** Factory for this manually injected ViewModel. */
    class Factory(
        private val currentUserId: Long,
        private val userRepository: UserRepository,
        private val adminRepository: AdminRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AdminDashboardViewModel(currentUserId, userRepository, adminRepository) as T
    }
}
