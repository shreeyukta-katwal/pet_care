package com.petcare.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ExampleUnitTest – baseline unit test to verify the test setup is working.
 *
 * This test does not test app logic; it simply asserts that 2+2=4 to confirm
 * that the JUnit test runner is configured and the test source set compiles.
 * Real unit tests (ViewModel, repository logic) will be added in subsequent steps.
 */
class ExampleUnitTest {

    /**
     * Verifies that the JUnit test runner is correctly configured.
     *
     * If this test passes, the test source set compiles and the runner is working.
     */
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
}
