package com.petcare.app.ui.delegate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.repository.PetRepository
import com.petcare.app.data.repository.TaskRepository
import com.petcare.app.data.repository.UserRepository
import com.petcare.app.data.session.SessionManager
import com.petcare.app.ui.checklist.ChecklistFilterMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * UI state for the SMS Delegation screen.
 *
 * Encapsulates all data required to render the delegation interface, validate
 * inputs, build and preview the formatted SMS message, and execute direct send
 * or messaging app fallback.
 */
data class DelegateUiState(
    val pet: PetEntity? = null,
    val senderName: String = "",
    val filterMode: ChecklistFilterMode = ChecklistFilterMode.TODAY,
    val allTasks: List<TaskEntity> = emptyList(),
    val displayTasks: List<TaskEntity> = emptyList(),
    val selectedTaskIds: Set<Long> = emptySet(),
    val recipientPhone: String = "",
    val contactName: String? = null,
    val extraInstructions: String = "",
    val previewMessage: String = "",
    val isManualEdit: Boolean = false,
    val isGsm7Bit: Boolean = true,
    val charCount: Int = 0,
    val smsPartsCount: Int = 0,
    val isPhoneValid: Boolean = false,
    val canSend: Boolean = false,
    val isSending: Boolean = false,
    val sendSuccess: Boolean = false,
    val userMessage: String? = null
)

/**
 * ViewModel managing SMS delegation for a pet's care routines.
 *
 * ## Architectural Trade-off Justification: Direct SMS vs Intent Fallback
 * -------------------------------------------------------------------------
 * This implementation provides two parallel paths for delegating care plans:
 *
 * 1. **Direct Send (`SmsManager.sendMultipartTextMessage`)**:
 *    - **Advantages**: Provides a polished, uninterrupted in-app UX. The user can
 *      pick a contact, confirm the plan, and send it directly without bouncing out
 *      to an external messaging client. Provides delivery/sent broadcast callbacks.
 *    - **Disadvantages & Store Policy**: Requires the restricted [android.permission.SEND_SMS]
 *      dangerous runtime permission. Under current Google Play Store Policy (declared
 *      "Permissions and APIs that Access Sensitive Information"), `SEND_SMS` is
 *      strictly reserved for apps whose core functionality is acting as the device's
 *      default SMS/MMS handler. General pet care or utility apps submitted with
 *      `SEND_SMS` without default-handler status are rejected during Play Store review.
 *    - **Educational Purpose**: Implemented here to demonstrate robust production-grade
 *      runtime permission handling (pre-request rationale, granular request flow,
 *      permanent denial recovery with Settings fallback, and multi-part SMS splitting).
 *
 * 2. **External Messaging App (`Intent.ACTION_SENDTO` with `smsto:<number>`)**:
 *    - **Advantages**: Requires **zero permissions**. Perfectly compliant with Google Play
 *      policies for all app categories. Hands off message transmission to the user's
 *      preferred default messaging client (Google Messages, Samsung Messages, Signal, etc.),
 *      respecting user preferences, RCS/MMS features, dual-SIM selections, and carrier plans.
 *    - **Disadvantages**: Takes the user out of the PetCare application to finalize the send.
 *
 * By implementing both, PetCare offers the best of both worlds: a direct sending
 * experience on supported devices while gracefully falling back to the messaging app
 * whenever permission is denied, permanent denial occurs, or on non-telephony devices (tablets).
 */
