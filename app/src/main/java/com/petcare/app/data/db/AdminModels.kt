package com.petcare.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** Read-only account summary used exclusively by the administrator dashboard. */
data class UserWithCounts(
    @Embedded val user: UserEntity,
    @ColumnInfo(name = "pet_count") val petCount: Int,
    @ColumnInfo(name = "task_count") val taskCount: Int
)

/** Application-wide totals that are intentionally visible only to an administrator. */
data class AppStats(
    @ColumnInfo(name = "total_users") val totalUsers: Int,
    @ColumnInfo(name = "total_pets") val totalPets: Int,
    @ColumnInfo(name = "total_tasks") val totalTasks: Int,
    @ColumnInfo(name = "tasks_completed_today") val tasksCompletedToday: Int
)
