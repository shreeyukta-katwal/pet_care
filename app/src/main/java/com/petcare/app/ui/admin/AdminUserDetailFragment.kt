package com.petcare.app.ui.admin

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
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.petcare.app.PetCareApp
import com.petcare.app.R
import com.petcare.app.databinding.FragmentAdminUserDetailBinding
import kotlinx.coroutines.launch

/** Read-only administrator view of a selected user's pets and care-task statuses. */
class AdminUserDetailFragment : Fragment() {
    private var _binding: FragmentAdminUserDetailBinding? = null
    private val binding get() = _binding!!
    private val args: AdminUserDetailFragmentArgs by navArgs()
    private val viewModel: AdminUserDetailViewModel by viewModels {
        val container = (requireActivity().application as PetCareApp).container
        AdminUserDetailViewModel.Factory(container.sessionManager.getUserId(), args.userId, container.userRepository, container.adminRepository)
    }
    private val adapter = AdminUserDetailAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentAdminUserDetailBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.buttonBack.setOnClickListener { findNavController().navigateUp() }
        binding.recyclerPets.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPets.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.uiState.collect { ui ->
                if (ui.authorized == false) {
                    Snackbar.make(binding.root, R.string.admin_not_authorised, Snackbar.LENGTH_SHORT).show()
                    findNavController().popBackStack(R.id.petListFragment, false)
                }
                // Keep long account addresses readable in the dedicated identity card.
                if (ui.targetUserEmail != null) {
                    binding.textAccountEmail.text = ui.targetUserEmail
                }
                adapter.submitList(ui.pets)
                binding.empty.visibility = if (ui.authorized == true && ui.pets.isEmpty()) View.VISIBLE else View.GONE
            }
        } }
    }
    override fun onDestroyView() { binding.recyclerPets.adapter = null; _binding = null; super.onDestroyView() }
}
