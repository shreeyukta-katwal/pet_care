package com.petcare.app.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.petcare.app.R
import com.petcare.app.data.db.UserWithCounts
import com.petcare.app.databinding.ItemAdminUserBinding

/** RecyclerView adapter for read-only account summaries and their admin-only actions. */
class AdminUserAdapter(
    private val currentUserId: () -> Long,
    private val onViewPets: (Long) -> Unit,
    private val onResetPassword: (UserWithCounts) -> Unit,
    private val onDelete: (UserWithCounts) -> Unit
) : ListAdapter<UserWithCounts, AdminUserAdapter.UserViewHolder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder =
        UserViewHolder(ItemAdminUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) = holder.bind(getItem(position))

    /** Binds one account and omits destructive action for the active administrator. */
    inner class UserViewHolder(private val binding: ItemAdminUserBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UserWithCounts) {
            binding.email.text = item.user.email
            binding.counts.text = binding.root.context.getString(R.string.admin_pet_count, item.petCount) + " • " +
                binding.root.context.getString(R.string.admin_task_count, item.taskCount)
            binding.root.setOnClickListener { onViewPets(item.user.id) }
            binding.overflow.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menu.add(R.string.admin_view_pets).setOnMenuItemClickListener { onViewPets(item.user.id); true }
                    if (item.user.id != currentUserId()) {
                        menu.add(R.string.admin_reset_password).setOnMenuItemClickListener { onResetPassword(item); true }
                        menu.add(R.string.admin_delete_account).setOnMenuItemClickListener { onDelete(item); true }
                    }
                }.show()
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<UserWithCounts>() {
        override fun areItemsTheSame(old: UserWithCounts, new: UserWithCounts) = old.user.id == new.user.id
        override fun areContentsTheSame(old: UserWithCounts, new: UserWithCounts) = old == new
    }
}
