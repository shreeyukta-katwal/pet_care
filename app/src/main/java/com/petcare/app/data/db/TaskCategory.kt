package com.petcare.app.data.db

/**
 * Represents the category of a care task.
 *
 * Used in [TaskEntity] to group checklist items under labelled sections.
 * The [displayName] property provides a user-friendly label for display in the UI;
 * all string resources reference these values via string-array in strings.xml.
 *
 * Categories map to the marking rubric sections:
 * FEEDING, EXERCISE, GROOMING, MEDICATION, HEALTHCARE (includes vet appointments).
 */
enum class TaskCategory(val displayName: String) {
    /** Meals, water, and dietary supplements. */
    FEEDING("Feeding"),

    /** Walks, play, and physical activity. */
    EXERCISE("Exercise"),

    /** Bathing, brushing, nail trimming, etc. */
    GROOMING("Grooming"),

    /** Daily or recurring medications. */
    MEDICATION("Medication"),

    /** Vet appointments, vaccinations, health checks. */
    HEALTHCARE("Healthcare")
}
