package com.petcare.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an app user.
 *
 * Email is stored lowercase and trimmed before insertion (enforced in the
 * repository layer) to prevent duplicate accounts with different casing.
 * The UNIQUE index on [email] lets Room surface a [SQLiteConstraintException]
 * on duplicate registration which the repository translates to a typed result.
 *
 * Passwords are NEVER stored in plaintext – only a PBKDF2/SHA-256 hash and
 * the random salt used to produce it are persisted (implemented in Step 2).
 *
 * @property id           Auto-generated primary key.
 * @property email        Unique, lowercase, trimmed email address.
 * @property passwordHash Hex-encoded hash of (salt + password).
 * @property salt         Hex-encoded random salt (16 bytes / 32 hex chars).
 */
@Entity(
    tableName = "users",
    indices = [
        // Unique index on email enables fast duplicate-check and login lookup.
        Index(value = ["email"], unique = true)
    ]
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "email")
    val email: String,

    @ColumnInfo(name = "password_hash")
    val passwordHash: String,

    @ColumnInfo(name = "salt")
    val salt: String
)
