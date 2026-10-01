package com.petcare.app.ui.profile

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.petcare.app.PetCareApp
import com.petcare.app.R
import com.petcare.app.data.db.UserEntity
import com.petcare.app.data.security.PasswordHasher
import com.petcare.app.data.session.SessionManager
import com.petcare.app.databinding.FragmentProfileBinding
import com.petcare.app.util.PhotoStorageHelper
import kotlinx.coroutines.launch

/** Lets the signed-in user manage their account name, photo, and password. */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private var currentUser: UserEntity? = null
    private var busy = false

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) savePhoto(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.buttonChangePhoto.setOnClickListener {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.buttonRemovePhoto.setOnClickListener { removePhoto() }
        binding.buttonSaveProfile.setOnClickListener { saveName() }
        binding.buttonChangePassword.setOnClickListener { changePassword() }
        loadProfile()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Reload from Room so changes to the password never overwrite newer profile fields. */
    private fun loadProfile() {
        val app = requireActivity().application as PetCareApp
        val userId = app.container.sessionManager.getUserId()
        if (userId == SessionManager.NO_USER) {
            findNavController().navigateUp()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val user = app.container.userRepository.findById(userId)
                if (user == null) {
                    showMessage(R.string.profile_load_failed)
                    return@launch
                }
                currentUser = user
                binding.editName.setText(user.displayName.ifBlank { suggestedName(user.email) })
                binding.editEmail.setText(user.email)
                showPhoto(user.photoUri)
            } catch (_: Exception) {
                showMessage(R.string.profile_load_failed)
            }
        }
    }

    private fun suggestedName(email: String): String =
        email.substringBefore('@').replaceFirstChar { it.titlecase() }

    private fun showPhoto(uriString: String?) {
        if (uriString.isNullOrBlank()) {
            binding.imageProfile.setImageResource(R.drawable.ic_person_24)
            binding.imageProfile.setPadding(28.dp, 28.dp, 28.dp, 28.dp)
            binding.buttonRemovePhoto.visibility = View.GONE
        } else {
            binding.imageProfile.setPadding(0, 0, 0, 0)
            binding.imageProfile.setImageURI(Uri.parse(uriString))
            binding.buttonRemovePhoto.visibility = View.VISIBLE
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun setBusy(value: Boolean) {
        busy = value
        binding.buttonSaveProfile.isEnabled = !value
        binding.buttonChangePassword.isEnabled = !value
        binding.buttonChangePhoto.isEnabled = !value
        binding.buttonRemovePhoto.isEnabled = !value
    }

    private fun saveName() {
        if (busy) return
        val name = binding.editName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            binding.layoutName.error = getString(R.string.profile_name_required)
            return
        }
        binding.layoutName.error = null
        val app = requireActivity().application as PetCareApp
        val userId = currentUser?.id ?: return
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val latest = app.container.userRepository.findById(userId) ?: error("Account missing")
                val updated = latest.copy(displayName = name)
                app.container.userRepository.update(updated)
                currentUser = updated
                showMessage(R.string.profile_saved)
            } catch (_: Exception) {
                showMessage(R.string.profile_save_failed)
            } finally {
                if (_binding != null) setBusy(false)
            }
        }
    }

    private fun changePassword() {
        if (busy) return
        val current = binding.editCurrentPassword.text?.toString().orEmpty()
        val new = binding.editNewPassword.text?.toString().orEmpty()
        val confirm = binding.editConfirmPassword.text?.toString().orEmpty()
        binding.layoutCurrentPassword.error = null
        binding.layoutNewPassword.error = null
        binding.layoutConfirmPassword.error = null
        when {
            current.isBlank() -> {
                binding.layoutCurrentPassword.error = getString(R.string.profile_password_required)
                return
            }
            new.length < 8 -> {
                binding.layoutNewPassword.error = getString(R.string.error_password_length)
                return
            }
            !new.any(Char::isLetter) || !new.any(Char::isDigit) -> {
                binding.layoutNewPassword.error = getString(R.string.error_password_complexity)
                return
            }
            new != confirm -> {
                binding.layoutConfirmPassword.error = getString(R.string.profile_password_mismatch)
                return
            }
        }
        val app = requireActivity().application as PetCareApp
        val userId = currentUser?.id ?: return
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val latest = app.container.userRepository.findById(userId) ?: error("Account missing")
                if (!PasswordHasher.verify(current, latest.passwordHash, latest.salt)) {
                    binding.layoutCurrentPassword.error = getString(R.string.profile_current_password_wrong)
                    return@launch
                }
                val hash = PasswordHasher.hash(new)
                val updated = latest.copy(passwordHash = hash.hashBase64, salt = hash.saltBase64)
                app.container.userRepository.update(updated)
                currentUser = updated
                binding.editCurrentPassword.text?.clear()
                binding.editNewPassword.text?.clear()
                binding.editConfirmPassword.text?.clear()
                showMessage(R.string.profile_password_saved)
            } catch (_: Exception) {
                showMessage(R.string.profile_save_failed)
            } finally {
                if (_binding != null) setBusy(false)
            }
        }
    }

    private fun savePhoto(source: Uri) {
        if (busy) return
        val app = requireActivity().application as PetCareApp
        val userId = currentUser?.id ?: return
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            var savedUri: String? = null
            try {
                savedUri = PhotoStorageHelper.savePhotoToInternalStorage(requireContext(), source)
                val latest = app.container.userRepository.findById(userId) ?: error("Account missing")
                val updated = latest.copy(photoUri = savedUri)
                app.container.userRepository.update(updated)
                PhotoStorageHelper.deletePhotoFile(latest.photoUri)
                currentUser = updated
                showPhoto(savedUri)
                showMessage(R.string.profile_saved)
            } catch (_: Exception) {
                PhotoStorageHelper.deletePhotoFile(savedUri)
                showMessage(R.string.profile_photo_failed)
            } finally {
                if (_binding != null) setBusy(false)
            }
        }
    }

    private fun removePhoto() {
        if (busy) return
        val app = requireActivity().application as PetCareApp
        val userId = currentUser?.id ?: return
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val latest = app.container.userRepository.findById(userId) ?: error("Account missing")
                app.container.userRepository.update(latest.copy(photoUri = null))
                PhotoStorageHelper.deletePhotoFile(latest.photoUri)
                currentUser = latest.copy(photoUri = null)
                showPhoto(null)
                showMessage(R.string.profile_saved)
            } catch (_: Exception) {
                showMessage(R.string.profile_save_failed)
            } finally {
                if (_binding != null) setBusy(false)
            }
        }
    }

    private fun showMessage(messageId: Int) {
        if (_binding != null) Snackbar.make(binding.root, messageId, Snackbar.LENGTH_LONG).show()
    }
}
