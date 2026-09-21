package com.petcare.app.ui.pet

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.petcare.app.R
import com.petcare.app.data.db.PetEntity
import com.petcare.app.databinding.ItemPetCardBinding

/**
 * [ListAdapter] that renders a list of [PetItemUiState] objects as Material cards
 * inside [PetListFragment]'s RecyclerView.
 *
 * Displays pet photo, name, species/breed, age, today's checklist progress ("4/6 done"),
 * card click callback, and overflow menu callbacks.
 */
class PetListAdapter(
    private val onCardClick: (petId: Long) -> Unit,
    private val onEditClick: (petId: Long) -> Unit,
    private val onDeleteClick: (pet: PetEntity) -> Unit,
    private val onAddRoutineClick: (petId: Long) -> Unit = {}
) : ListAdapter<PetItemUiState, PetListAdapter.PetViewHolder>(PetDiffCallback()) {

    // ── Gesture / Selection state ─────────────────────────────────────────
    // These mutable vars are set by PetListFragment when entering / exiting
    // ActionMode multi-selection (Gesture 4: long-press to select).
    var isSelectionMode: Boolean = false
    var selectedIds: Set<Long> = emptySet()
    var onPetLongClick: ((PetEntity) -> Boolean)? = null
    var onPetSelectToggle: ((PetEntity) -> Unit)? = null
    /** Invoked when the user double-taps the pet's photo (Gesture 5). */
    var onPhotoDoubleTap: ((PetEntity) -> Unit)? = null

    /** Returns the [PetEntity] at [position], or null if out of bounds. */
    fun getPetAt(position: Int): PetEntity? =
        if (position in 0 until itemCount) getItem(position).pet else null

    // ── ViewHolder ────────────────────────────────────────────────────────

    inner class PetViewHolder(
        private val binding: ItemPetCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PetItemUiState) {
            val pet = item.pet

            // ── Pet photo ──────────────────────────────────────────────
            if (!pet.photoUri.isNullOrBlank()) {
                try {
                    binding.imagePetPhoto.setImageURI(Uri.parse(pet.photoUri))
                } catch (e: Exception) {
                    binding.imagePetPhoto.setImageResource(R.drawable.ic_pet_placeholder)
                }
            } else {
                binding.imagePetPhoto.setImageResource(R.drawable.ic_pet_placeholder)
            }

            // Photo double-tap → Enlarge photo (Gesture 5)
            binding.imagePetPhoto.setOnTouchListener(
                com.petcare.app.gesture.DoubleTapPhotoListener(
                    context = binding.root.context,
                    onDoubleTap = { onPhotoDoubleTap?.invoke(pet) },
                    onSingleTap = {
                        if (isSelectionMode) {
                            onPetSelectToggle?.invoke(pet)
                        } else {
                            onCardClick(pet.id)
                        }
                    }
                )
            )

            // ── Pet name ───────────────────────────────────────────────
            binding.textPetName.text = pet.name

            // ── Species · Breed ────────────────────────────────────────
            val speciesBreed = buildString {
                append(pet.species)
                if (pet.breed.isNotBlank()) {
                    append(" · ")
                    append(pet.breed)
                }
            }
            binding.textSpeciesBreed.text = speciesBreed

            // ── Age label ──────────────────────────────────────────────
            binding.textAge.text = formatAge(pet.ageYears)

            // ── Today's progress label (e.g. "4/6 done") ────────────────
            if (item.progressText.isNotBlank()) {
                binding.textProgress.text = item.progressText
                binding.textProgress.visibility = View.VISIBLE
            } else {
                binding.textProgress.visibility = View.GONE
            }

            // ── Multi-selection state ───────────────────────────────────
            val isSelected = selectedIds.contains(pet.id)
            binding.cardPet.isCheckable = isSelectionMode
            binding.cardPet.isChecked = isSelected
            binding.buttonOverflow.visibility = if (isSelectionMode) View.GONE else View.VISIBLE

            // ── Card click → open checklist or toggle selection ────────
            binding.cardPet.setOnClickListener {
                if (isSelectionMode) {
                    onPetSelectToggle?.invoke(pet)
                } else {
                    onCardClick(pet.id)
                }
            }

            // ── Card long-press → Enter multi-selection mode (Gesture 4) ─
            binding.cardPet.setOnLongClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onPetLongClick?.invoke(pet) ?: false
            }

            // ── Overflow menu ──────────────────────────────────────────
            binding.buttonOverflow.setOnClickListener { anchor ->
                val popup = PopupMenu(anchor.context, anchor)
                popup.inflate(R.menu.menu_pet_card)
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_edit_pet      -> { onEditClick(pet.id);        true }
                        R.id.action_delete_pet    -> { onDeleteClick(pet);         true }
                        R.id.action_add_routine   -> { onAddRoutineClick(pet.id);  true }
                        else                      -> false
                    }
                }
                popup.show()
            }
        }

        private fun formatAge(ageYears: Float): String {
            if (ageYears < 1f) return "< 1 yr"
            val years = ageYears.toInt()
            return "$years yr"
        }
    }

    // ── ListAdapter overrides ─────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PetViewHolder {
        val binding = ItemPetCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PetViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PetViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // ── DiffUtil ──────────────────────────────────────────────────────────

    private class PetDiffCallback : DiffUtil.ItemCallback<PetItemUiState>() {

        override fun areItemsTheSame(oldItem: PetItemUiState, newItem: PetItemUiState): Boolean =
            oldItem.pet.id == newItem.pet.id

        override fun areContentsTheSame(oldItem: PetItemUiState, newItem: PetItemUiState): Boolean =
            oldItem == newItem
    }
}
