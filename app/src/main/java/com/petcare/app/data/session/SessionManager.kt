package com.petcare.app.data.session

/**
 * Contract for managing the logged-in user's session.
 *
 * The session determines which user is currently active, scoping all data
 * queries to their account. Two persistence modes are supported:
 *
 * - **Persistent** (remember me): the userId is written to SharedPreferences
 *   and survives app restarts.
 * - **Non-persistent** (session only): the userId is held in memory only;
 *   closing the app process clears it (SharedPreferences entry is removed).
 *
 * Implementations must be thread-safe and accessible from any coroutine context.
 */
interface SessionManager {

    /**
     * Saves the session for the given user.
     *
     * @param userId     The logged-in user's primary key.
     * @param persistent True → write to SharedPreferences (survives restarts).
     *                   False → store in memory only, remove from SharedPreferences.
     */
    fun saveSession(userId: Long, persistent: Boolean)

    /**
     * Returns the currently logged-in user's ID.
     *
     * Checks the in-memory value first, then falls back to SharedPreferences
     * (for persistent sessions restored after an app restart).
     *
     * @return The user ID, or [NO_USER] (-1L) if no session is active.
     */
    fun getUserId(): Long

    /**
     * Clears all session state (both in-memory and SharedPreferences).
     *
     * Must be called on logout so the user is redirected to the welcome screen.
     */
    fun clear()

    companion object {
        /** Sentinel value returned by [getUserId] when no session is active. */
        const val NO_USER = -1L
    }
}
