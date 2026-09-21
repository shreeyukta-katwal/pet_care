package com.petcare.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a single care task (routine item) for a pet.
 *
 * Tasks belong to exactly one [PetEntity] via the [petId] foreign key.
 * The CASCADE delete action means that deleting a pet automatically removes
 * all its tasks from the database (no orphan records).
 *
 * ## Scheduling logic
 * - [frequency] == DAILY  → task appears on every day's checklist.
 * - [frequency] == WEEKLY → task appears only on days whose bit is set in
 *   [daysOfWeek]. Bit values: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64.
 *   Example: Mon+Wed+Fri = 1+4+16 = 21.
 *
 * ## "Done today" logic
 * There is NO scheduled midnight reset job. Instead, [lastCompletedDate] stores
 * the ISO date string (yyyy-MM-dd) of the last completion. A task is considered
 * "done today" when [lastCompletedDate] == today's ISO date. When the calendar
 * day changes, the comparison naturally becomes false — effectively auto-resetting.
 *
 * ## Veterinary appointments
 * Vet visits are modelled as HEALTHCARE tasks with [hour]/[minute] set to the
 * appointment time and details in [notes] (clinic name, address, date context).
 *
 * @property id                Auto-generated primary key.
 * @property petId             FK → [PetEntity.id]; the pet this task belongs to.
 * @property name              Short task name (e.g. "Morning walk").
 * @property category          One of the five [TaskCategory] values.
 * @property frequency         [TaskFrequency.DAILY] or [TaskFrequency.WEEKLY].
 * @property daysOfWeek        Bitmask of active weekdays (only used when WEEKLY).
 * @property hour              Reminder hour in 24h format (0–23).
 * @property minute            Reminder minute (0–59).
 * @property supplies          Comma-separated list of required supplies.
 * @property notes             Free-form extra notes for this task.
 * @property reminderEnabled   Whether WorkManager should schedule a notification.
 * @property lastCompletedDate ISO yyyy-MM-dd string of last completion, or null.
 */
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["pet_id"],
            // Automatically delete all tasks when their parent pet is deleted
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // Index speeds up the common query "all tasks for pet X"
        Index(value = ["pet_id"])
    ]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "pet_id")
    val petId: Long,

    @ColumnInfo(name = "name")
    val name: String,

    /** Stored as String via [Converters.fromTaskCategory]. */
    @ColumnInfo(name = "category")
    val category: TaskCategory,

    /** Stored as String via [Converters.fromTaskFrequency]. */
    @ColumnInfo(name = "frequency")
    val frequency: TaskFrequency,

    /**
     * Bitmask of active days for WEEKLY tasks.
     * Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64.
     * For DAILY tasks this field is ignored (checklist logic must skip it).
     */
    @ColumnInfo(name = "days_of_week")
    val daysOfWeek: Int = 0,

    /** 24-hour clock hour for the reminder notification (0–23). */
    @ColumnInfo(name = "hour")
    val hour: Int = 8,

    /** Minute component for the reminder notification (0–59). */
    @ColumnInfo(name = "minute")
    val minute: Int = 0,

    @ColumnInfo(name = "supplies")
    val supplies: String = "",

    @ColumnInfo(name = "notes")
    val notes: String = "",

    /** True if WorkManager should schedule a daily/weekly alarm notification. */
    @ColumnInfo(name = "reminder_enabled")
    val reminderEnabled: Boolean = false,

    /**
     * ISO date (yyyy-MM-dd) of the most recent completion, or null if never done.
     * Comparing this to today's date determines the "done today" state without
     * requiring any scheduled reset job.
     */
    @ColumnInfo(name = "last_completed_date")
    val lastCompletedDate: String? = null
)
