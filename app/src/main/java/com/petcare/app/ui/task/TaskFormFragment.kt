package com.petcare.app.ui.task
 
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.petcare.app.PetCareApp
import com.petcare.app.R
import com.petcare.app.data.db.PetEntity
import com.petcare.app.data.db.TaskCategory
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.db.TaskFrequency
import com.petcare.app.databinding.FragmentTaskFormBinding
import com.petcare.app.databinding.ItemCategoryDropdownBinding
import com.petcare.app.databinding.ItemPetDropdownBinding
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * TaskFormFragment – Unified Add / Edit Care Routine screen.
 */
class TaskFormFragment : Fragment() {

    companion object {
        private const val PREFS_NAME = "pet_care_notification_prefs"
        private const val PREF_KEY_NOTIFICATION_ASKED = "notification_permission_asked"
    }

    // ── View binding ──────────────────────────────────────────────────────
    private var _binding: FragmentTaskFormBinding? = null
    private val binding get() = _binding!!

    // ── Navigation arguments ──────────────────────────────────────────────
    private val args: TaskFormFragmentArgs by navArgs()

    // ── Permission Launcher ───────────────────────────────────────────────
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Continue with saving regardless of permission outcome
        executeSave()

        if (!isGranted) {
            // Non-blocking notification explaining reminders won't show with settings shortcut
            Snackbar.make(
                binding.root,
                R.string.snackbar_notifications_disabled,
                Snackbar.LENGTH_LONG
            ).setAction(R.string.action_open_settings) {
                try {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", requireContext().packageName, null)
                    }
                    startActivity(intent)
                }
            }.show()
        }
    }

    // ── ViewModel ─────────────────────────────────────────────────────────
    private val viewModel: TaskFormViewModel by viewModels {
        val app = requireActivity().application as PetCareApp
        val userId = app.container.sessionManager.getUserId()
            ?: error("TaskFormFragment reached without active session")
        TaskFormViewModel.Factory(
            taskId = args.taskId,
            preselectedPetId = args.preselectedPetId,
            userId = userId,
            taskRepository = app.container.taskRepository,
            petRepository = app.container.petRepository,
            reminderScheduler = app.container.reminderScheduler
        )
    }

    private var petList: List<PetEntity> = emptyList()
    private var selectedCategory: TaskCategory? = null

    // ── Back-press callback ───────────────────────────────────────────────
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showUnsavedChangesDialog()
        }
    }

    // ── Lifecycle overrides ───────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTaskFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            backPressedCallback
        )

        setupWindowInsets()
        setupToolbar()
        setupCategoryDropdown()
        setupFrequencyToggle()
        setupDayChips()
        setupTimePicker()
        setupReminderCard()
        setupTextWatchers()
        setupSaveButton()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── UI Setup Helpers ──────────────────────────────────────────────────

    /**
     * Handles keyboard and system bar insets so bottom content and Save button are never obscured.
     */
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollView) { v, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    private fun setupToolbar() {
        binding.toolbar.title = if (viewModel.isEditMode) {
            getString(R.string.title_edit_task)
        } else {
            getString(R.string.title_add_task)
        }
        binding.toolbar.setNavigationOnClickListener {
            handleBackNavigation()
        }
    }

    /**
     * Creates a circular avatar drawable for the selected pet (or placeholder).
     * Disables any start icon tint so photos are not tinted.
     */
    private fun getPetAvatarDrawable(photoUriString: String?, sizeDp: Int): Drawable {
        if (!photoUriString.isNullOrBlank()) {
            try {
                val uri = Uri.parse(photoUriString)
                val density = resources.displayMetrics.density
                val targetPx = (sizeDp * density).toInt().coerceAtLeast(1)

                // Decode bounds first to downsample efficiently
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, boundsOptions)
                }

                val sampleSize = calculateInSampleSize(boundsOptions, targetPx, targetPx)
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                val rawBitmap = requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }

                if (rawBitmap != null) {
                    // Center-crop to a square
                    val minDim = minOf(rawBitmap.width, rawBitmap.height)
                    val cropX = (rawBitmap.width - minDim) / 2
                    val cropY = (rawBitmap.height - minDim) / 2
                    val squareBitmap = Bitmap.createBitmap(rawBitmap, cropX, cropY, minDim, minDim)
                    if (squareBitmap != rawBitmap) {
                        rawBitmap.recycle()
                    }
                    val scaledBitmap = Bitmap.createScaledBitmap(squareBitmap, targetPx, targetPx, true)
                    if (scaledBitmap != squareBitmap) {
                        squareBitmap.recycle()
                    }
                    return RoundedBitmapDrawableFactory.create(resources, scaledBitmap).apply {
                        isCircular = true
                    }
                }
            } catch (e: Exception) {
                // Fall back to theme-colored placeholder below
            }
        }
        // Theme-colored paw-print placeholder on colorSecondaryContainer background
        return ContextCompat.getDrawable(requireContext(), R.drawable.ic_pet_avatar_placeholder)!!
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Updates the UI elements associated with the selected pet:
     * circular avatar start icon (no tint), text, and toolbar subtitle.
     */
    private fun selectPetUi(pet: PetEntity) {
        val petText = "${pet.name} (${pet.species})"
        binding.dropdownPet.setText(petText, false)
        // Disable icon tint so photo colors are preserved
        binding.layoutPet.setStartIconTintList(null)
        binding.layoutPet.startIconDrawable = getPetAvatarDrawable(pet.photoUri, 28)
        binding.toolbar.subtitle = pet.name
    }

    /**
     * Custom adapter for pet dropdown displaying a 32dp avatar, pet name, and species.
     */
    private inner class PetDropdownAdapter(
        context: Context,
        private val pets: List<PetEntity>
    ) : ArrayAdapter<PetEntity>(context, R.layout.item_pet_dropdown, pets) {

        private val noOpFilter = object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                return FilterResults().apply {
                    values = pets
                    count = pets.size
                }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?): CharSequence {
                val pet = resultValue as? PetEntity ?: return ""
                return "${pet.name} (${pet.species})"
            }
        }

        override fun getFilter(): android.widget.Filter = noOpFilter

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val itemBinding = if (convertView != null) {
                ItemPetDropdownBinding.bind(convertView)
            } else {
                ItemPetDropdownBinding.inflate(LayoutInflater.from(context), parent, false)
            }
            val pet = getItem(position)!!
            itemBinding.textPetName.text = pet.name
            itemBinding.textPetSpecies.text = pet.species
            itemBinding.imagePetAvatar.setImageDrawable(getPetAvatarDrawable(pet.photoUri, 32))
            return itemBinding.root
        }
    }

    /**
     * Configures the pet dropdown and no-pets warning banner.
     */
    private fun setupPetDropdown(pets: List<PetEntity>) {
        petList = pets
        if (pets.isEmpty()) {
            binding.cardNoPetsWarning.visibility = View.VISIBLE
            binding.layoutPet.visibility = View.GONE
            binding.buttonSave.isEnabled = false
            binding.buttonAddPetFirst.setOnClickListener {
                // Navigate to petFormFragment (create mode) directly from taskFormFragment
                findNavController().navigate(R.id.petFormFragment)
            }
            return
        }

        binding.cardNoPetsWarning.visibility = View.GONE
        binding.layoutPet.visibility = View.VISIBLE
        binding.buttonSave.isEnabled = true

        // Ensure icon tint is null so custom photos and vectors render without color distortion
        binding.layoutPet.setStartIconTintList(null)

        val adapter = PetDropdownAdapter(requireContext(), pets)
        binding.dropdownPet.setAdapter(adapter)

        // Select preselected pet or restore selection
        val selectedId = viewModel.getSelectedPetId()
        val defaultPet = pets.find { it.id == selectedId } ?: pets.firstOrNull()
        if (defaultPet != null) {
            selectPetUi(defaultPet)
            viewModel.selectPet(defaultPet.id)
        }

        binding.dropdownPet.setOnItemClickListener { _, _, position, _ ->
            val pet = pets[position]
            selectPetUi(pet)
            viewModel.selectPet(pet.id)
            binding.layoutPet.error = null
        }
    }

    /**
     * Sets up the category ExposedDropdownMenu with custom icons and labels.
     */
    private fun setupCategoryDropdown() {
        val categories = TaskCategory.values()
        val categoryNames = categories.map { it.displayName }

        val adapter = object : ArrayAdapter<TaskCategory>(
            requireContext(),
            R.layout.item_category_dropdown,
            categories
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val itemBinding = if (convertView != null) {
                    ItemCategoryDropdownBinding.bind(convertView)
                } else {
                    ItemCategoryDropdownBinding.inflate(LayoutInflater.from(context), parent, false)
                }
                val cat = getItem(position)!!
                itemBinding.textCategoryName.text = cat.displayName
                itemBinding.imageCategoryIcon.setImageResource(getCategoryIcon(cat))
                return itemBinding.root
            }
        }

        binding.dropdownCategory.setAdapter(adapter)

        // Default to FEEDING for new tasks
        if (!viewModel.isEditMode && selectedCategory == null) {
            selectCategory(TaskCategory.FEEDING)
        }

        binding.dropdownCategory.setOnItemClickListener { _, _, position, _ ->
            val cat = categories[position]
            selectCategory(cat)
            viewModel.markDirty()
        }
    }

    private fun selectCategory(cat: TaskCategory) {
        selectedCategory = cat
        binding.dropdownCategory.setText(cat.displayName, false)
        binding.layoutCategory.startIconDrawable = androidx.core.content.ContextCompat.getDrawable(
            requireContext(),
            getCategoryIcon(cat)
        )
        binding.layoutCategory.error = null

        // Dynamic hint for Healthcare notes
        if (cat == TaskCategory.HEALTHCARE) {
            binding.layoutNotes.hint = getString(R.string.hint_notes_healthcare)
        } else {
            binding.layoutNotes.hint = getString(R.string.hint_task_notes)
        }
    }

    private fun getCategoryIcon(cat: TaskCategory): Int {
        return when (cat) {
            TaskCategory.FEEDING -> R.drawable.ic_cat_feeding
            TaskCategory.EXERCISE -> R.drawable.ic_cat_exercise
            TaskCategory.GROOMING -> R.drawable.ic_cat_grooming
            TaskCategory.MEDICATION -> R.drawable.ic_cat_medication
            TaskCategory.HEALTHCARE -> R.drawable.ic_cat_healthcare
        }
    }

    /**
     * Wires frequency toggle between Daily and Weekly with check icon on selected button.
     */
    private fun setupFrequencyToggle() {
        updateFrequencyIcons(binding.toggleFrequency.checkedButtonId)

        binding.toggleFrequency.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                viewModel.markDirty()
                updateFrequencyIcons(checkedId)
                if (checkedId == R.id.button_freq_weekly) {
                    binding.containerDaysOfWeek.visibility = View.VISIBLE
                } else {
                    binding.containerDaysOfWeek.visibility = View.GONE
                    binding.textDaysError.visibility = View.GONE
                }
            }
        }
    }

    private fun updateFrequencyIcons(checkedId: Int) {
        if (checkedId == R.id.button_freq_weekly) {
            binding.buttonFreqWeekly.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_check)
            binding.buttonFreqDaily.icon = null
        } else {
            binding.buttonFreqDaily.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_check)
            binding.buttonFreqWeekly.icon = null
        }
    }

    /**
     * Listens for chip clicks to clear weekly day errors.
     */
    private fun setupDayChips() {
        val chipIds = listOf(
            R.id.chip_mon, R.id.chip_tue, R.id.chip_wed,
            R.id.chip_thu, R.id.chip_fri, R.id.chip_sat, R.id.chip_sun
        )
        chipIds.forEach { id ->
            binding.root.findViewById<Chip>(id).setOnCheckedChangeListener { _, _ ->
                binding.textDaysError.visibility = View.GONE
                viewModel.markDirty()
            }
        }
    }

    /**
     * Calculates the days of week bitmask from the 7 weekday chips.
     */
    private fun getDaysOfWeekBitmask(): Int {
        var mask = 0
        if (binding.chipMon.isChecked) mask = mask or 1
        if (binding.chipTue.isChecked) mask = mask or 2
        if (binding.chipWed.isChecked) mask = mask or 4
        if (binding.chipThu.isChecked) mask = mask or 8
        if (binding.chipFri.isChecked) mask = mask or 16
        if (binding.chipSat.isChecked) mask = mask or 32
        if (binding.chipSun.isChecked) mask = mask or 64
        return mask
    }

    /**
     * Sets chip checked states from a bitmask.
     */
    private fun setDaysOfWeekFromBitmask(mask: Int) {
        binding.chipMon.isChecked = (mask and 1) != 0
        binding.chipTue.isChecked = (mask and 2) != 0
        binding.chipWed.isChecked = (mask and 4) != 0
        binding.chipThu.isChecked = (mask and 8) != 0
        binding.chipFri.isChecked = (mask and 16) != 0
        binding.chipSat.isChecked = (mask and 32) != 0
        binding.chipSun.isChecked = (mask and 64) != 0
    }

    /**
     * Sets up click listener on the Time input and layout to launch MaterialTimePicker.
     */
    private fun setupTimePicker() {
        binding.editTime.setOnClickListener {
            showTimePicker()
        }
        binding.layoutTime.setOnClickListener {
            showTimePicker()
        }
    }

    private fun formatDisplayTime(hour: Int, minute: Int): String {
        return if (DateFormat.is24HourFormat(requireContext())) {
            String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        } else {
            val amPm = if (hour < 12) "AM" else "PM"
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            String.format(Locale.getDefault(), "%02d:%02d %s", displayHour, minute, amPm)
        }
    }

    private fun showTimePicker() {
        val is24Hour = DateFormat.is24HourFormat(requireContext())
        val clockFormat = if (is24Hour) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H

        val currentTime = viewModel.selectedTime.value ?: Pair(8, 0)

        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(clockFormat)
            .setHour(currentTime.first)
            .setMinute(currentTime.second)
            .setTitleText(R.string.hint_time)
            .build()

        picker.addOnPositiveButtonClickListener {
            viewModel.setTime(picker.hour, picker.minute)
            binding.layoutTime.error = null
        }

        picker.show(childFragmentManager, "TaskTimePicker")
    }

    /**
     * Makes the reminder card row clickable to toggle the switch.
     */
    private fun setupReminderCard() {
        binding.cardReminder.setOnClickListener {
            binding.switchReminder.toggle()
        }
    }

    private fun setupTextWatchers() {
        binding.editName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                binding.layoutName.error = null
                viewModel.markDirty()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val genericWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                viewModel.markDirty()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        binding.editSupplies.addTextChangedListener(genericWatcher)
        binding.editNotes.addTextChangedListener(genericWatcher)
        binding.switchReminder.setOnCheckedChangeListener { _, _ -> viewModel.markDirty() }
    }

    private fun setupSaveButton() {
        binding.buttonSave.text = if (viewModel.isEditMode) {
            getString(R.string.btn_save_changes)
        } else {
            getString(R.string.btn_save_routine)
        }

        binding.buttonSave.setOnClickListener {
            val reminderEnabled = binding.switchReminder.isChecked

            // Android 13+ (API 33+) POST_NOTIFICATIONS permission handling
            if (reminderEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

                val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val alreadyAsked = prefs.getBoolean(PREF_KEY_NOTIFICATION_ASKED, false)

                if (!hasPermission && !alreadyAsked) {
                    prefs.edit().putBoolean(PREF_KEY_NOTIFICATION_ASKED, true).apply()
                    showNotificationPermissionRationale()
                    return@setOnClickListener
                }
            }

            executeSave()
        }
    }

    private fun showNotificationPermissionRationale() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_notification_permission_title)
            .setMessage(R.string.dialog_notification_permission_message)
            .setPositiveButton(R.string.btn_allow) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    executeSave()
                }
            }
            .setNegativeButton(R.string.btn_not_now) { _, _ ->
                executeSave()
            }
            .show()
        }

    private fun executeSave() {
        val freq = if (binding.toggleFrequency.checkedButtonId == R.id.button_freq_weekly) {
            TaskFrequency.WEEKLY
        } else {
            TaskFrequency.DAILY
        }

        viewModel.save(
            name = binding.editName.text?.toString().orEmpty(),
            category = selectedCategory,
            frequency = freq,
            daysOfWeekBitmask = getDaysOfWeekBitmask(),
            supplies = binding.editSupplies.text?.toString().orEmpty(),
            notes = binding.editNotes.text?.toString().orEmpty(),
            reminderEnabled = binding.switchReminder.isChecked
        )
    }

    // ── ViewModel Observation ─────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.availablePets.collect { pets ->
                        setupPetDropdown(pets)
                    }
                }

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
                    viewModel.selectedTime.collect { time ->
                        if (time != null) {
                            binding.editTime.setText(formatDisplayTime(time.first, time.second))
                        }
                    }
                }
            }
        }
    }

    private fun handleState(state: TaskFormState) {
        when (state) {
            is TaskFormState.Loading -> {
                binding.progressIndicator.visibility = View.VISIBLE
                binding.buttonSave.isEnabled = false
            }
            is TaskFormState.Idle -> {
                binding.progressIndicator.visibility = View.GONE
                binding.buttonSave.isEnabled = petList.isNotEmpty()
            }
            is TaskFormState.TaskLoaded -> {
                binding.progressIndicator.visibility = View.GONE
                binding.buttonSave.isEnabled = true
                populateFields(state.task)
                viewModel.resetState()
            }
            is TaskFormState.Saved -> {
                binding.progressIndicator.visibility = View.GONE
                showConfirmationAndExit()
            }
            is TaskFormState.ValidationError -> {
                binding.progressIndicator.visibility = View.GONE
                binding.buttonSave.isEnabled = true
                displayValidationError(state)
                viewModel.resetState()
            }
            is TaskFormState.Error -> {
                binding.progressIndicator.visibility = View.GONE
                binding.buttonSave.isEnabled = true
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun populateFields(task: TaskEntity) {
        binding.editName.setText(task.name)
        selectCategory(task.category)

        if (task.frequency == TaskFrequency.WEEKLY) {
            binding.toggleFrequency.check(R.id.button_freq_weekly)
            updateFrequencyIcons(R.id.button_freq_weekly)
            binding.containerDaysOfWeek.visibility = View.VISIBLE
            setDaysOfWeekFromBitmask(task.daysOfWeek)
        } else {
            binding.toggleFrequency.check(R.id.button_freq_daily)
            updateFrequencyIcons(R.id.button_freq_daily)
            binding.containerDaysOfWeek.visibility = View.GONE
        }

        binding.editSupplies.setText(task.supplies)
        binding.editNotes.setText(task.notes)
        binding.switchReminder.isChecked = task.reminderEnabled

        val pet = petList.find { it.id == task.petId }
        if (pet != null) {
            selectPetUi(pet)
        }
    }

    private fun displayValidationError(error: TaskFormState.ValidationError) {
        when (error.field) {
            TaskFormState.ValidationError.Field.PET -> {
                binding.layoutPet.error = getString(R.string.error_pet_required)
                binding.dropdownPet.requestFocus()
            }
            TaskFormState.ValidationError.Field.NAME -> {
                binding.layoutName.error = getString(R.string.error_task_name_required)
                binding.editName.requestFocus()
            }
            TaskFormState.ValidationError.Field.CATEGORY -> {
                binding.layoutCategory.error = getString(R.string.error_category_required)
                binding.dropdownCategory.requestFocus()
            }
            TaskFormState.ValidationError.Field.TIME -> {
                binding.layoutTime.error = getString(R.string.error_time_required)
                showTimePicker()
            }
            TaskFormState.ValidationError.Field.DAYS_OF_WEEK -> {
                binding.textDaysError.visibility = View.VISIBLE
            }
        }
    }

    private fun showConfirmationAndExit() {
        findNavController().popBackStack()
    }

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
