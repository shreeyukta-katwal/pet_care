package com.petcare.app.ui.pet

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.repository.PetRepository
import com.petcare.app.data.session.SessionManager
import com.petcare.app.util.PhotoStorageHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── UI state ──────────────────────────────────────────────────────────────────

/**
 * Sealed class representing every possible UI state for [PetFormFragment].
 *
 * The Fragment observes this and renders accordingly:
 * - [Idle]    → empty / pre-filled form, buttons enabled.
 * - [Loading] → form fields disabled, spinner visible.
 * - [Saved]   → navigate back (one-shot).
 * - [Error]   → show error in the appropriate TextInputLayout.
 */
sealed class PetFormState {

    /** Initial state; form is interactive and ready for input. */
    object Idle : PetFormState()

    /** Pet data loaded from DB in edit mode; Fragment should populate fields. */
    data class PetLoaded(val pet: PetEntity) : PetFormState()

    /** A database or photo-copy operation is in progress. */
    object Loading : PetFormState()

    /** The pet was successfully saved/updated; Fragment should navigate back. */
    object Saved : PetFormState()

    /** Validation or save failed; [field] indicates which field caused the error. */
    data class ValidationError(val field: Field, val message: String) : PetFormState() {

        /** The form field that contains an invalid value. */
        enum class Field { NAME, SPECIES, AGE, WEIGHT }
    }

