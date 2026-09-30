package com.petcare.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.petcare.app.BuildConfig
import com.petcare.app.data.db.UserEntity
import com.petcare.app.data.security.PasswordHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Custom [Application] class for PetCare.
 *
 * Serves as the application-level dependency injection root.
 * [AppContainer] is created here once (in [onCreate]) and lives for the
 * entire lifetime of the application process.
 *
 * On app startup:
 * - Builds the [AppContainer] DI container.
 * - Creates the "Pet care reminders" notification channel (API 26+).
 * - Reschedules all active WorkManager reminders across all enabled tasks for safety.
 */
class PetCareApp : Application() {

    companion object {
        const val CHANNEL_REMINDERS_ID = "pet_care_reminders"
    }

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        seedDebugAdmin()
        createNotificationChannel()
        rescheduleEnabledReminders()
    }

    /** Creates the documented test administrator only in debug builds and only once. */
    private fun seedDebugAdmin() {
        if (!BuildConfig.DEBUG) return
        CoroutineScope(Dispatchers.IO).launch {
            val email = "admin@petcare.app"
            if (container.userRepository.findByEmail(email) == null) {
                val hash = PasswordHasher.hash("AdminPetCare1")
                container.userRepository.insert(
                    UserEntity(email = email, passwordHash = hash.hashBase64, salt = hash.saltBase64, isAdmin = true)
                )
            }
        }
    }

    /**
     * Creates the high-priority notification channel for care routine alerts (API 26+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = getString(R.string.notification_channel_reminders_name)
            val channelDescription = getString(R.string.notification_channel_reminders_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH

            val channel = NotificationChannel(CHANNEL_REMINDERS_ID, channelName, importance).apply {
                description = channelDescription
                enableLights(true)
                enableVibration(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Safety hook: reschedules all active reminders on app startup.
     * Guarantees reminders continue firing even after app update or system WorkManager queue reset.
     */
    private fun rescheduleEnabledReminders() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabledTasks = container.taskRepository.getAllEnabledTasks()
                enabledTasks.forEach { task ->
                    container.reminderScheduler.schedule(task)
                }
            } catch (e: Exception) {
                // Ignore initial empty database or transient startup reads
            }
        }
    }
}
