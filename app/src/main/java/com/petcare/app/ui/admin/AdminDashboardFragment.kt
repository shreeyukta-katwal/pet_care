package com.petcare.app.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.petcare.app.PetCareApp
import com.petcare.app.R
import com.petcare.app.databinding.FragmentAdminDashboardBinding
import kotlinx.coroutines.launch

/** Privileged dashboard; it defensively returns non-administrators to the pet list. */
class AdminDashboardFragment : Fragment() {
    private var _binding: FragmentAdminDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AdminDashboardViewModel by viewModels {
        val container = (requireActivity().application as PetCareApp).container
        AdminDashboardViewModel.Factory(container.sessionManager.getUserId(), container.userRepository, container.adminRepository)
    }
    private val adapter = AdminUserAdapter(
        currentUserId = { (requireActivity().application as PetCareApp).container.sessionManager.getUserId() },
        onViewPets = { userId -> findNavController().navigate(R.id.action_adminDashboard_to_adminUserDetail, Bundle().apply { putLong("userId", userId) }) },
        onResetPassword = { user -> confirmPasswordReset(user.user.id) },
        onDelete = { user -> confirmDelete(user.user.id) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentAdminDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.buttonBack.setOnClickListener { findNavController().navigateUp() }
        // Make the device Back button match the visible Up-arrow behaviour.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    findNavController().navigateUp()
                }
            }
        )
        binding.recyclerUsers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerUsers.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { ui ->
                    if (ui.authorized == false) {
                        Snackbar.make(binding.root, R.string.admin_not_authorised, Snackbar.LENGTH_SHORT).show()
                        findNavController().popBackStack()
                    }
                    ui.stats?.let {
                        binding.statUsers.text = it.totalUsers.toString()
                        binding.statPets.text = it.totalPets.toString()
                        binding.statTasks.text = it.totalTasks.toString()
                        binding.statDoneToday.text = it.tasksCompletedToday.toString()
                        val completionPercent = if (it.totalTasks == 0) 0 else
                            (it.tasksCompletedToday * 100 / it.totalTasks).coerceIn(0, 100)
                        binding.progressCompletion.progress = completionPercent
                        binding.textCompletionPercent.text =
                            getString(R.string.admin_completion_percent, completionPercent)
                    }
                    adapter.submitList(ui.users)
                    binding.textEmpty.visibility = if (ui.authorized == true && ui.users.isEmpty()) View.VISIBLE else View.GONE
                    if (ui.deleted) {
                        Snackbar.make(binding.root, R.string.admin_account_deleted, Snackbar.LENGTH_SHORT).show()
                        viewModel.deletionHandled()
                    }
                    ui.resetPassword?.let { password ->
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.admin_password_reset_complete_title)
                            .setMessage(getString(R.string.admin_password_reset_complete_message, password))
                            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.resetPasswordHandled() }
                            .show()
                    }
                }
            }
        }
    }

    private fun confirmDelete(userId: Long) {
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_PetCare_MaterialAlertDialog_Destructive)
            .setTitle(R.string.admin_delete_title)
            .setMessage(R.string.admin_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ -> viewModel.deleteUser(userId) }
            .show()
    }

    private fun confirmPasswordReset(userId: Long) {
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_PetCare_MaterialAlertDialog_Destructive)
            .setTitle(R.string.admin_reset_password)
            .setMessage(R.string.admin_password_reset_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.admin_reset_password) { _, _ -> viewModel.resetPassword(userId) }
            .show()
    }

    override fun onDestroyView() { binding.recyclerUsers.adapter = null; _binding = null; super.onDestroyView() }
}