    /** An unexpected error occurred during save (e.g. DB exception). */
    data class SaveError(val message: String) : PetFormState()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * ViewModel for [PetFormFragment].
 *
 * Handles both "create new pet" (petId == 0) and "edit existing pet" (petId > 0) modes.
 *
 * Key responsibilities:
 * - Loading an existing pet from the database when in edit mode.
 * - Tracking a "pending" photo URI (chosen from camera or gallery) before save.
 * - Tracking whether the form is "dirty" (has unsaved changes) for the back-press guard.
 * - Validating all input fields before saving.
 * - Copying the selected photo to internal storage via [PhotoStorageHelper].
 * - Inserting or updating the [PetEntity] in the database.
 *
 * @param petId           0 for new pet; existing pet's primary key for edit.
 * @param petRepository   Repository for pet CRUD operations.
 * @param sessionManager  Provides the current user's ID.
 * @param appContext      Application context for [PhotoStorageHelper] (safe, not Activity).
 */
class PetFormViewModel(
    private val petId: Long,
    private val petRepository: PetRepository,
    private val sessionManager: SessionManager,
    private val appContext: android.content.Context? = null
) : ViewModel() {

    /** True when the ViewModel is in "edit" mode (editing an existing pet). */
    val isEditMode: Boolean get() = petId != 0L

    /**
     * Resets the UI state to [PetFormState.Idle].
     * Called after one-time events (such as [PetFormState.PetLoaded]) are handled.
     */
    fun resetState() {
        if (_state.value is PetFormState.PetLoaded || _state.value is PetFormState.ValidationError) {
            _state.value = PetFormState.Idle
        }
    }

    // ── UI state ──────────────────────────────────────────────────────────

    private val _state = MutableStateFlow<PetFormState>(PetFormState.Idle)

    /**
     * UI state observed by [PetFormFragment].
     * Drives form enablement, spinner, and navigation.
     */
    val state: StateFlow<PetFormState> = _state.asStateFlow()

    // ── Pet data state ────────────────────────────────────────────────────

    /**
     * The pet currently being edited, or null when creating a new pet.
     * Set once by [loadPet]; used to detect dirty state and preserve unchanged fields.
     */
    private var originalPet: PetEntity? = null

    /**
     * URI of a newly selected photo (from camera or gallery), not yet saved.
     * Null means: use [existingPhotoUri] or no photo.
     */
    private var pendingPhotoUri: Uri? = null

    /**
     * Photo URI currently stored in the database for this pet.
     * Null when no photo is set or when creating a new pet.
     */
    var existingPhotoUri: String? = null
        private set

    // ── Dirty state ───────────────────────────────────────────────────────

    private val _isDirty = MutableStateFlow(false)

    /**
     * True if the user has changed any field without saving.
     * Used by [PetFormFragment]'s back-press guard.
     */
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    // ── Initialisation ────────────────────────────────────────────────────

    init {
        if (isEditMode) {
            loadPet()
        }
    }

    /**
     * Loads the existing [PetEntity] from the database in edit mode.
     *
     * Transitions through [PetFormState.Loading] while fetching, then back to
     * [PetFormState.Idle] once data is available. The Fragment observes [state]
     * and [originalPet] via the shared [petLoaded] flow.
     */
    private fun loadPet() {
        viewModelScope.launch {
            _state.value = PetFormState.Loading
            val pet = petRepository.findById(petId)
            if (pet != null) {
                originalPet = pet
                existingPhotoUri = pet.photoUri
                if (!pet.photoUri.isNullOrBlank()) {
                    _displayPhotoUri.value = Uri.parse(pet.photoUri)
                }
                _state.value = PetFormState.PetLoaded(pet)
            } else {
                _state.value = PetFormState.Idle
            }
        }
    }

    /**
     * Returns the currently loaded [PetEntity] for pre-filling the form fields.
     * Null when in create mode or before [loadPet] has completed.
     */
    fun getOriginalPet(): PetEntity? = originalPet

    // ── Photo selection ───────────────────────────────────────────────────

    private val _displayPhotoUri = MutableStateFlow<Uri?>(null)

    /**
     * Flow of the current photo URI to render in the photo preview.
     * Updated when a photo is loaded, picked, captured, or removed.
     */
    val displayPhotoUri: StateFlow<Uri?> = _displayPhotoUri.asStateFlow()

    /**
     * Called when the user selects a photo from the gallery.
     *
     * Stores the URI as [pendingPhotoUri] and marks the form dirty.
     *
     * @param uri The content URI returned by [ActivityResultContracts.PickVisualMedia].
     */
    fun onPhotoSelected(uri: Uri) {
        pendingPhotoUri = uri
        _displayPhotoUri.value = uri
        markDirty()
    }

    /**
     * Called after [ActivityResultContracts.TakePicture] completes.
     *
     * If the capture succeeded, stores the FileProvider [tempUri] as pending.
     * If capture was cancelled, the pending photo remains unchanged.
     *
     * @param success  True if the camera wrote a photo; false if cancelled.
     * @param tempUri  The FileProvider URI that was passed to the camera intent.
     */
    fun onPhotoCaptured(success: Boolean, tempUri: Uri) {
        if (success) {
            pendingPhotoUri = tempUri
            _displayPhotoUri.value = tempUri
            markDirty()
        }
    }

    /**
     * Called when the user taps "Remove Photo".
     * Clears both the pending URI and the existing stored URI.
     */
    fun onPhotoRemoved() {
        pendingPhotoUri = null
        existingPhotoUri = null
        _displayPhotoUri.value = null
        markDirty()
    }

    /**
     * Returns the URI to display in the photo preview.
     *
     * Priority: pendingPhotoUri > existingPhotoUri > null (placeholder).
     */
    fun getDisplayPhotoUri(): Uri? = _displayPhotoUri.value

    // ── Form dirty tracking ───────────────────────────────────────────────

    /**
     * Called by the Fragment whenever any text field changes.
     * Marks the form as having unsaved changes.
     */
    fun markDirty() {
        _isDirty.value = true
    }

    // ── Save ──────────────────────────────────────────────────────────────

    /**
     * Validates and saves the pet form data.
     *
     * Validation rules:
     * - Name: required, non-blank.
     * - Species: required, non-blank.
     * - Age: if provided, must be between 0 and 40.
     * - Weight: if provided, must be between 0.1 and 150.
     *
     * If validation passes:
     * 1. Copies [pendingPhotoUri] to internal storage (replaces old photo if in edit mode).
     * 2. Inserts (create) or updates (edit) the [PetEntity] in Room.
     * 3. Transitions to [PetFormState.Saved] → Fragment navigates back.
     *
     * @param name           Pet name field value.
     * @param species        Selected or typed species value.
     * @param breed          Optional breed.
     * @param ageText        Raw text from age field (may be empty).
     * @param weightText     Raw text from weight field (may be empty).
     * @param diet           Optional diet notes.
     * @param allergies      Optional allergy notes.
     * @param vaccinations   Optional vaccination notes.
     * @param favouriteToys  Optional favourite toys.
     * @param notes          Optional miscellaneous notes.
     */
    fun save(
        name: String,
        species: String,
        breed: String,
        ageText: String,
        weightText: String,
        diet: String,
        allergies: String,
        vaccinations: String,
        favouriteToys: String,
        notes: String
    ) {
        // ── Validate ──────────────────────────────────────────────────
        val trimmedName    = name.trim()
        val trimmedSpecies = species.trim()

        if (trimmedName.isBlank()) {
            _state.value = PetFormState.ValidationError(
                PetFormState.ValidationError.Field.NAME,
                "Pet name is required"
            )
            return
        }

        if (trimmedSpecies.isBlank()) {
            _state.value = PetFormState.ValidationError(
                PetFormState.ValidationError.Field.SPECIES,
                "Species is required"
            )
            return
        }

        val ageYears: Float = if (ageText.isBlank()) {
            0f
        } else {
            val parsed = ageText.toFloatOrNull()
            if (parsed == null || parsed < 0f || parsed > 40f) {
                _state.value = PetFormState.ValidationError(
                    PetFormState.ValidationError.Field.AGE,
                    "Age must be between 0 and 40 years"
                )
                return
            }
            parsed
        }

        val weightKg: Float = if (weightText.isBlank()) {
            0f
        } else {
            val parsed = weightText.toFloatOrNull()
            if (parsed == null || parsed < 0.1f || parsed > 150f) {
                _state.value = PetFormState.ValidationError(
                    PetFormState.ValidationError.Field.WEIGHT,
                    "Weight must be between 0.1 and 150 kg"
                )
                return
            }
            parsed
        }

        // ── Save ──────────────────────────────────────────────────────
        _state.value = PetFormState.Loading
        viewModelScope.launch {
            try {
                // Copy pending photo to internal storage (replaces old if editing)
                val savedPhotoUri: String? = when {
                    pendingPhotoUri != null -> {
                        val oldUri = if (isEditMode) originalPet?.photoUri else null
                        if (appContext != null) {
                            PhotoStorageHelper.savePhotoToInternalStorage(
                                context = appContext,
                                sourceUri = pendingPhotoUri!!,
                                oldPhotoPath = oldUri
                            )
                        } else {
                            pendingPhotoUri.toString()
                        }
                    }
                    // User removed photo
                    existingPhotoUri == null && isEditMode -> {
                        // Delete the old file if the user removed it
                        originalPet?.photoUri?.let { PhotoStorageHelper.deletePhotoFile(it) }
                        null
                    }
                    else -> existingPhotoUri
                }

                val userId = sessionManager.getUserId()
                    ?: throw IllegalStateException("No active session during save")

                if (isEditMode) {
                    // Update existing pet, preserving its id and userId
                    val updatedPet = originalPet!!.copy(
                        name           = trimmedName,
                        species        = trimmedSpecies,
                        breed          = breed.trim(),
                        ageYears       = ageYears,
                        weightKg       = weightKg,
                        diet           = diet.trim(),
                        allergies      = allergies.trim(),
                        vaccinations   = vaccinations.trim(),
                        favouriteToys  = favouriteToys.trim(),
                        notes          = notes.trim(),
                        photoUri       = savedPhotoUri
                    )
                    petRepository.update(updatedPet)
                } else {
                    // Insert a new pet
                    val newPet = PetEntity(
                        userId        = userId,
                        name          = trimmedName,
                        species       = trimmedSpecies,
                        breed         = breed.trim(),
                        ageYears      = ageYears,
                        weightKg      = weightKg,
                        diet          = diet.trim(),
                        allergies     = allergies.trim(),
                        vaccinations  = vaccinations.trim(),
                        favouriteToys = favouriteToys.trim(),
                        notes         = notes.trim(),
                        photoUri      = savedPhotoUri
                    )
                    petRepository.insert(newPet)
                }

                _isDirty.value = false
                _state.value = PetFormState.Saved

            } catch (e: Exception) {
                _state.value = PetFormState.SaveError(
                    e.message ?: "An unexpected error occurred while saving"
                )
            }
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────

    /**
     * Factory for creating [PetFormViewModel] with the required constructor parameters.
     *
     * @param petId          0 for new pet; existing pet ID for edit.
     * @param petRepository  Repository for pet CRUD.
     * @param sessionManager Current user session.
     * @param appContext     Application context for [PhotoStorageHelper].
     */
    class Factory(
        private val petId: Long,
        private val petRepository: PetRepository,
        private val sessionManager: SessionManager,
        private val appContext: android.content.Context
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == PetFormViewModel::class.java) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return PetFormViewModel(
                petId = petId,
                petRepository = petRepository,
                sessionManager = sessionManager,
                appContext = appContext
            ) as T
        }
    }
}
