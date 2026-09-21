package com.petcare.app.ui.delegate

import com.petcare.app.data.db.TaskCategory
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.db.TaskFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CarePlanMessageBuilder].
 * Verifies message formatting, category grouping, supplies/notes inclusion,
 * GSM 7-bit vs UCS-2 character detection, SMS part calculation, and phone validation.
 */
class CarePlanMessageBuilderTest {

    private fun createTask(
        id: Long = 1,
        name: String,
        category: TaskCategory,
        hour: Int,
        minute: Int,
        supplies: String = "",
        notes: String = ""
    ) = TaskEntity(
        id = id,
        petId = 10L,
        name = name,
        category = category,
        frequency = TaskFrequency.DAILY,
        daysOfWeek = 127,
        hour = hour,
        minute = minute,
        supplies = supplies,
        notes = notes,
        reminderEnabled = false
    )

    @Test
    fun `buildMessage formats header with pet name and breed`() {
        val tasks = listOf(
            createTask(1, "Morning meal", TaskCategory.FEEDING, 8, 0, "1 cup dry food"),
            createTask(2, "Evening meal", TaskCategory.FEEDING, 18, 0),
            createTask(3, "Walk", TaskCategory.EXERCISE, 7, 30, "bring bag"),
            createTask(4, "Flea tablet", TaskCategory.MEDICATION, 20, 0, notes = "with food")
        )

        val message = CarePlanMessageBuilder.buildMessage(
            petName = "Max",
            petBreed = "Golden Retriever",
            senderName = "Emily",
            tasks = tasks,
            extraInstructions = "Back on Friday. Call me if he refuses food."
        )

        val expected = """
            PetCare - Care plan for Max (Golden Retriever)
            Feeding: 08:00 Morning meal (1 cup dry food) | 18:00 Evening meal
            Exercise: 07:30 Walk (bring bag)
            Medication: 20:00 Flea tablet - with food
            Notes: Back on Friday. Call me if he refuses food.
            Sent by Emily via PetCare
        """.trimIndent()

        assertEquals(expected, message)
    }

    @Test
    fun `buildMessage handles empty breed and empty notes gracefully`() {
        val tasks = listOf(
            createTask(1, "Walk", TaskCategory.EXERCISE, 9, 15)
        )

        val message = CarePlanMessageBuilder.buildMessage(
            petName = "Luna",
            petBreed = "",
            senderName = "",
            tasks = tasks,
            extraInstructions = ""
        )

        val expected = """
            PetCare - Care plan for Luna
            Exercise: 09:15 Walk
            Sent via PetCare
        """.trimIndent()

        assertEquals(expected, message)
    }

    @Test
    fun `buildMessage preserves category canonical order regardless of input order`() {
        val tasks = listOf(
            createTask(1, "Ear cleaning", TaskCategory.HEALTHCARE, 19, 0),
            createTask(2, "Brush coat", TaskCategory.GROOMING, 12, 0),
            createTask(3, "Breakfast", TaskCategory.FEEDING, 7, 0)
        )

        val message = CarePlanMessageBuilder.buildMessage(
            petName = "Milo",
            tasks = tasks
        )

        val lines = message.lines()
        val feedingLineIndex = lines.indexOfFirst { it.startsWith("Feeding:") }
        val groomingLineIndex = lines.indexOfFirst { it.startsWith("Grooming:") }
        val healthcareLineIndex = lines.indexOfFirst { it.startsWith("Healthcare:") }

        assertTrue(feedingLineIndex < groomingLineIndex)
        assertTrue(groomingLineIndex < healthcareLineIndex)
    }

    @Test
    fun `isGsm7Bit detects standard GSM characters correctly`() {
        assertTrue(CarePlanMessageBuilder.isGsm7Bit("Hello World! 123 - / | €"))
        assertTrue(CarePlanMessageBuilder.isGsm7Bit("PetCare - Care plan for Max"))
    }

    @Test
    fun `isGsm7Bit detects non-GSM Unicode characters and emojis`() {
        assertFalse(CarePlanMessageBuilder.isGsm7Bit("PetCare 🐶"))
        assertFalse(CarePlanMessageBuilder.isGsm7Bit("Care plan with smart quotes ‘test’"))
    }

    @Test
    fun `calculateSmsParts accurately computes single and multipart counts`() {
        // GSM: 160 characters fits in 1 SMS
        val gsm160 = "A".repeat(160)
        assertEquals(1, CarePlanMessageBuilder.calculateSmsParts(gsm160))

        // GSM: 161 characters requires 2 SMS parts (153 chars per part)
        val gsm161 = "A".repeat(161)
        assertEquals(2, CarePlanMessageBuilder.calculateSmsParts(gsm161))

        // Unicode: 70 characters fits in 1 SMS
        val ucs70 = "🐶" + "A".repeat(68)
        assertEquals(1, CarePlanMessageBuilder.calculateSmsParts(ucs70))

        // Unicode: 71 characters requires 2 SMS parts (67 chars per part)
        val ucs71 = "🐶" + "A".repeat(69)
        assertEquals(2, CarePlanMessageBuilder.calculateSmsParts(ucs71))
    }

    @Test
    fun `isValidPhoneNumber validates correct formats and rejects invalid ones`() {
        // Valid phone numbers (7 to 15 digits)
        assertTrue(CarePlanMessageBuilder.isValidPhoneNumber("+44 7911 123456"))
        assertTrue(CarePlanMessageBuilder.isValidPhoneNumber("+1 (555) 234-5678"))
        assertTrue(CarePlanMessageBuilder.isValidPhoneNumber("07123456789"))
        assertTrue(CarePlanMessageBuilder.isValidPhoneNumber("1234567"))

        // Invalid: too short (< 7 digits)
        assertFalse(CarePlanMessageBuilder.isValidPhoneNumber("12345"))

        // Invalid: too long (> 15 digits)
        assertFalse(CarePlanMessageBuilder.isValidPhoneNumber("1234567890123456"))

        // Invalid: letters or invalid symbols
        assertFalse(CarePlanMessageBuilder.isValidPhoneNumber("123-456-CALL"))
        assertFalse(CarePlanMessageBuilder.isValidPhoneNumber("+44+12345678"))
        assertFalse(CarePlanMessageBuilder.isValidPhoneNumber("123+4567890"))
        assertFalse(CarePlanMessageBuilder.isValidPhoneNumber(""))
    }

    @Test
    fun `cleanPhoneNumber normalizes format for intent and smsManager`() {
        assertEquals("+447911123456", CarePlanMessageBuilder.cleanPhoneNumber("+44 7911 123456"))
        assertEquals("+15552345678", CarePlanMessageBuilder.cleanPhoneNumber("+1 (555) 234-5678"))
        assertEquals("07123456789", CarePlanMessageBuilder.cleanPhoneNumber("07123-456-789"))
    }
}
