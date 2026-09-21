package com.petcare.app.ui.delegate

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.petcare.app.PetCareApp
import com.petcare.app.R
import com.petcare.app.databinding.FragmentDelegateBinding
import com.petcare.app.ui.checklist.ChecklistFilterMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DelegateFragment – SMS delegation screen for pet care routines.
 *
 * ## Architectural Trade-off Justification: Direct SMS vs Intent Fallback
 * -------------------------------------------------------------------------
 * This screen implements BOTH direct SMS transmission and external messaging app hand-off:
 *
 * 1. **Direct Send (`SmsManager.sendMultipartTextMessage`)**:
 *    - **UX Advantage**: Sends the care plan directly without leaving the app, providing
 *      an integrated experience and delivery status feedback via broadcast callbacks.
 *    - **Google Play Policy Limitation**: `SEND_SMS` is classified by Google as a "High-Risk /
 *      Sensitive" permission. Per Google Play Store policy, apps requesting `SEND_SMS` must be
 *      registered as the device's default SMS handler (with very narrow exceptions for enterprise
 *      device managers). A consumer pet care app submitting with `SEND_SMS` would be rejected
 *      during store review.
 *    - **Educational Purpose**: Implemented here to demonstrate complete runtime permission
 *      lifecycle management: pre-request rationale dialogs, granular permissions, permanent denial
 *      handling with settings redirection, multi-part SMS splitting, and telephony hardware checks.
 *
 * 2. **External Messaging App (`Intent.ACTION_SENDTO` with `smsto:<number>`)**:
 *    - **Store Policy Advantage**: Completely permission-free. Perfectly compliant with Google Play
 *      for all app categories.
 *    - **User Advantage**: Respects user's messaging client preference (Google Messages, Signal, etc.),
 *      RCS chat features, MMS support, and dual-SIM selection.
 *
 * 3. **Non-Telephony Devices (Tablets)**:
 *    - Automatically checks [PackageManager.FEATURE_TELEPHONY]. On devices without cellular radios,
 *      direct SMS is hidden and the app seamlessly offers the messaging app or universal share sheet.
 */
class DelegateFragment : Fragment() {

    private var _binding: FragmentDelegateBinding? = null
    private val binding get() = _binding!!

    private val args: DelegateFragmentArgs by navArgs()

    private val viewModel: DelegateViewModel by viewModels {
        val app = requireActivity().application as PetCareApp
        val container = app.container
        DelegateViewModel.Factory(
            petId = args.petId,
            petRepository = container.petRepository,
            taskRepository = container.taskRepository,
            userRepository = container.userRepository,
            sessionManager = container.sessionManager
        )
    }

    private lateinit var taskAdapter: DelegateTaskAdapter

    /** Registered broadcast receiver for SMS delivery callbacks, if any active. */
    private var activeSmsReceiver: BroadcastReceiver? = null

    /** Safety timeout coroutine job to prevent indefinite spinner if cellular stack drops callback. */
    private var smsTimeoutJob: Job? = null

    // ── Activity Result Launchers ─────────────────────────────────────────────

