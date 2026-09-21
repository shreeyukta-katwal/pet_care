package com.petcare.app.reminder

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.db.TaskFrequency
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * [ReminderScheduler] implementation backed by Android Jetpack [WorkManager].
 *
 * ## Why WorkManager over Exact Alarms?
 * - **Permission-free**: Android 12+ (API 31+) restricts [android.app.AlarmManager.setExact] behind
 *   the special `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` permissions which require user intervention
 *   or special Google Play policy compliance. WorkManager requires **no special alarm permission**.
 * - **Battery & OS Friendly**: WorkManager respects Android Doze mode and App Standby buckets,
 *   coalescing background work to conserve device battery.
 * - **Inexact Timing by Design**: WorkManager executions are subject to system optimization and may
 *   drift by a few minutes depending on device load and power-saving policies. For pet care routines
 *   (feeding, grooming, daily walks), a drift of 2–5 minutes is completely acceptable and ensures
 *   reliable background execution across all Android OEM devices.
 * - **Drift-Free Recurring Chain**: Each [ReminderWorker] execution automatically re-schedules the NEXT
 *   occurrence via [schedule], creating a self-sustaining chain of one-time work requests that never
 *   accumulates drift over weeks or months.
 *
 * @param context Application context.
 */
class WorkManagerReminderScheduler(
    context: Context
) : ReminderScheduler {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    companion object {
        fun tagForPet(petId: Long) = "pet_$petId"
        fun tagForTask(taskId: Long) = "task_$taskId"
        fun uniqueWorkName(taskId: Long) = "reminder_$taskId"
    }

    /**
     * Schedules the next reminder notification for [task].
     *
     * Computes the nearest upcoming occurrence (today if time is still in the future,
     * otherwise the next matching day per Daily/Weekly schedule) and enqueues a
     * [OneTimeWorkRequest] with that initial delay using [ExistingWorkPolicy.REPLACE].
     */
    override fun schedule(task: TaskEntity) {
        if (!task.reminderEnabled) {
            cancel(task.id)
            return
        }

        val nextOccurrence = calculateNextOccurrence(task)
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val delayMillis = Duration.between(now, nextOccurrence).toMillis().coerceAtLeast(0L)

        val workRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_TASK_ID to task.id))
            .addTag(tagForPet(task.petId))
            .addTag(tagForTask(task.id))
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(task.id),
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    /**
     * Cancels any scheduled reminder work for [taskId].
     */
    override fun cancel(taskId: Long) {
        workManager.cancelUniqueWork(uniqueWorkName(taskId))
    }

    /**
     * Cancels all scheduled reminder work for all tasks associated with [petId].
     */
    override fun cancelForPet(petId: Long) {
        workManager.cancelAllWorkByTag(tagForPet(petId))
    }

    /**
     * Computes the exact [ZonedDateTime] of the next upcoming occurrence for [task].
     *
     * - **Daily**: If today at (hour, minute) is in the future, return today. Otherwise tomorrow.
     * - **Weekly**: Tests days starting from today (offset 0..7) to find the nearest upcoming day
     *   matching the [task.daysOfWeek] bitmask (Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64).
     */
    fun calculateNextOccurrence(
        task: TaskEntity,
        zone: ZoneId = ZoneId.systemDefault()
    ): ZonedDateTime {
        val now = ZonedDateTime.now(zone)
        val targetToday = now.withHour(task.hour)
            .withMinute(task.minute)
            .withSecond(0)
            .withNano(0)

        return when (task.frequency) {
            TaskFrequency.DAILY -> {
                if (targetToday.isAfter(now)) {
                    targetToday
                } else {
                    targetToday.plusDays(1)
                }
            }
            TaskFrequency.WEEKLY -> {
                // Day bit for offset 0 (today)
                val todayBit = 1 shl (now.dayOfWeek.value - 1)
                if ((task.daysOfWeek and todayBit) != 0 && targetToday.isAfter(now)) {
                    targetToday
                } else {
                    // Check upcoming 1..7 days
                    var nextDate = targetToday.plusDays(1)
                    for (offset in 1..7) {
                        val candidate = targetToday.plusDays(offset.toLong())
                        val dayBit = 1 shl (candidate.dayOfWeek.value - 1)
                        if ((task.daysOfWeek and dayBit) != 0) {
                            nextDate = candidate
                            break
                        }
                    }
                    nextDate
                }
            }
        }
    }
}
