package com.petcare.app.ui.pet

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.petcare.app.PetCareApp
import com.petcare.app.R
import com.petcare.app.data.db.PetEntity
import com.petcare.app.databinding.FragmentPetFormBinding
import com.petcare.app.util.PhotoStorageHelper
import kotlinx.coroutines.launch

/**
 * PetFormFragment – Add / Edit pet profile screen.
 *
 * Supports both create (petId == 0) and edit (petId > 0) modes using a single unified form:
 * - When creating: shows "Add Pet" toolbar title and button text.
 * - When editing: pre-fills all fields, changes title to "Edit Pet", and button to "Save Changes".
 * - Photo integration: circular preview with dialog options to take photo (camera) or choose from gallery.
 *   Uses FileProvider and PickVisualMedia without requiring CAMERA permission.
 * - Unsaved changes guard: intercepts back navigation (system and toolbar) when form is dirty.
 * - Material Design 3 inputs with TextInputLayout error handling and IME navigation.
 */
class PetFormFragment : Fragment() {

    // ── View binding ──────────────────────────────────────────────────────

    private var _binding: FragmentPetFormBinding? = null
    private val binding get() = _binding!!

    // ── Navigation arguments ──────────────────────────────────────────────

    private val args: PetFormFragmentArgs by navArgs()

    // ── ViewModel ─────────────────────────────────────────────────────────

    private val viewModel: PetFormViewModel by viewModels {
        val app = requireActivity().application as PetCareApp
        PetFormViewModel.Factory(
            petId = args.petId,
            petRepository = app.container.petRepository,
            sessionManager = app.container.sessionManager,
            appContext = app.applicationContext
        )
    }

    // ── Photo Activity Result Launchers ───────────────────────────────────

    private var cameraTempUri: Uri? = null

