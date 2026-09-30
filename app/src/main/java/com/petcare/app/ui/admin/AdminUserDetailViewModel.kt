package com.petcare.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.repository.AdminRepository
import com.petcare.app.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One pet plus its tasks for the administrator's non-editable detail screen. */
data class AdminPetDetail(val pet: PetEntity, val tasks: List<TaskEntity>)

/** Rotation-safe state for the administrator's user-detail view. */
data class AdminUserDetailUiState(
    val authorized: Boolean? = null,
    val pets: List<AdminPetDetail> = emptyList(),
    /** Email address of the account being inspected, used as the toolbar title. */
    val targetUserEmail: String? = null
)

/** Loads a user's pets only after verifying the active account is an administrator. */
class AdminUserDetailViewModel(
    private val currentUserId: Long,
    private val targetUserId: Long,
    private val userRepository: UserRepository,
    private val adminRepository: AdminRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AdminUserDetailUiState())
    val uiState: StateFlow<AdminUserDetailUiState> = _uiState.asStateFlow()

    init { viewModelScope.launch {
        val authorized = userRepository.findById(currentUserId)?.isAdmin == true
        if (!authorized) { _uiState.value = AdminUserDetailUiState(authorized = false); return@launch }
        val targetEmail = userRepository.findById(targetUserId)?.email
        val pets = adminRepository.getPetsForUser(targetUserId).map { pet ->
            AdminPetDetail(pet, adminRepository.getTasksForPet(pet.id))
        }
        _uiState.value = AdminUserDetailUiState(authorized = true, pets = pets, targetUserEmail = targetEmail)
    } }

    /** Factory for the manually injected, argument-aware ViewModel. */
    class Factory(private val currentUserId: Long, private val targetUserId: Long, private val users: UserRepository, private val admin: AdminRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AdminUserDetailViewModel(currentUserId, targetUserId, users, admin) as T
    }
}
