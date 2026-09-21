package com.petcare.app.reminder

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.navigation.NavDeepLinkBuilder
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.petcare.app.PetCareApp
import com.petcare.app.R
import java.time.LocalDate
import java.util.Locale

/**
 * [CoroutineWorker] responsible for firing a task reminder notification and
 * automatically enqueuing the next occurrence.
 *
 * Execution flow:
 * 1. Read `TASK_ID` from worker input data.
 * 2. Load task and pet from Room database.
 * 3. Verify reminder is still enabled and task still exists (avoid stale notifications).
 * 4. Check if task was already completed today; if completed, skip notification.
 * 5. If pending, post a notification with task time, notes, and a deep link to the checklist.
 * 6. Always schedule the next occurrence via [ReminderScheduler.schedule] to maintain the chain.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TASK_ID = "TASK_ID"
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0L) {
            return Result.success()
        }

        val app = applicationContext as? PetCareApp ?: return Result.success()
        val container = app.container
        val task = container.taskRepository.findById(taskId) ?: return Result.success()

        // If user disabled reminder or deleted pet/task while queued, don't notify
        if (!task.reminderEnabled) {
            return Result.success()
        }

        val todayIso = LocalDate.now().toString()
        val isDoneToday = (task.lastCompletedDate == todayIso)

        // Only show notification if not already completed today
        if (!isDoneToday) {
            val pet = container.petRepository.findById(task.petId)
            val petName = pet?.name ?: "Your pet"

            postNotification(
                taskId = task.id,
                petId = task.petId,
                title = "$petName: ${task.name}",
                hour = task.hour,
                minute = task.minute,
                supplies = task.supplies,
                notes = task.notes
            )
        }

        // Chain the NEXT occurrence so reminders repeat reliably without drift
        container.reminderScheduler.schedule(task)

        return Result.success()
    }

    /**
     * Builds and posts the reminder notification to the Pet care reminders channel.
     * Tapping deep-links directly to [PetChecklistFragment] with the proper back stack.
     */
    private fun postNotification(
        taskId: Long,
        petId: Long,
        title: String,
        hour: Int,
        minute: Int,
        supplies: String,
        notes: String
    ) {
        val context = applicationContext
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Formatted time
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val timeStr = String.format(Locale.getDefault(), "%02d:%02d %s", displayHour, minute, amPm)

        val contentText = buildString {
            append("Scheduled for $timeStr")
            if (supplies.isNotBlank()) {
                append(" • Supplies: $supplies")
            }
            if (notes.isNotBlank()) {
                append(" • Notes: $notes")
            }
        }

        // Navigation Component Deep Link PendingIntent
        val args = Bundle().apply {
            putLong("petId", petId)
        }

        val pendingIntent = NavDeepLinkBuilder(context)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.petChecklistFragment)
            .setArguments(args)
            .createPendingIntent()

        val notification = NotificationCompat.Builder(context, PetCareApp.CHANNEL_REMINDERS_ID)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(taskId.toInt(), notification)
    }
}
