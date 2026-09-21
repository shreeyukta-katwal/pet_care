package com.petcare.app.data.db

import androidx.room.TypeConverter

/**
 * TypeConverters for Room.
 *
 * Room cannot store custom types (enums) directly in SQLite columns.
 * This class provides bidirectional conversion between Kotlin types and
 * primitive types that Room/SQLite can persist.
 *
 * Annotated on [PetCareDatabase] via @TypeConverters so they apply globally.
 */
class Converters {

    // ── TaskCategory ──────────────────────────────────────────────────────

    /**
     * Converts a [TaskCategory] enum value to its String name for storage.
     * Stores the enum's name() string (e.g. "FEEDING") rather than ordinal
     * so that re-ordering enums in future does not corrupt existing data.
     *
     * @param category The [TaskCategory] to convert; null if not set.
     * @return The string name of the enum, or null.
     */
    @TypeConverter
    fun fromTaskCategory(category: TaskCategory?): String? = category?.name

    /**
     * Restores a [TaskCategory] from its stored String name.
     *
     * @param value The stored string; null if the column was null.
     * @return The corresponding [TaskCategory] enum constant, or null.
     */
    @TypeConverter
    fun toTaskCategory(value: String?): TaskCategory? =
        value?.let { enumValueOf<TaskCategory>(it) }

    // ── TaskFrequency ─────────────────────────────────────────────────────

    /**
     * Converts a [TaskFrequency] enum value to its String name for storage.
     */
    @TypeConverter
    fun fromTaskFrequency(frequency: TaskFrequency?): String? = frequency?.name

    /**
     * Restores a [TaskFrequency] from its stored String name.
     */
    @TypeConverter
    fun toTaskFrequency(value: String?): TaskFrequency? =
        value?.let { enumValueOf<TaskFrequency>(it) }
}
