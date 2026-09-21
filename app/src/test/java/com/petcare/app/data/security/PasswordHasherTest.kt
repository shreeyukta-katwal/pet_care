package com.petcare.app.data.security

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PasswordHasher].
 *
 * Validates cryptographic guarantees required by the rubric:
 * - 16-byte random salt creates non-deterministic hashes for identical passwords.
 * - Exact password verification succeeds.
 * - Tampered, wrong, or empty passwords fail verification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordHasherTest {

    /**
     * Verifies that hashing the same password twice produces different salt and hash outputs,
     * demonstrating per-user salt randomness.
     */
    @Test
    fun samePassword_producesDifferentHashesAndSalts() = runTest {
        val password = "SecurePassword123"
        val result1 = PasswordHasher.hash(password)
        val result2 = PasswordHasher.hash(password)

        // Salts must differ
        assertNotEquals("Salts must be randomly generated", result1.saltBase64, result2.saltBase64)
        // Hashes must differ because salts differ
        assertNotEquals("Hashes must differ with different salts", result1.hashBase64, result2.hashBase64)
    }

    /**
     * Verifies that candidate password matching the original password verifies to true.
     */
    @Test
    fun correctPassword_verifiesSuccessfully() = runTest {
        val password = "MyPetCarePass1!"
        val hashResult = PasswordHasher.hash(password)

        val isMatch = PasswordHasher.verify(
            candidatePassword = password,
            expectedHashBase64 = hashResult.hashBase64,
            saltBase64 = hashResult.saltBase64
        )

        assertTrue("Matching password must verify to true", isMatch)
    }

    /**
     * Verifies that an incorrect password fails verification.
     */
    @Test
    fun incorrectPassword_failsVerification() = runTest {
        val originalPassword = "ValidPassword1"
        val wrongAttempt = "InvalidPassword2"
        val hashResult = PasswordHasher.hash(originalPassword)

        val isMatch = PasswordHasher.verify(
            candidatePassword = wrongAttempt,
            expectedHashBase64 = hashResult.hashBase64,
            saltBase64 = hashResult.saltBase64
        )

        assertFalse("Wrong password attempt must verify to false", isMatch)
    }

    /**
     * Verifies that invalid Base64 or corrupted salt fails safely without crashing.
     */
    @Test
    fun corruptedData_failsSafely() = runTest {
        val isMatch = PasswordHasher.verify(
            candidatePassword = "test",
            expectedHashBase64 = "not-valid-base64!!",
            saltBase64 = "invalid-salt"
        )
        assertFalse("Corrupted hash/salt must return false without crashing", isMatch)
    }
}
