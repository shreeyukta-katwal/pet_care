package com.petcare.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a pet profile owned by a [UserEntity].
 *
 * Every pet is scoped to exactly one user via the [userId] foreign key.
 * The CASCADE delete action ensures that when a user is deleted all their
 * pets (and transitively all tasks) are removed automatically by Room/SQLite.
 *
 * All fields besides [photoUri] are required. Optional animal-care fields
 * ([allergies], [vaccinations], [favouriteToys], [notes]) default to empty
 * strings so the UI can always safely display them.
 *
 * @property id            Auto-generated primary key.
 * @property userId        FK → [UserEntity.id]; owner of this pet.
 * @property name          Pet's display name.
 * @property species       e.g. "Dog", "Cat", "Rabbit".
 * @property breed         Specific breed (may be "Unknown" or "Mixed").
 * @property ageYears      Age in years (floating point for e.g. 0.5 = 6 months).
 * @property weightKg      Body weight in kilograms.
 * @property diet          Dietary notes (kibble brand, feeding schedule, etc.).
 * @property allergies     Known allergies or intolerances.
 * @property vaccinations  Vaccination history summary.
 * @property favouriteToys Favourite toys / enrichment items.
 * @property notes         Miscellaneous owner notes.
 * @property photoUri      Content URI for the pet photo (null = no photo set).
 */
@Entity(
    tableName = "pets",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            // Automatically delete all pets when their owner user is deleted
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // Index on user_id speeds up the common query "all pets for user X"
        Index(value = ["user_id"])
    ]
)
data class PetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "species")
    val species: String,

    @ColumnInfo(name = "breed")
    val breed: String,

    @ColumnInfo(name = "age_years")
    val ageYears: Float,

    @ColumnInfo(name = "weight_kg")
    val weightKg: Float,

    @ColumnInfo(name = "diet")
    val diet: String,

    @ColumnInfo(name = "allergies")
    val allergies: String,

    @ColumnInfo(name = "vaccinations")
    val vaccinations: String,

    @ColumnInfo(name = "favourite_toys")
    val favouriteToys: String,

    @ColumnInfo(name = "notes")
    val notes: String,

    /** Nullable: null means the user has not set a photo for this pet. */
    @ColumnInfo(name = "photo_uri")
    val photoUri: String? = null
)
