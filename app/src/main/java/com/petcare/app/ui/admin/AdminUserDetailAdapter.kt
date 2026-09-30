package com.petcare.app.ui.admin

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.petcare.app.R
import com.petcare.app.databinding.ItemAdminPetDetailBinding
import com.petcare.app.databinding.ItemAdminTaskRowBinding
import java.time.LocalDate

/**
 * Read-only adapter that renders each [AdminPetDetail] as a card containing the pet's
 * name/species/breed and a per-task status list grouped inside a dynamic LinearLayout.
 *
 * Each task row is inflated from [item_admin_task_row.xml] and uses a plain ImageView
 * status icon instead of a Checkbox, making the non-interactive, read-only nature
 * visually obvious at a glance. Tasks are grouped by category under compact header labels.
 */
class AdminUserDetailAdapter : RecyclerView.Adapter<AdminUserDetailAdapter.DetailHolder>() {

    private var items: List<AdminPetDetail> = emptyList()

    fun submitList(newItems: List<AdminPetDetail>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): DetailHolder =
        DetailHolder(
            ItemAdminPetDetailBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: DetailHolder, position: Int): Unit =
        holder.bind(items[position])

    /** Binds one read-only pet card, inflating individual task rows into tasks_container. */
    inner class DetailHolder(
        private val binding: ItemAdminPetDetailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AdminPetDetail) {
            val ctx = binding.root.context

            // ── Pet identity header ─────────────────────────────────────────
            binding.petName.text = item.pet.name
            binding.petInfo.text = buildString {
                if (item.pet.species.isNotBlank()) append(item.pet.species)
                if (item.pet.species.isNotBlank() && item.pet.breed.isNotBlank()) append(" • ")
                if (item.pet.breed.isNotBlank()) append(item.pet.breed)
            }

            // Resolve theme colours once to avoid repeated typed-value lookups per row
            val colorPrimary = resolveThemeColor(binding.root, com.google.android.material.R.attr.colorPrimary)
            val colorOnSurfaceVariant = resolveThemeColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)

            // ── Task rows ───────────────────────────────────────────────────
            val container = binding.tasksContainer
            container.removeAllViews()

            val today = LocalDate.now().toString()
            val inflater = LayoutInflater.from(ctx)

            if (item.tasks.isEmpty()) {
                // Placeholder row when there are no routines for this pet
                val rowBinding = ItemAdminTaskRowBinding.inflate(inflater, container, false)
                rowBinding.textTaskName.text = ctx.getString(R.string.admin_no_routines_for_pet)
                rowBinding.textTaskCategory.text = ""
                rowBinding.textTaskStatus.text = ""
                rowBinding.iconStatus.setImageResource(R.drawable.ic_notes_24)
                rowBinding.iconStatus.setColorFilter(colorOnSurfaceVariant)
                container.addView(rowBinding.root)
                return
            }

            // Group tasks by category so related routines are visually clustered
            val byCategory = item.tasks.groupBy { it.category }

            byCategory.forEach { (category, tasks) ->
                // ── Category divider label ──────────────────────────────────
                val categoryLabel = inflater.inflate(
                    R.layout.item_admin_category_header, container, false
                ) as TextView
                categoryLabel.text = category.displayName
                container.addView(categoryLabel)

                // ── Task rows for this category ─────────────────────────────
                tasks.forEach { task ->
                    val isDoneToday = task.lastCompletedDate == today
                    val rowBinding = ItemAdminTaskRowBinding.inflate(inflater, container, false)

                    rowBinding.textTaskName.text = task.name
                    rowBinding.textTaskCategory.text = category.displayName

                    if (isDoneToday) {
                        // Done: check icon tinted with colorPrimary (green/brand)
                        rowBinding.iconStatus.setImageResource(R.drawable.ic_check)
                        rowBinding.iconStatus.setColorFilter(colorPrimary)
                        rowBinding.textTaskStatus.text = ctx.getString(R.string.admin_task_done)
                        rowBinding.textTaskStatus.setTextColor(colorPrimary)
                    } else {
                        // Not done: clock icon in muted colorOnSurfaceVariant
                        rowBinding.iconStatus.setImageResource(R.drawable.ic_clock)
                        rowBinding.iconStatus.setColorFilter(colorOnSurfaceVariant)
                        rowBinding.textTaskStatus.text = ctx.getString(R.string.admin_task_not_done)
                        rowBinding.textTaskStatus.setTextColor(colorOnSurfaceVariant)
                    }

                    container.addView(rowBinding.root)
                }
            }
        }

        /**
         * Resolves a theme colour attribute to an ARGB integer using the view's context.
         * Falls back to transparent if the attribute is not defined.
         */
        private fun resolveThemeColor(view: android.view.View, attrRes: Int): Int {
            val tv = TypedValue()
            return if (view.context.theme.resolveAttribute(attrRes, tv, true)) tv.data
            else view.context.getColor(R.color.color_transparent)
        }
    }
}
