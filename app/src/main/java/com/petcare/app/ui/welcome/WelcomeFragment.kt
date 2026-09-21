package com.petcare.app.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.petcare.app.PetCareApp
import com.petcare.app.R
import com.petcare.app.databinding.FragmentWelcomeBinding
import kotlinx.coroutines.launch

/**
 * WelcomeFragment – the initial landing and authentication gateway of PetCare.
 *
 * Responsibilities:
 * - Checks for an active, valid user session upon entry without UI flickering.
 * - If already logged in and the user exists in the local database, immediately
 *   navigates to the Pet List screen and pops Welcome off the back-stack.
 * - If not logged in (or session was invalid), displays the brand logo, app tagline,
 *   a filled 'Log In' button, and an outlined 'Sign Up' button.
 * - Handles navigation to the Login and Sign-up screens via Navigation Component.
 * - Layout is fully responsive for phones and tablets, constrained to 480dp width,
 *   and survives device orientation changes seamlessly via [WelcomeViewModel].
 */
class WelcomeFragment : Fragment() {

    /**
     * ViewBinding instance for [fragment_welcome.xml].
     * Null outside [onCreateView] - [onDestroyView] lifecycle window to prevent leaks.
     */
    private var _binding: FragmentWelcomeBinding? = null

    /** Non-null binding accessor, valid only between onCreateView and onDestroyView. */
    private val binding get() = _binding!!

    /**
     * ViewModel providing auto-skip session verification and UI state coordination.
     * Injected with SessionManager and UserRepository from [PetCareApp.container].
     */
    private val viewModel: WelcomeViewModel by viewModels {
        val app = requireActivity().application as PetCareApp
        WelcomeViewModelFactory(
            sessionManager = app.container.sessionManager,
            userRepository = app.container.userRepository
        )
    }

    /**
     * Inflates the layout hierarchy using ViewBinding.
     *
     * @param inflater           The LayoutInflater object that can be used to inflate views.
     * @param container          The parent view that the fragment's UI should be attached to.
     * @param savedInstanceState Previous state if reconstructed.
     * @return The root view of [FragmentWelcomeBinding].
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * Called immediately after [onCreateView] has returned.
     * Sets up click listeners and collects [WelcomeViewModel.uiState].
     *
     * @param view               The View returned by [onCreateView].
     * @param savedInstanceState Previous state if reconstructed.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupButtons()
        observeState()
    }

    /**
     * Sets up click listeners for the Login and Sign-up navigation buttons.
     * Guards navigation by ensuring the current destination is still [R.id.welcomeFragment].
     */
    private fun setupButtons() {
        // Navigate forward to Login screen
        binding.btnLogin.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.welcomeFragment) {
                findNavController().navigate(R.id.action_welcome_to_login)
            }
        }

        // Navigate forward to Sign-up screen
        binding.btnSignup.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.welcomeFragment) {
                findNavController().navigate(R.id.action_welcome_to_signup)
            }
        }
    }

    /**
     * Observes [WelcomeViewModel.uiState] lifecycle-safely using [repeatOnLifecycle].
     *
     * - [WelcomeUiState.NavigateToPetList]: Navigates directly to the pet list, removing
     *   the welcome fragment from the back-stack so Back exits the application.
     * - [WelcomeUiState.ShowWelcome]: Reveals the welcome details (title, tagline, buttons)
     *   smoothly with a 200ms fade-in transition.
     * - [WelcomeUiState.Loading]: Keeps welcome details invisible so only the centered logo
     *   is shown (splash effect) to prevent any UI flickering.
     */
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is WelcomeUiState.Loading -> {
                            // Only the centered logo is shown; details are hidden
                            binding.welcomeDetailsContainer.visibility = View.INVISIBLE
                            binding.welcomeDetailsContainer.alpha = 0f
                        }

                        is WelcomeUiState.NavigateToPetList -> {
                            // User is already signed in; jump to Pet List and clear back-stack
                            if (findNavController().currentDestination?.id == R.id.welcomeFragment) {
                                findNavController().navigate(R.id.action_welcome_to_petList)
                            }
                        }

                        is WelcomeUiState.ShowWelcome -> {
                            // No valid session; fade in the welcome options
                            binding.welcomeDetailsContainer.visibility = View.VISIBLE
                            binding.welcomeDetailsContainer.animate()
                                .alpha(1f)
                                .setDuration(200)
                                .start()
                        }
                    }
                }
            }
        }
    }

    /**
     * Clears the ViewBinding reference when the fragment view is destroyed.
     * Prevents memory leaks caused by references to detached views.
     */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