class DelegateViewModel(
    private val petId: Long,
    private val petRepository: PetRepository,
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _pet = MutableStateFlow<PetEntity?>(null)
    private val _allTasks = MutableStateFlow<List<TaskEntity>>(emptyList())
    private val _senderName = MutableStateFlow("")
    private val _filterMode = MutableStateFlow(ChecklistFilterMode.TODAY)
    private val _selectedTaskIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _recipientPhone = MutableStateFlow("")
    private val _contactName = MutableStateFlow<String?>(null)
    private val _extraInstructions = MutableStateFlow("")
    private val _manualPreview = MutableStateFlow<String?>(null)
    private val _isSending = MutableStateFlow(false)
    private val _sendSuccess = MutableStateFlow(false)
    private val _userMessage = MutableStateFlow<String?>(null)

    // Tracks if task selection has been manually modified by the user
    private var hasManuallyChangedTasks = false

    /**
     * Combined reactive UI state flow.
     */
    val uiState: StateFlow<DelegateUiState> = combine(
        combine(_pet, _allTasks, _senderName, _filterMode, _selectedTaskIds) { pet, tasks, sender, mode, selectedIds ->
            CombinedData1(pet, tasks, sender, mode, selectedIds)
        },
        combine(_recipientPhone, _contactName, _extraInstructions, _manualPreview) { phone, contact, notes, manual ->
            CombinedData2(phone, contact, notes, manual)
        },
        combine(_isSending, _sendSuccess, _userMessage) { sending, success, msg ->
            CombinedData3(sending, success, msg)
        }
    ) { d1, d2, d3 ->
        computeUiState(d1, d2, d3)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DelegateUiState()
    )

    init {
        loadPetAndTasks()
        loadSenderName()
    }

    private fun loadPetAndTasks() {
        viewModelScope.launch {
            val petEntity = petRepository.findById(petId)
            _pet.value = petEntity

            taskRepository.getAllForPet(petId).collect { tasks ->
                _allTasks.value = tasks
                // If the user has not explicitly unselected items, select all relevant tasks
                if (!hasManuallyChangedTasks) {
                    val relevantTasks = filterTasksForMode(tasks, _filterMode.value)
                    _selectedTaskIds.value = relevantTasks.map { it.id }.toSet()
                }
            }
        }
    }

    private fun loadSenderName() {
        viewModelScope.launch {
            try {
                val userId = sessionManager.getUserId()
                if (userId != SessionManager.NO_USER) {
                    val user = userRepository.findById(userId)
                    if (user != null) {
                        val derived = user.email.substringBefore('@')
                            .replaceFirstChar { it.titlecase() }
                        _senderName.value = derived
                    }
                }
            } catch (e: Exception) {
                _senderName.value = ""
            }
        }
    }

    private fun filterTasksForMode(tasks: List<TaskEntity>, mode: ChecklistFilterMode): List<TaskEntity> {
        return when (mode) {
            ChecklistFilterMode.ALL_ROUTINES -> tasks
            ChecklistFilterMode.TODAY -> {
                val today = LocalDate.now()
                val todayBit = 1 shl (today.dayOfWeek.value - 1)
                tasks.filter { task ->
                    task.frequency == com.petcare.app.data.db.TaskFrequency.DAILY ||
                            (task.daysOfWeek and todayBit) != 0
                }
            }
        }
    }

    private fun computeUiState(d1: CombinedData1, d2: CombinedData2, d3: CombinedData3): DelegateUiState {
        val displayTasks = filterTasksForMode(d1.tasks, d1.mode)
        val selectedTasks = displayTasks.filter { it.id in d1.selectedIds }

        // Determine message text (either user-customized or freshly generated)
        val isManual = d2.manualPreview != null
        val messageText = d2.manualPreview ?: CarePlanMessageBuilder.buildMessage(
            petName = d1.pet?.name ?: "Pet",
            petBreed = d1.pet?.breed ?: "",
            senderName = d1.sender,
            tasks = selectedTasks,
            extraInstructions = d2.extraInstructions
        )

        val isGsm = CarePlanMessageBuilder.isGsm7Bit(messageText)
        val charCount = messageText.length
        val smsParts = CarePlanMessageBuilder.calculateSmsParts(messageText)
        val isPhoneValid = CarePlanMessageBuilder.isValidPhoneNumber(d2.recipientPhone)
        val canSend = isPhoneValid && messageText.isNotBlank() && !d3.isSending

        return DelegateUiState(
            pet = d1.pet,
            senderName = d1.sender,
            filterMode = d1.mode,
            allTasks = d1.tasks,
            displayTasks = displayTasks,
            selectedTaskIds = d1.selectedIds,
            recipientPhone = d2.recipientPhone,
            contactName = d2.contactName,
            extraInstructions = d2.extraInstructions,
            previewMessage = messageText,
            isManualEdit = isManual,
            isGsm7Bit = isGsm,
            charCount = charCount,
            smsPartsCount = smsParts,
            isPhoneValid = isPhoneValid,
            canSend = canSend,
            isSending = d3.isSending,
            sendSuccess = d3.sendSuccess,
            userMessage = d3.userMessage
        )
    }

    // ── User Actions ──────────────────────────────────────────────────────────

    fun setRecipientPhone(phone: String) {
        _recipientPhone.value = phone
    }

    fun setContact(name: String, phone: String) {
        _contactName.value = name.ifBlank { null }
        _recipientPhone.value = phone
    }

    fun setFilterMode(mode: ChecklistFilterMode) {
        if (_filterMode.value != mode) {
            _filterMode.value = mode
            // Update selected tasks to all items of the new mode
            val relevantTasks = filterTasksForMode(_allTasks.value, mode)
            _selectedTaskIds.value = relevantTasks.map { it.id }.toSet()
            hasManuallyChangedTasks = false
        }
    }

    fun toggleTaskSelection(taskId: Long) {
        hasManuallyChangedTasks = true
        val current = _selectedTaskIds.value.toMutableSet()
        if (taskId in current) {
            current.remove(taskId)
        } else {
            current.add(taskId)
        }
        _selectedTaskIds.value = current
    }

    fun selectAllTasks() {
        hasManuallyChangedTasks = true
        val displayTasks = filterTasksForMode(_allTasks.value, _filterMode.value)
        _selectedTaskIds.value = displayTasks.map { it.id }.toSet()
    }

    fun clearTaskSelection() {
        hasManuallyChangedTasks = true
        _selectedTaskIds.value = emptySet()
    }

    fun setExtraInstructions(notes: String) {
        _extraInstructions.value = notes
    }

    fun setManualPreview(text: String) {
        _manualPreview.value = text
    }

    fun resetToGeneratedMessage() {
        _manualPreview.value = null
    }

    fun setSending(sending: Boolean) {
        _isSending.value = sending
    }

    fun onSendCompleted(success: Boolean, message: String? = null) {
        _isSending.value = false
        _sendSuccess.value = success
        _userMessage.value = message
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    // Helper data holders for combine
    private data class CombinedData1(
        val pet: PetEntity?,
        val tasks: List<TaskEntity>,
        val sender: String,
        val mode: ChecklistFilterMode,
        val selectedIds: Set<Long>
    )

    private data class CombinedData2(
        val recipientPhone: String,
        val contactName: String?,
        val extraInstructions: String,
        val manualPreview: String?
    )

    private data class CombinedData3(
        val isSending: Boolean,
        val sendSuccess: Boolean,
        val userMessage: String?
    )

    /**
     * Factory for creating [DelegateViewModel] instances with manual DI.
     */
    class Factory(
        private val petId: Long,
        private val petRepository: PetRepository,
        private val taskRepository: TaskRepository,
        private val userRepository: UserRepository,
        private val sessionManager: SessionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DelegateViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return DelegateViewModel(
                petId = petId,
                petRepository = petRepository,
                taskRepository = taskRepository,
                userRepository = userRepository,
                sessionManager = sessionManager
            ) as T
        }
    }
}
