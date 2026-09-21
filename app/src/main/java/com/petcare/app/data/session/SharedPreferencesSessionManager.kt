package com.petcare.app.data.session

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed implementation of [SessionManager].
 *
 * ## Persistence modes
 * - **Persistent**: userId is written to [prefs] under [KEY_USER_ID].
 *   On the next app launch [getUserId] reads it from disk and returns it.
 * - **Non-persistent**: userId is held in [currentUserId] (memory only).
 *   The SharedPreferences key is explicitly removed so a stale persistent
 *   session from a previous login does not accidentally resume.
 *
 * ## Thread safety
 * [currentUserId] is marked @Volatile so reads and writes are always
 * fetched from main memory, preventing caching issues across threads.
 * SharedPreferences itself is thread-safe for single-process apps.
 *
 * @param context Application context used to access SharedPreferences.
 */
class SharedPreferencesSessionManager(context: Context) : SessionManager {

    /** The SharedPreferences file name; isolated from other prefs in the app. */
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    /**
     * In-memory userId cache.
     * Volatile ensures visibility across threads without a full synchronised block.
     * Initialised to [SessionManager.NO_USER] meaning "not logged in".
     */
    @Volatile
    private var currentUserId: Long = SessionManager.NO_USER

    /**
     * Saves the session.
     *
     * If [persistent] is true, writes to SharedPreferences so the session
     * survives process death (e.g., "Remember me" checkbox).
     * If [persistent] is false, removes the SharedPreferences key and stores
     * the userId in memory only.
     *
     * @param userId     The logged-in user's primary key.
     * @param persistent Whether to persist across app restarts.
     */
    override fun saveSession(userId: Long, persistent: Boolean) {
        currentUserId = userId
        if (persistent) {
            // Write to disk so the session survives process death
            prefs.edit().putLong(KEY_USER_ID, userId).apply()
        } else {
            // Non-persistent: explicitly remove any old persistent session
            // so a previous "remember me" login doesn't interfere
            prefs.edit().remove(KEY_USER_ID).apply()
        }
    }

    /**
     * Returns the active user's ID.
     *
     * Checks the in-memory cache first; if that is [SessionManager.NO_USER]
     * (e.g., after a process restart), falls back to SharedPreferences.
     *
     * @return The user ID, or [SessionManager.NO_USER] if no session exists.
     */
    override fun getUserId(): Long {
        if (currentUserId != SessionManager.NO_USER) {
            return currentUserId
        }
        // Attempt to restore a persistent session from disk
        val persisted = prefs.getLong(KEY_USER_ID, SessionManager.NO_USER)
        if (persisted != SessionManager.NO_USER) {
            // Cache in memory to avoid repeated disk reads
            currentUserId = persisted
        }
        return currentUserId
    }

    /**
     * Clears the session from both memory and SharedPreferences.
     *
     * Called on logout. After this call [getUserId] returns [SessionManager.NO_USER].
     */
    override fun clear() {
        currentUserId = SessionManager.NO_USER
        prefs.edit().remove(KEY_USER_ID).apply()
    }

    companion object {
        /** Name of the SharedPreferences file for session data. */
        private const val PREFS_NAME = "petcare_session"

        /** SharedPreferences key under which the user ID is stored. */
        private const val KEY_USER_ID = "user_id"
    }
}
