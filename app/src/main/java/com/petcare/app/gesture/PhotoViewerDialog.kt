package com.petcare.app.gesture

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.petcare.app.R
import com.petcare.app.databinding.DialogPhotoViewerBinding
import kotlin.math.abs

/**
 * # Gesture 5: Full-Screen Photo Viewer Dialog
 *
 * ## Technical Operation:
 * - Launched when the user **double-taps** a pet photo thumbnail in either:
 *   1. [com.petcare.app.ui.pet.PetListFragment] (pet card thumbnail)
 *   2. [com.petcare.app.ui.checklist.PetChecklistFragment] (checklist header hero photo)
 * - Renders the photo in full resolution inside an immersive, borderless [DialogFragment].
 * - Implements touch gesture dismissal:
 *   - **Single tap**: Dismisses viewer immediately.
 *   - **Swipe Down gesture**: Detects downward vertical drag (`deltaY > 150dp` with minimal X-drift)
 *     and smoothly dismisses the viewer.
 *
 * ## Usability & Accessibility:
 * - Allows pet parents and sitters to inspect important visual health markers (skin condition,
 *   coat texture, collars, eye clarity) without navigating to an edit screen.
 * - Non-gesture alternative: A dedicated "View photo" action or clicking the close button.
 */
class PhotoViewerDialog : DialogFragment() {

    companion object {
        private const val ARG_PET_NAME = "ARG_PET_NAME"
        private const val ARG_PHOTO_URI = "ARG_PHOTO_URI"
        const val TAG = "PhotoViewerDialog"

        fun newInstance(petName: String, photoUri: String?): PhotoViewerDialog {
            return PhotoViewerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PET_NAME, petName)
                    putString(ARG_PHOTO_URI, photoUri)
                }
            }
        }
    }

    private var _binding: DialogPhotoViewerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPhotoViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val petName = arguments?.getString(ARG_PET_NAME) ?: "Pet Photo"
        val photoUri = arguments?.getString(ARG_PHOTO_URI)

        binding.textPetTitle.text = petName

        if (!photoUri.isNullOrBlank()) {
            try {
                binding.imageEnlarged.setImageURI(Uri.parse(photoUri))
            } catch (e: Exception) {
                binding.imageEnlarged.setImageResource(R.drawable.ic_pet_placeholder)
            }
        } else {
            binding.imageEnlarged.setImageResource(R.drawable.ic_pet_placeholder)
        }

        // Close button
        binding.buttonClose.setOnClickListener {
            dismiss()
        }

        // Background tap to dismiss
        binding.root.setOnClickListener {
            dismiss()
        }

        // Swipe-down to dismiss gesture
        setupSwipeDownToDismiss(binding.root)
    }

    private fun setupSwipeDownToDismiss(view: View) {
        var startY = 0f
        var startX = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startX = event.rawX
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaY = event.rawY - startY
                    val deltaX = abs(event.rawX - startX)
                    // Downward swipe of at least 120 pixels with mostly vertical trajectory
                    if (deltaY > 120 && deltaY > deltaX * 1.5) {
                        dismiss()
                        true
                    } else if (abs(deltaY) < 20 && deltaX < 20) {
                        // Click / tap
                        dismiss()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
