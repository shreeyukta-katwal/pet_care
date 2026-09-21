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
import com.petcare.app.databinding.FragmentLoginBinding
import kotlinx.coroutines.launch

/**
 * LoginFragment – Handles user authentication.
 *
 * Requirements fulfilled:
 * - Email and password input fields with error display via [com.google.android.material.textfield.TextInputLayout].
 * - "Remember me" checkbox to choose between persistent storage (survives app restarts)
 *   and in-memory session (session only for current app process).
 * - Incorrect credentials show a generic message to prevent account enumeration.
 * - Password verification is executed off the main thread with cryptographic hash checks.
 * - Disables inputs and button during loading with a progress indicator.
 * - On success, navigates to Pet List and pops the authentication screens off the back stack.
 * - Never logs or persists plain-text passwords.
 * - All text and checkbox states survive device rotation.
 */
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LoginViewModel by viewModels {
        val app = requireActivity().application as PetCareApp
        LoginViewModelFactory(
            userRepository = app.container.userRepository,
            sessionManager = app.container.sessionManager
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
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
     * Attaches text and checkbox change listeners.
     */
    private fun setupInputWatchers() {
        binding.etEmail.doAfterTextChanged { text ->
            viewModel.onEmailChanged(text?.toString().orEmpty())
        }

        binding.etPassword.doAfterTextChanged {
            viewModel.onPasswordChanged()
        }

        binding.cbRememberMe.setOnCheckedChangeListener { _, isChecked ->
            viewModel.onRememberMeChanged(isChecked)
        }
    }

    /**
     * Attaches focus loss listeners to trigger instant validation on blur.
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
    }

    /**
     * Configures button clicks and IME keyboard actions.
     */
    private fun setupActions() {
        // Submit on button tap
        binding.btnLogin.setOnClickListener {
            submitLogin()
        }

        // Submit on soft keyboard Done action
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitLogin()
                true
            } else {
                false
            }
        }

        // Navigate to Signup screen
        binding.btnSignupLink.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.loginFragment) {
                findNavController().navigate(R.id.action_login_to_signup)
            }
        }
    }

    /**
     * Triggers credential verification in [LoginViewModel].
     */
    private fun submitLogin() {
        val password = binding.etPassword.text?.toString().orEmpty()
        viewModel.login(password)
    }

    /**
     * Observes UI state updates lifecycle-safely.
     */
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 1. Error mapping
                    binding.tilEmail.error = state.emailError?.let { getString(it) }
                    binding.tilPassword.error = state.passwordError?.let { getString(it) }

                    // 2. Remember-me checkbox synchronization
                    if (binding.cbRememberMe.isChecked != state.rememberMe) {
                        binding.cbRememberMe.isChecked = state.rememberMe
                    }

                    // 3. Loading state and interactivity
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.btnLogin.isEnabled = !state.isLoading
                    binding.etEmail.isEnabled = !state.isLoading
                    binding.etPassword.isEnabled = !state.isLoading
                    binding.cbRememberMe.isEnabled = !state.isLoading

                    // 4. Navigation event on success
                    if (state.navigateToPetList) {
                        viewModel.onNavigated()
                        if (findNavController().currentDestination?.id == R.id.loginFragment) {
                            findNavController().navigate(R.id.action_login_to_petList)
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