    /** Photo picker for selecting an image from the device gallery. */
    private val galleryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onPhotoSelected(uri)
        }
    }

    /** Camera capture launcher writing to temporary FileProvider URI. */
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val temp = cameraTempUri
        if (temp != null) {
            viewModel.onPhotoCaptured(success, temp)
        }
    }

    // ── Back-press callback ───────────────────────────────────────────────

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showUnsavedChangesDialog()
        }
    }

    // ── Fragment lifecycle ────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPetFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            backPressedCallback
        )

        setupToolbar()
        setupSpeciesDropdown()
        setupPhotoButtons()
        setupTextWatchers()
        setupSaveButton()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── UI setup ──────────────────────────────────────────────────────────

    /**
     * Configures the top app bar with navigation back click and dynamic title.
     */
    private fun setupToolbar() {
        binding.toolbar.title = if (viewModel.isEditMode) {
            getString(R.string.title_edit_pet)
        } else {
            getString(R.string.title_add_pet)
        }

        binding.toolbar.setNavigationOnClickListener {
            handleBackNavigation()
        }
    }

    /**
     * Populates the species exposed dropdown menu with predefined animal types.
     */
    private fun setupSpeciesDropdown() {
        val speciesArray = resources.getStringArray(R.array.species_options)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, speciesArray)
        binding.dropdownSpecies.setAdapter(adapter)

        binding.dropdownSpecies.setOnItemClickListener { _, _, _, _ ->
            binding.layoutSpecies.error = null
            viewModel.markDirty()
        }
    }

    /**
     * Sets up photo preview and change photo button clicks.
     */
    private fun setupPhotoButtons() {
        binding.imagePetPhoto.setOnClickListener {
            showPhotoSourceDialog()
        }
        binding.buttonChangePhoto.setOnClickListener {
            showPhotoSourceDialog()
        }
    }

    /**
     * Displays a dialog with choices for setting the pet photo.
     */
    private fun showPhotoSourceDialog() {
        val options = mutableListOf(
            getString(R.string.action_take_photo),
            getString(R.string.action_choose_gallery)
        )
        val hasPhoto = viewModel.getDisplayPhotoUri() != null
        if (hasPhoto) {
            options.add(getString(R.string.action_remove_photo))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_photo_source_title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 -> launchGallery()
                    2 -> viewModel.onPhotoRemoved()
                }
            }
            .show()
    }

    /**
     * Launches the camera with a temporary FileProvider content URI.
     */
    private fun launchCamera() {
        val tempUri = PhotoStorageHelper.createTempCameraUri(requireContext().applicationContext)
        cameraTempUri = tempUri
        cameraLauncher.launch(tempUri)
    }

    /**
     * Launches the photo picker for images.
     */
    private fun launchGallery() {
        galleryPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    /**
     * Wires text change watchers to mark form dirty and clear field errors.
     */
    private fun setupTextWatchers() {
        val genericWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                viewModel.markDirty()
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        binding.editName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                binding.layoutName.error = null
                viewModel.markDirty()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.editAge.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                binding.layoutAge.error = null
                viewModel.markDirty()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.editWeight.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                binding.layoutWeight.error = null
                viewModel.markDirty()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.editBreed.addTextChangedListener(genericWatcher)
        binding.editDiet.addTextChangedListener(genericWatcher)
        binding.editAllergies.addTextChangedListener(genericWatcher)
        binding.editVaccinations.addTextChangedListener(genericWatcher)
        binding.editFavouriteToys.addTextChangedListener(genericWatcher)
        binding.editNotes.addTextChangedListener(genericWatcher)
    }

    /**
     * Configures the Save/Add button text and click listener.
     */
    private fun setupSaveButton() {
        binding.buttonSave.text = if (viewModel.isEditMode) {
            getString(R.string.btn_save_changes)
        } else {
            getString(R.string.btn_add_pet)
        }

        binding.buttonSave.setOnClickListener {
            viewModel.save(
                name = binding.editName.text?.toString().orEmpty(),
                species = binding.dropdownSpecies.text?.toString().orEmpty(),
                breed = binding.editBreed.text?.toString().orEmpty(),
                ageText = binding.editAge.text?.toString().orEmpty(),
                weightText = binding.editWeight.text?.toString().orEmpty(),
                diet = binding.editDiet.text?.toString().orEmpty(),
                allergies = binding.editAllergies.text?.toString().orEmpty(),
                vaccinations = binding.editVaccinations.text?.toString().orEmpty(),
                favouriteToys = binding.editFavouriteToys.text?.toString().orEmpty(),
                notes = binding.editNotes.text?.toString().orEmpty()
            )
        }
    }

    // ── ViewModel observation ─────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        handleState(state)
                    }
                }

                launch {
                    viewModel.isDirty.collect { isDirty ->
                        backPressedCallback.isEnabled = isDirty
                    }
                }

                launch {
                    viewModel.displayPhotoUri.collect { uri ->
                        updatePhotoPreview(uri)
                    }
                }
            }
        }
    }

    /**
     * Renders UI changes based on [PetFormState].
     */
    private fun handleState(state: PetFormState) {
        when (state) {
            is PetFormState.Loading -> {
                binding.progressIndicator.visibility = View.VISIBLE
                binding.buttonSave.isEnabled = false
            }
            is PetFormState.Idle -> {
                binding.progressIndicator.visibility = View.GONE
                binding.buttonSave.isEnabled = true
            }
            is PetFormState.PetLoaded -> {
                binding.progressIndicator.visibility = View.GONE
                binding.buttonSave.isEnabled = true
                populateFields(state.pet)
                viewModel.resetState()
            }
            is PetFormState.Saved -> {
                binding.progressIndicator.visibility = View.GONE
                findNavController().popBackStack()
            }
            is PetFormState.ValidationError -> {
                binding.progressIndicator.visibility = View.GONE
                binding.buttonSave.isEnabled = true
                displayValidationError(state)
                viewModel.resetState()
            }
            is PetFormState.SaveError -> {
                binding.progressIndicator.visibility = View.GONE
                binding.buttonSave.isEnabled = true
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Updates the circular photo preview.
     */
    private fun updatePhotoPreview(uri: Uri?) {
        if (uri != null) {
            try {
                binding.imagePetPhoto.setImageURI(uri)
            } catch (e: Exception) {
                binding.imagePetPhoto.setImageResource(R.drawable.ic_pet_placeholder)
            }
        } else {
            binding.imagePetPhoto.setImageResource(R.drawable.ic_pet_placeholder)
        }
    }

    /**
     * Pre-fills form fields when editing an existing pet.
     */
    private fun populateFields(pet: PetEntity) {
        binding.editName.setText(pet.name)
        binding.dropdownSpecies.setText(pet.species, false)
        binding.editBreed.setText(pet.breed)
        if (pet.ageYears > 0f) {
            binding.editAge.setText(
                if (pet.ageYears % 1.0f == 0f) pet.ageYears.toInt().toString() else pet.ageYears.toString()
            )
        }
        if (pet.weightKg > 0f) {
            binding.editWeight.setText(
                if (pet.weightKg % 1.0f == 0f) pet.weightKg.toInt().toString() else pet.weightKg.toString()
            )
        }
        binding.editDiet.setText(pet.diet)
        binding.editAllergies.setText(pet.allergies)
        binding.editVaccinations.setText(pet.vaccinations)
        binding.editFavouriteToys.setText(pet.favouriteToys)
        binding.editNotes.setText(pet.notes)
    }

    /**
     * Sets validation error on the offending input layout.
     */
    private fun displayValidationError(error: PetFormState.ValidationError) {
        when (error.field) {
            PetFormState.ValidationError.Field.NAME -> {
                binding.layoutName.error = getString(R.string.error_name_required)
                binding.editName.requestFocus()
            }
            PetFormState.ValidationError.Field.SPECIES -> {
                binding.layoutSpecies.error = getString(R.string.error_species_required)
                binding.dropdownSpecies.requestFocus()
            }
            PetFormState.ValidationError.Field.AGE -> {
                binding.layoutAge.error = getString(R.string.error_age_invalid)
                binding.editAge.requestFocus()
            }
            PetFormState.ValidationError.Field.WEIGHT -> {
                binding.layoutWeight.error = getString(R.string.error_weight_invalid)
                binding.editWeight.requestFocus()
            }
        }
    }

    // ── Navigation & confirmation ─────────────────────────────────────────

    private fun handleBackNavigation() {
        if (viewModel.isDirty.value) {
            showUnsavedChangesDialog()
        } else {
            findNavController().popBackStack()
        }
    }

    private fun showUnsavedChangesDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_unsaved_title)
            .setMessage(R.string.dialog_unsaved_message)
            .setPositiveButton(R.string.btn_discard) { _, _ ->
                backPressedCallback.isEnabled = false
                findNavController().popBackStack()
            }
            .setNegativeButton(R.string.btn_keep_editing, null)
            .show()
    }
}
