package com.petcare.app.data.db

/**
 * Represents how often a care task repeats.
 *
 * Used in [TaskEntity] alongside [TaskEntity.daysOfWeek] to determine
 * which days a task should appear on the daily checklist.
 *
 * - [DAILY]  – appears every day; [TaskEntity.daysOfWeek] is ignored.
 * - [WEEKLY] – appears only on days specified by the bitmask in
 *              [TaskEntity.daysOfWeek] (Mon=1, Tue=2, Wed=4, Thu=8,
 *              Fri=16, Sat=32, Sun=64).
 */
enum class TaskFrequency {
    /** Task repeats every single day. */
    DAILY,

    /** Task repeats on selected days of the week (bitmask). */
    WEEKLY
}