    /**
     * Contact picker launcher using [Intent.ACTION_PICK] on Phone CONTENT_URI.
     * Note: Returns a temporary read grant for the chosen contact record,
     * so NO READ_CONTACTS permission is required.
     */
    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val contactUri = result.data?.data ?: return@registerForActivityResult
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                try {
                    requireContext().contentResolver.query(contactUri, projection, null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val numberIndex =
                                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                val nameIndex =
                                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

                                val phone = if (numberIndex >= 0) cursor.getString(numberIndex) else null
                                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null

                                if (!phone.isNullOrBlank()) {
                                    viewModel.setContact(name ?: "", phone)
                                    binding.editPhone.setText(phone)
                                } else {
                                    showSnackbar(getString(R.string.snackbar_contact_no_number))
                                }
                            }
                        }
                } catch (e: Exception) {
                    showSnackbar(getString(R.string.snackbar_contact_no_number))
                }
            }
        }

    /**
     * Permission launcher for SEND_SMS runtime permission.
     */
    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                confirmAndSendDirectly()
            } else {
                val shouldShowRationale =
                    shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS)
                if (!shouldShowRationale) {
                    // Permanently denied (user checked "Don't ask again")
                    showPermanentDenialDialog()
                } else {
                    showSnackbar(
                        getString(R.string.snackbar_sms_permission_denied),
                        actionText = getString(R.string.btn_open_messaging)
                    ) {
                        openInMessagingApp()
                    }
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDelegateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupInputListeners()
        setupActionButtons()
        observeUiState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterSmsReceiver()
        _binding = null
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        taskAdapter = DelegateTaskAdapter { taskId ->
            viewModel.toggleTaskSelection(taskId)
        }
        binding.recyclerTasks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = taskAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupInputListeners() {
        // Phone number input
        binding.editPhone.doAfterTextChanged { text ->
            viewModel.setRecipientPhone(text?.toString() ?: "")
        }

        // Contact picker button
        binding.btnPickContact.setOnClickListener {
            val pickIntent = Intent(
                Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            )
            try {
                pickContactLauncher.launch(pickIntent)
            } catch (e: ActivityNotFoundException) {
                showSnackbar("No contact app found on device.")
            }
        }

        // Segmented filter toggle
        binding.toggleFilterMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_filter_today -> viewModel.setFilterMode(ChecklistFilterMode.TODAY)
                    R.id.btn_filter_all -> viewModel.setFilterMode(ChecklistFilterMode.ALL_ROUTINES)
                }
            }
        }

        // Select All / Clear selection
        binding.btnSelectAllTasks.setOnClickListener {
            viewModel.selectAllTasks()
        }
        binding.btnClearTasks.setOnClickListener {
            viewModel.clearTaskSelection()
        }

        // Extra instructions
        binding.editExtraInstructions.doAfterTextChanged { text ->
            viewModel.setExtraInstructions(text?.toString() ?: "")
        }

        // Editable message preview
        binding.editMessagePreview.doAfterTextChanged { text ->
            val updated = text?.toString() ?: ""
            val currentState = viewModel.uiState.value
            // Only flag as manual edit if content differs from auto-generated preview
            if (updated != currentState.previewMessage && binding.editMessagePreview.hasFocus()) {
                viewModel.setManualPreview(updated)
            }
        }

        // Reset to default message button
        binding.btnResetMessage.setOnClickListener {
            viewModel.resetToGeneratedMessage()
        }
    }

    private fun setupActionButtons() {
        val hasTelephony =
            requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

        if (!hasTelephony) {
            // Devices without cellular radio (tablets)
            binding.btnSendSms.visibility = View.GONE
            binding.textNoTelephonyHint.visibility = View.VISIBLE
        } else {
            binding.btnSendSms.visibility = View.VISIBLE
            binding.textNoTelephonyHint.visibility = View.GONE
        }

        // Primary: Send SMS directly
        binding.btnSendSms.setOnClickListener {
            onSendSmsClicked()
        }

        // Secondary: Open in messaging app (no permission needed)
        binding.btnOpenMessaging.setOnClickListener {
            openInMessagingApp()
        }

        // Tertiary: Share care plan
        binding.btnSharePlan.setOnClickListener {
            shareCarePlan()
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }
    }

    private fun renderUiState(state: DelegateUiState) {
        // Subtitle banner with pet name
        val petName = state.pet?.name ?: "your pet"
        binding.textSubtitle.text = getString(R.string.delegate_subtitle, petName)

        // Contact name indicator
        if (!state.contactName.isNullOrBlank()) {
            binding.textContactName.visibility = View.VISIBLE
            binding.textContactName.text =
                getString(R.string.contact_selected_prefix, state.contactName)
        } else {
            binding.textContactName.visibility = View.GONE
        }

        // Phone input text (keep in sync if modified from contact picker)
        if (binding.editPhone.text?.toString() != state.recipientPhone && !binding.editPhone.hasFocus()) {
            binding.editPhone.setText(state.recipientPhone)
        }

        // Phone error state
        if (state.recipientPhone.isNotBlank() && !state.isPhoneValid) {
            binding.layoutPhone.error = getString(R.string.error_invalid_phone)
        } else {
            binding.layoutPhone.error = null
        }

        // Tasks list
        if (state.displayTasks.isEmpty()) {
            binding.recyclerTasks.visibility = View.GONE
            binding.textEmptyTasks.visibility = View.VISIBLE
        } else {
            binding.recyclerTasks.visibility = View.VISIBLE
            binding.textEmptyTasks.visibility = View.GONE
            taskAdapter.submitList(state.displayTasks)
            taskAdapter.updateSelectedIds(state.selectedTaskIds)
        }

        // Extra instructions
        if (binding.editExtraInstructions.text?.toString() != state.extraInstructions &&
            !binding.editExtraInstructions.hasFocus()
        ) {
            binding.editExtraInstructions.setText(state.extraInstructions)
        }

        // Message preview text
        if (binding.editMessagePreview.text?.toString() != state.previewMessage &&
            !binding.editMessagePreview.hasFocus()
        ) {
            binding.editMessagePreview.setText(state.previewMessage)
        }

        // Reset to default button visibility
        binding.btnResetMessage.visibility = if (state.isManualEdit) View.VISIBLE else View.GONE

        // Live counter
        val encodingLabel = if (state.isGsm7Bit) "GSM 7-bit" else "UCS-2 Unicode"
        binding.textSmsCounter.text = getString(
            R.string.counter_sms_format,
            state.charCount,
            state.smsPartsCount,
            encodingLabel
        )

        // Encoding warning
        binding.textEncodingWarning.visibility =
            if (!state.isGsm7Bit) View.VISIBLE else View.GONE

        // Buttons enabled state
        binding.btnSendSms.isEnabled = state.canSend
        binding.btnOpenMessaging.isEnabled = state.canSend

        // Progress bar
        binding.progressSending.visibility =
            if (state.isSending) View.VISIBLE else View.GONE

        // Notification messages (Snackbar)
        state.userMessage?.let { msg ->
            showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    // ── Send SMS Handling ─────────────────────────────────────────────────────

    private fun onSendSmsClicked() {
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            confirmAndSendDirectly()
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS)) {
                showSmsPermissionRationale()
            } else {
                smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
            }
        }
    }

    private fun showSmsPermissionRationale() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_sms_permission_title)
            .setMessage(R.string.dialog_sms_permission_message)
            .setPositiveButton(R.string.btn_allow) { _, _ ->
                smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
            }
            .setNegativeButton(R.string.btn_open_messaging) { _, _ ->
                openInMessagingApp()
            }
            .show()
    }

    private fun showPermanentDenialDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_sms_permission_title)
            .setMessage(R.string.snackbar_sms_permission_permanently_denied)
            .setPositiveButton(R.string.action_open_settings) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)
                )
                startActivity(intent)
            }
            .setNegativeButton(R.string.btn_open_messaging) { _, _ ->
                openInMessagingApp()
            }
            .show()
    }

    private fun confirmAndSendDirectly() {
        val state = viewModel.uiState.value
        val recipientLabel = state.contactName ?: state.recipientPhone
        val petName = state.pet?.name ?: "your pet"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_confirm_send_title)
            .setMessage(
                getString(
                    R.string.dialog_confirm_send_message,
                    petName,
                    recipientLabel
                )
            )
            .setPositiveButton(R.string.btn_send) { _, _ ->
                executeDirectSmsSend(
                    phone = CarePlanMessageBuilder.cleanPhoneNumber(state.recipientPhone),
                    message = state.previewMessage,
                    recipientDisplay = recipientLabel
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Sends the care plan directly using [SmsManager.sendMultipartTextMessage].
     * Registers a local broadcast receiver to listen for transmission outcome.
     */
    private fun executeDirectSmsSend(
        phone: String,
        message: String,
        recipientDisplay: String
    ) {
        viewModel.setSending(true)

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requireContext().getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        val parts = smsManager.divideMessage(message)
        val actionSent = "com.petcare.app.SMS_SENT_${System.currentTimeMillis()}"

        unregisterSmsReceiver()

        val receiver = object : BroadcastReceiver() {
            private var partsRemaining = parts.size
            private var hasReportedError = false

            override fun onReceive(context: Context?, intent: Intent?) {
                partsRemaining--
                val resultCode = resultCode
                if (resultCode != Activity.RESULT_OK && !hasReportedError) {
                    hasReportedError = true
                    cancelSmsTimeout()
                    val errorDesc = when (resultCode) {
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> getString(R.string.reason_generic_failure)
                        SmsManager.RESULT_ERROR_NO_SERVICE -> getString(R.string.reason_no_service)
                        SmsManager.RESULT_ERROR_RADIO_OFF -> getString(R.string.reason_radio_off)
                        SmsManager.RESULT_ERROR_NULL_PDU -> getString(R.string.reason_null_pdu)
                        else -> "Code $resultCode"
                    }
                    viewModel.onSendCompleted(
                        success = false,
                        message = getString(R.string.snackbar_sms_failed, errorDesc)
                    )
                    unregisterSmsReceiver()
                } else if (partsRemaining <= 0 && !hasReportedError) {
                    cancelSmsTimeout()
                    viewModel.onSendCompleted(
                        success = true,
                        message = getString(R.string.snackbar_sms_sent, recipientDisplay)
                    )
                    unregisterSmsReceiver()
                }
            }
        }
        activeSmsReceiver = receiver

        // Register receiver with appropriate export flag
        val filter = IntentFilter(actionSent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                requireContext(),
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(receiver, filter)
        }

        val sentIntents = ArrayList<PendingIntent>()
        for (i in parts.indices) {
            val sentIntent = PendingIntent.getBroadcast(
                requireContext(),
                i,
                Intent(actionSent),
                PendingIntent.FLAG_IMMUTABLE
            )
            sentIntents.add(sentIntent)
        }

        // Safety timeout: if cellular carrier does not return a callback within 10s, cancel loading
        smsTimeoutJob?.cancel()
        smsTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(10_000)
            if (activeSmsReceiver != null) {
                unregisterSmsReceiver()
                viewModel.onSendCompleted(
                    success = false,
                    message = getString(R.string.snackbar_sms_timeout)
                )
            }
        }

        try {
            smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
        } catch (e: Exception) {
            cancelSmsTimeout()
            unregisterSmsReceiver()
            viewModel.onSendCompleted(
                success = false,
                message = getString(R.string.snackbar_sms_failed, e.localizedMessage ?: "Exception")
            )
        }
    }

    private fun cancelSmsTimeout() {
        smsTimeoutJob?.cancel()
        smsTimeoutJob = null
    }

    private fun unregisterSmsReceiver() {
        cancelSmsTimeout()
        activeSmsReceiver?.let {
            try {
                requireContext().unregisterReceiver(it)
            } catch (ignored: IllegalArgumentException) {
                // Already unregistered
            }
            activeSmsReceiver = null
        }
    }

    // ── Intent Fallback Actions ───────────────────────────────────────────────

    /**
     * Opens the device's default messaging application with the recipient and message prefilled.
     * Requires ZERO permissions and is 100% compliant with Google Play Store policies.
     */
    private fun openInMessagingApp() {
        val state = viewModel.uiState.value
        val cleanNumber = CarePlanMessageBuilder.cleanPhoneNumber(state.recipientPhone)
        val uri = Uri.parse("smsto:$cleanNumber")

        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", state.previewMessage)
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            shareCarePlan()
        }
    }

    /**
     * Generic share sheet fallback using [Intent.ACTION_SEND].
     * Useful for Wi-Fi only tablets, messaging apps without smsto: filter, or emailing.
     */
    private fun shareCarePlan() {
        val state = viewModel.uiState.value
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, state.previewMessage)
            putExtra(Intent.EXTRA_SUBJECT, "PetCare Care Plan")
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_share_plan)))
    }

    private fun showSnackbar(
        message: String,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        if (actionText != null && action != null) {
            snackbar.setAction(actionText) { action() }
        }
        snackbar.show()
    }
}
