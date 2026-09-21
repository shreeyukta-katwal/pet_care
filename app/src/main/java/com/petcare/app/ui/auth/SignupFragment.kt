package com.petcare.app.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.petcare.app.PetCareApp
import com.petcare.app.R
import com.petcare.app.databinding.FragmentSignupBinding
import kotlinx.coroutines.launch

/**
 * SignupFragment – Handles new user account registration.
 *
 * Requirements fulfilled:
 * - Validates inputs (email format, password length/complexity, confirm-password matching, duplicate email).
 * - Displays inline error messages via [com.google.android.material.textfield.TextInputLayout].
 * - Clears each field's error immediately when edited.
 * - Validates on submit and on focus loss (blur).
 * - Disables submit button and shows progress indicator during cryptographic hashing and DB insertion.
 * - Cryptographically hashes passwords off main thread (PBKDF2WithHmacSHA256, 120000 iterations, 256-bit key).
 * - Saves persistent session on success and clears authentication back-stack on transition to Pet List.
 * - Responsive layout for phones and tablets, surviving orientation changes.
 */
class SignupFragment : Fragment() {

    private var _binding: FragmentSignupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SignupViewModel by viewModels {
        val app = requireActivity().application as PetCareApp
        SignupViewModelFactory(
            userRepository = app.container.userRepository,
            sessionManager = app.container.sessionManager
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupInputWatchers()
        setupFocusListeners()
        setupActions()
        observeState()
    }

    /**
     * Attaches text watchers to clear field errors as soon as the user edits them.
     */
    private fun setupInputWatchers() {
        binding.etEmail.doAfterTextChanged { text ->
            viewModel.onEmailChanged(text?.toString().orEmpty())
        }

        binding.etPassword.doAfterTextChanged {
            viewModel.onPasswordChanged()
        }

        binding.etConfirmPassword.doAfterTextChanged {
            viewModel.onConfirmPasswordChanged()
        }
    }

    /**
     * Attaches focus change listeners to validate fields upon focus loss (blur).
     */
    private fun setupFocusListeners() {
        binding.etEmail.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                viewModel.onEmailFocusLost()
            }
        }

        binding.etPassword.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                viewModel.onPasswordFocusLost(binding.etPassword.text?.toString().orEmpty())
            }
        }

        binding.etConfirmPassword.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                viewModel.onConfirmPasswordFocusLost(
                    password = binding.etPassword.text?.toString().orEmpty(),
                    confirm = binding.etConfirmPassword.text?.toString().orEmpty()
                )
            }
        }
    }

    /**
     * Configures button clicks and IME keyboard actions.
     */
    private fun setupActions() {
        // Submit on button tap
        binding.btnSignup.setOnClickListener {
            submitRegistration()
        }

        // Submit on soft keyboard Done action
        binding.etConfirmPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitRegistration()
                true
            } else {
                false
            }
        }

        // Navigate to Login screen
        binding.btnLoginLink.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.signupFragment) {
                findNavController().navigate(R.id.action_signup_to_login)
            }
        }
    }

    /**
     * Triggers the registration pipeline in [SignupViewModel].
     */
    private fun submitRegistration() {
        val password = binding.etPassword.text?.toString().orEmpty()
        val confirm = binding.etConfirmPassword.text?.toString().orEmpty()
        viewModel.register(password, confirm)
    }

    /**
     * Observes the UI state and updates views accordingly.
     */
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 1. Email error mapping
                    binding.tilEmail.error = state.emailError?.let { getString(it) }

                    // 2. Password error mapping
                    binding.tilPassword.error = state.passwordError?.let { getString(it) }

                    // 3. Confirm password error mapping
                    binding.tilConfirmPassword.error = state.confirmPasswordError?.let { getString(it) }

                    // 4. Loading state indicator and button interactivity
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.btnSignup.isEnabled = !state.isLoading
                    binding.etEmail.isEnabled = !state.isLoading
                    binding.etPassword.isEnabled = !state.isLoading
                    binding.etConfirmPassword.isEnabled = !state.isLoading

                    // 5. Navigation event on success
                    if (state.navigateToPetList) {
                        viewModel.onNavigated()
                        if (findNavController().currentDestination?.id == R.id.signupFragment) {
                            findNavController().navigate(R.id.action_signup_to_petList)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
