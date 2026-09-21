package com.petcare.app.reminder

import com.petcare.app.data.db.TaskEntity

/**
 * No-operation implementation of [ReminderScheduler].
 *
 * Used in Step 1 while the WorkManager notification infrastructure has not
 * yet been built. All methods silently do nothing so that the rest of the
 * app can compile and run without a real scheduler in place.
 *
 * This will be replaced by [WorkManagerReminderScheduler] in a later step
 * once the Worker and notification channel are implemented.
 */
class NoOpReminderScheduler : ReminderScheduler {

    /**
     * No-op: does not schedule anything.
     *
     * @param task Ignored in this implementation.
     */
    override fun schedule(task: TaskEntity) {
        // Intentionally empty – real scheduling implemented in a later step
    }

    /**
     * No-op: does not cancel anything.
     *
     * @param taskId Ignored in this implementation.
     */
    override fun cancel(taskId: Long) {
        // Intentionally empty – real cancellation implemented in a later step
    }

    /**
     * No-op: does not cancel anything.
     *
     * @param petId Ignored in this implementation.
     */
    override fun cancelForPet(petId: Long) {
        // Intentionally empty – real cancellation implemented in a later step
    }
}
