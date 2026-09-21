package com.petcare.app.reminder

import com.petcare.app.data.db.TaskEntity

/**
 * Contract for scheduling and cancelling task reminder notifications.
 *
 * Implementations use WorkManager to enqueue periodic workers that fire
 * notifications at the time defined in [TaskEntity.hour] and [TaskEntity.minute].
 *
 * A no-op implementation ([NoOpReminderScheduler]) is used in Step 1
 * so the rest of the app compiles without WorkManager worker code.
 * The real implementation is wired in Step 4 (reminder/notification step).
 */
interface ReminderScheduler {

    /**
     * Schedules (or re-schedules) a WorkManager reminder for the given task.
     *
     * If a reminder for this task already exists it must be cancelled and
     * re-created so that any changes to time or enabled state are applied.
     * This is a no-op when [task.reminderEnabled] is false.
     *
     * @param task The [TaskEntity] for which to schedule a reminder.
     */
    fun schedule(task: TaskEntity)

    /**
     * Cancels the WorkManager reminder for a specific task.
     *
     * Called when a task is deleted or its reminder is disabled.
     *
     * @param taskId The primary key of the task whose reminder to cancel.
     */
    fun cancel(taskId: Long)

    /**
     * Cancels all WorkManager reminders for every task belonging to a pet.
     *
     * Called when an entire pet profile is deleted, ensuring no orphan
     * notifications are left in the WorkManager queue.
     *
     * @param petId The primary key of the pet whose task reminders to cancel.
     */
    fun cancelForPet(petId: Long)
}
