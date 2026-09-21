package com.petcare.app.data.security

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Result of hashing a password, containing both the Base64-encoded hash and salt.
 *
 * @property hashBase64 The Base64-encoded PBKDF2 hash (256 bits / 32 bytes).
 * @property saltBase64 The Base64-encoded random salt (16 bytes).
 */
data class HashResult(
    val hashBase64: String,
    val saltBase64: String
)

/**
 * Pure Kotlin singleton responsible for cryptographic password hashing and verification.
 *
 * Specifications (per rubric and security requirements):
 * - Algorithm: `PBKDF2WithHmacSHA256`
 * - Salt: 16 cryptographically secure random bytes via [SecureRandom]
 * - Iterations: 120,000 (standard high work factor against brute force attacks)
 * - Key Length: 256 bits (32 bytes)
 * - Encoding: Standard Base64 strings for database storage
 * - Comparison: Constant-time comparison using [MessageDigest.isEqual] to prevent timing attacks
 * - Threading: Computation runs on [Dispatchers.Default] by default; dispatcher can be overridden for testing.
 */
object PasswordHasher {

    /** Algorithm name for PBKDF2 with SHA-256 HMAC. */
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /** Number of PBKDF2 derivation iterations. */
    private const val ITERATIONS = 120_000

    /** Derived key length in bits (256 bits = 32 bytes). */
    private const val KEY_LENGTH = 256

    /** Size of the random salt in bytes. */
    private const val SALT_SIZE_BYTES = 16

    /** Secure random number generator instance. */
    private val secureRandom = SecureRandom()

    /**
     * Generates a new random 16-byte salt and derives a 256-bit PBKDF2WithHmacSHA256 hash
     * for the given [password].
     *
     * Computation runs off the main thread on [dispatcher] ([Dispatchers.Default] by default).
     *
     * @param password   The plaintext password to hash.
     * @param dispatcher Coroutine dispatcher to execute hashing on.
     * @return A [HashResult] with Base64-encoded hash and salt.
     */
    suspend fun hash(
        password: String,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): HashResult = withContext(dispatcher) {
        // Generate 16 bytes of cryptographically secure random salt
        val salt = ByteArray(SALT_SIZE_BYTES)
        secureRandom.nextBytes(salt)

        // Compute the hash bytes using PBKDF2
        val hashBytes = computeHash(password.toCharArray(), salt)

        // Base64-encode both hash and salt for database persistence
        val encoder = Base64.getEncoder()
        HashResult(
            hashBase64 = encoder.encodeToString(hashBytes),
            saltBase64 = encoder.encodeToString(salt)
        )
    }

    /**
     * Verifies whether an entered [candidatePassword] matches the stored [expectedHashBase64]
     * using the stored [saltBase64].
     *
     * Uses [MessageDigest.isEqual] for constant-time comparison to prevent side-channel timing attacks.
     * Runs off the main thread on [dispatcher] ([Dispatchers.Default] by default).
     *
     * @param candidatePassword  The password attempt entered by the user.
     * @param expectedHashBase64 The stored Base64 hash from the database.
     * @param saltBase64         The stored Base64 salt from the database.
     * @param dispatcher         Coroutine dispatcher to execute verification on.
     * @return `true` if candidate password matches; `false` otherwise.
     */
    suspend fun verify(
        candidatePassword: String,
        expectedHashBase64: String,
        saltBase64: String,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): Boolean = withContext(dispatcher) {
        try {
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(saltBase64)
            val expectedHashBytes = decoder.decode(expectedHashBase64)

            // Recompute the hash with candidate password and original salt
            val candidateHashBytes = computeHash(candidatePassword.toCharArray(), salt)

            // Constant-time byte-array equality check
            MessageDigest.isEqual(candidateHashBytes, expectedHashBytes)
        } catch (e: Exception) {
            // In case of corrupted Base64 or decoding failure, fail closed
            false
        }
    }

    /**
     * Computes the raw PBKDF2 hash bytes given a password char array and salt.
     *
     * @param password The password character array.
     * @param salt     The random salt byte array.
     * @return The 32-byte derived key array.
     */
    private fun computeHash(password: CharArray, salt: ByteArray): ByteArray {
        val keySpec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(keySpec).encoded
    }
}
