package com.petcare.app

import android.content.Context
import com.petcare.app.data.db.PetCareDatabase
import com.petcare.app.data.repository.PetRepository
import com.petcare.app.data.repository.TaskRepository
import com.petcare.app.data.repository.UserRepository
import com.petcare.app.data.session.SessionManager
import com.petcare.app.data.session.SharedPreferencesSessionManager
import com.petcare.app.reminder.ReminderScheduler
import com.petcare.app.reminder.WorkManagerReminderScheduler

/**
 * Manual dependency injection container for the PetCare application.
 *
 * Holds singleton instances of all application-level dependencies:
 * the database, repositories, session manager, and reminder scheduler.
 * This replaces Hilt/Dagger DI (which is prohibited by the tech rules).
 *
 * Created once in [PetCareApp.onCreate] and accessible throughout the
 * application via `(application as PetCareApp).container`.
 *
 * ## Dependency graph
 * ```
 * PetCareDatabase
 *   └── UserDao   → UserRepository
 *   └── PetDao    ┐
 *   └── TaskDao   ┤→ PetRepository
 *                 └→ TaskRepository
 * Context → SharedPreferencesSessionManager (implements SessionManager)
 * Context → WorkManagerReminderScheduler (implements ReminderScheduler)
 * ```
 *
 * @param context Application context (never an Activity context).
 */
class AppContainer(context: Context) {

    // ── Database ──────────────────────────────────────────────────────────

    /**
     * The single Room database instance.
     * All DAOs are obtained from this instance.
     */
    val database: PetCareDatabase = PetCareDatabase.getInstance(context)

    // ── Repositories ─────────────────────────────────────────────────────

    /**
     * Repository for user account operations (registration, login lookup).
     */
    val userRepository: UserRepository = UserRepository(database.userDao())

    /**
     * Repository for pet profile operations (CRUD, undo-delete snapshot).
     * Receives both PetDao and TaskDao because restore() must re-insert tasks.
     */
    val petRepository: PetRepository = PetRepository(
        database = database,
        petDao = database.petDao(),
        taskDao = database.taskDao()
    )

    /**
     * Repository for care task operations (CRUD, mark done, reset checklist).
     */
    val taskRepository: TaskRepository = TaskRepository(database.taskDao())

    // ── Session ───────────────────────────────────────────────────────────

    /**
     * Manages the currently logged-in user's session.
     * Supports persistent (remember-me) and non-persistent (session-only) modes.
     */
    val sessionManager: SessionManager = SharedPreferencesSessionManager(context)

    // ── Reminders ─────────────────────────────────────────────────────────

    /**
     * Schedules WorkManager tasks for care reminders (Step 7).
     * Provides drift-free chaining of one-time work requests.
     */
    val reminderScheduler: ReminderScheduler = WorkManagerReminderScheduler(context)
}
