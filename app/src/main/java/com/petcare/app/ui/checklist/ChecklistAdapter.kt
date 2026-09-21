package com.petcare.app.ui.checklist

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.petcare.app.R
import com.petcare.app.data.db.TaskCategory
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.databinding.ItemChecklistHeaderBinding
import com.petcare.app.databinding.ItemChecklistTaskBinding
import java.util.Locale

/**
 * Sealed class representing items in the checklist RecyclerView.
 * Can be either a category [Header] or a care [Task] item.
 */
sealed class ChecklistItem {
    data class Header(val category: TaskCategory) : ChecklistItem()
    data class Task(val task: TaskEntity, val isDoneToday: Boolean) : ChecklistItem()
}

/**
 * RecyclerView adapter for displaying categorized care tasks in [PetChecklistFragment].
 *
 * Supports two view types:
 * - Category Header (Feeding, Exercise, Grooming, Medication, Healthcare)
 * - Task Item (Checkbox, strikethrough when done, scheduled time, supplies/notes preview, overflow menu)
 *
 * @param onTaskCheckChanged Triggered when the user toggles a task's done checkbox.
 * @param onTaskEditClick   Triggered when the user chooses "Edit task" from overflow or taps card.
 * @param onTaskDeleteClick Triggered when the user chooses "Delete task" from overflow.
 */
class ChecklistAdapter(
    private val onTaskCheckChanged: (task: TaskEntity, isChecked: Boolean) -> Unit,
    private val onTaskEditClick: (taskId: Long) -> Unit,
    private val onTaskDeleteClick: (task: TaskEntity) -> Unit
) : ListAdapter<ChecklistItem, RecyclerView.ViewHolder>(ChecklistDiffCallback()) {

    var isSelectionMode: Boolean = false
    var selectedIds: Set<Long> = emptySet()
    var onTaskLongClick: ((TaskEntity) -> Boolean)? = null
    var onTaskSelectToggle: ((TaskEntity) -> Unit)? = null

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_TASK = 1
    }

    fun getItemAt(position: Int): ChecklistItem? =
        if (position in 0 until itemCount) getItem(position) else null

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ChecklistItem.Header -> VIEW_TYPE_HEADER
            is ChecklistItem.Task -> VIEW_TYPE_TASK
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemChecklistHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            VIEW_TYPE_TASK -> {
                val binding = ItemChecklistTaskBinding.inflate(inflater, parent, false)
                TaskViewHolder(binding)
            }
            else -> error("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChecklistItem.Header -> (holder as HeaderViewHolder).bind(item.category)
            is ChecklistItem.Task -> (holder as TaskViewHolder).bind(item)
        }
    }

    // ── Header ViewHolder ─────────────────────────────────────────────────

    class HeaderViewHolder(
        private val binding: ItemChecklistHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(category: TaskCategory) {
            binding.textCategoryTitle.text = category.displayName.uppercase(Locale.getDefault())
            binding.imageCategoryIcon.setImageResource(getCategoryIcon(category))
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
    }

    // ── Task ViewHolder ───────────────────────────────────────────────────

    inner class TaskViewHolder(
        private val binding: ItemChecklistTaskBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChecklistItem.Task) {
            val task = item.task

            // Name & Strikethrough / Dimmed effect
            binding.textTaskName.text = task.name

            // Detach listener before setting state to avoid triggering callback during recycle
            binding.checkboxTask.setOnCheckedChangeListener(null)
            binding.checkboxTask.isChecked = item.isDoneToday

            if (item.isDoneToday) {
                binding.textTaskName.paintFlags =
                    binding.textTaskName.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                binding.cardTask.alpha = 0.6f
            } else {
                binding.textTaskName.paintFlags =
                    binding.textTaskName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                binding.cardTask.alpha = 1.0f
            }

            // Checkbox click handler
            binding.checkboxTask.setOnCheckedChangeListener { _, isChecked ->
                onTaskCheckChanged(task, isChecked)
            }

            // Formatted scheduled time
            binding.textTaskTime.text = formatTime(task.hour, task.minute)

            // Supplies & Notes preview
            val detailsText = buildDetailsPreview(task.supplies, task.notes)
            if (detailsText.isNotBlank()) {
                binding.textTaskDetails.text = detailsText
                binding.textTaskDetails.visibility = View.VISIBLE
            } else {
                binding.textTaskDetails.visibility = View.GONE
            }

            // Multi-selection state
            val isSelected = selectedIds.contains(task.id)
            binding.cardTask.isCheckable = isSelectionMode
            binding.cardTask.isChecked = isSelected
            binding.buttonOverflow.visibility = if (isSelectionMode) View.GONE else View.VISIBLE

            // Card click → Edit task or toggle selection
            binding.cardTask.setOnClickListener {
                if (isSelectionMode) {
                    onTaskSelectToggle?.invoke(task)
                } else {
                    onTaskEditClick(task.id)
                }
            }

            // Card long-press → Enter multi-selection mode (Gesture 4)
            binding.cardTask.setOnLongClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onTaskLongClick?.invoke(task) ?: false
            }

            // Overflow menu button
            binding.buttonOverflow.setOnClickListener { anchor ->
                val popup = PopupMenu(anchor.context, anchor)
                popup.inflate(R.menu.menu_pet_card) // Reuse overflow menu structure or custom
                popup.menu.clear()
                popup.menu.add(0, R.id.action_edit_pet, 0, R.string.action_edit_task)
                popup.menu.add(0, R.id.action_delete_pet, 1, R.string.action_delete_task)

                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_edit_pet -> {
                            onTaskEditClick(task.id)
                            true
                        }
                        R.id.action_delete_pet -> {
                            onTaskDeleteClick(task)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }

        private fun formatTime(hour: Int, minute: Int): String {
            val amPm = if (hour < 12) "AM" else "PM"
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            return String.format(Locale.getDefault(), "%02d:%02d %s", displayHour, minute, amPm)
        }

        private fun buildDetailsPreview(supplies: String, notes: String): String {
            val parts = mutableListOf<String>()
            if (supplies.isNotBlank()) parts.add("Supplies: $supplies")
            if (notes.isNotBlank()) parts.add("Notes: $notes")
            return parts.joinToString(" • ")
        }
    }

    // ── DiffCallback ──────────────────────────────────────────────────────

    private class ChecklistDiffCallback : DiffUtil.ItemCallback<ChecklistItem>() {
        override fun areItemsTheSame(oldItem: ChecklistItem, newItem: ChecklistItem): Boolean {
            return when {
                oldItem is ChecklistItem.Header && newItem is ChecklistItem.Header ->
                    oldItem.category == newItem.category
                oldItem is ChecklistItem.Task && newItem is ChecklistItem.Task ->
                    oldItem.task.id == newItem.task.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ChecklistItem, newItem: ChecklistItem): Boolean {
            return oldItem == newItem
        }
    }
}
