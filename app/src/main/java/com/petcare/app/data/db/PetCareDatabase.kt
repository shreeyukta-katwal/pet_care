package com.petcare.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * PetCareDatabase – the single Room database instance for the application.
 *
 * Declared as a singleton via the companion object to ensure only one
 * database connection is ever open at a time, preventing concurrency bugs.
 *
 * ## Schema versioning
 * - Version 1 is the initial schema (this step).
 * - Future schema changes must increment [version] and provide a [androidx.room.migration.Migration].
 * - [exportSchema] = true writes the schema JSON to app/schemas/, which should be
 *   committed to version control so that migration correctness can be verified.
 *
 * ## Entities
 * - [UserEntity]  – registered users
 * - [PetEntity]   – pet profiles owned by users
 * - [TaskEntity]  – care routine tasks belonging to pets
 *
 * ## TypeConverters
 * [Converters] is registered globally so Room can serialise/deserialise
 * [TaskCategory] and [TaskFrequency] enum fields automatically.
 */
@Database(
    entities = [UserEntity::class, PetEntity::class, TaskEntity::class],
    version = 1,
    exportSchema = true   // Exports schema to app/schemas/ for migration tracking
)
@TypeConverters(Converters::class)
abstract class PetCareDatabase : RoomDatabase() {

    /** Provides access to user account queries. */
    abstract fun userDao(): UserDao

    /** Provides access to pet profile queries. */
    abstract fun petDao(): PetDao

    /** Provides access to care task queries. */
    abstract fun taskDao(): TaskDao

    companion object {
        /**
         * The file name of the SQLite database on disk.
         * Changing this will create a new empty database on existing installs.
         */
        private const val DATABASE_NAME = "petcare.db"

        /**
         * Volatile ensures the value of [INSTANCE] is always read from main memory,
         * preventing threads from seeing a stale cached copy (double-checked locking).
         */
        @Volatile
        private var INSTANCE: PetCareDatabase? = null

        /**
         * Returns the singleton [PetCareDatabase] instance, creating it if needed.
         *
         * Thread-safe via double-checked locking with a synchronized block.
         * Must be called from the Application class during startup.
         *
         * @param context Application context used to locate the database file.
         * @return The singleton [PetCareDatabase].
         */
        fun getInstance(context: Context): PetCareDatabase {
            // First check (without lock) – fast path if already initialised
            return INSTANCE ?: synchronized(this) {
                // Second check (inside lock) – guards against two threads
                // both passing the first check simultaneously
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        /**
         * Constructs the Room database using the application context.
         *
         * @param context Application context (must not be an Activity context).
         * @return A fully configured [PetCareDatabase] instance.
         */
        private fun buildDatabase(context: Context): PetCareDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PetCareDatabase::class.java,
                DATABASE_NAME
            )
                // In production, migrations would be added here.
                // For now, destructive migration is disabled.
                .build()
    }
}
