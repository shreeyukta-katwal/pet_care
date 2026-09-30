package com.petcare.app.ui.delegate

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.petcare.app.R
import com.petcare.app.data.db.TaskCategory
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.databinding.ItemDelegateTaskBinding
import java.util.Locale

/**
 * ListAdapter displaying tasks available for SMS delegation.
 * Each item has a checkbox reflecting its inclusion in the care plan message,
 * along with a category icon and badge for distinct visual grouping.
 */
class DelegateTaskAdapter(
    private val onTaskToggled: (Long) -> Unit
) : ListAdapter<TaskEntity, DelegateTaskAdapter.TaskViewHolder>(DiffCallback) {

    private var selectedIds: Set<Long> = emptySet()

    fun updateSelectedIds(ids: Set<Long>) {
        selectedIds = ids
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemDelegateTaskBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position), selectedIds.contains(getItem(position).id))
    }

    inner class TaskViewHolder(
        private val binding: ItemDelegateTaskBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onTaskToggled(getItem(position).id)
                }
            }
            binding.checkInclude.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onTaskToggled(getItem(position).id)
                }
            }
        }

        fun bind(task: TaskEntity, isSelected: Boolean) {
            val timeStr = String.format(Locale.getDefault(), "%02d:%02d", task.hour, task.minute)
            binding.textTaskName.text = task.name
            binding.textTaskTime.text = timeStr
            binding.textCategoryBadge.text = task.category.displayName
            binding.imageCategoryIcon.setImageResource(getCategoryIcon(task.category))

            val detailsText = buildString {
                if (task.supplies.isNotBlank()) {
                    append(task.supplies.trim())
                }
                if (task.notes.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(task.notes.trim())
                }
            }
            if (detailsText.isNotBlank()) {
                binding.textTaskDetails.visibility = View.VISIBLE
                binding.textTaskDetails.text = detailsText
            } else {
                binding.textTaskDetails.visibility = View.GONE
            }

            binding.checkInclude.isChecked = isSelected
        }
    }

    companion object {
        /**
         * Resolves the vector drawable icon resource for each [TaskCategory].
         */
        fun getCategoryIcon(cat: TaskCategory): Int {
            return when (cat) {
                TaskCategory.FEEDING -> R.drawable.ic_cat_feeding
                TaskCategory.EXERCISE -> R.drawable.ic_cat_exercise
                TaskCategory.GROOMING -> R.drawable.ic_cat_grooming
                TaskCategory.MEDICATION -> R.drawable.ic_cat_medication
                TaskCategory.HEALTHCARE -> R.drawable.ic_cat_healthcare
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<TaskEntity>() {
        override fun areItemsTheSame(oldItem: TaskEntity, newItem: TaskEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: TaskEntity, newItem: TaskEntity): Boolean =
            oldItem == newItem
    }
}
