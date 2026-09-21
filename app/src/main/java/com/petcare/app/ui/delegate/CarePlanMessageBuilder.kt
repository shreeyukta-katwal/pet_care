package com.petcare.app.ui.delegate

import com.petcare.app.data.db.TaskCategory
import com.petcare.app.data.db.TaskEntity
import java.util.Locale

/**
 * Pure Kotlin message builder and GSM encoding helper for SMS care plan delegation.
 *
 * Formats a clean, compact, plain-ASCII-friendly SMS message summarizing a pet's
 * daily care routines, grouped by category and sorted chronologically.
 *
 * ## Example output:
 * ```text
 * PetCare - Care plan for Max (Golden Retriever)
 * Feeding: 08:00 Morning meal (1 cup dry food) | 18:00 Evening meal
 * Exercise: 07:30 Walk (bring bag)
 * Medication: 20:00 Flea tablet - with food
 * Notes: Back Friday evening. Call me if he refuses food.
 * Sent by Emily via PetCare
 * ```
 *
 * ## SMS Encoding & Length Rules:
 * - **GSM 7-bit**: Standard cellular alphabet. Single SMS allows up to 160 characters.
 *   Multipart (concatenated) SMS allows 153 characters per segment due to the 7-byte UDH header.
 *   Extended characters (such as `|`, `^`, `{`, `}`, `\`, `[`, `~`, `]`, `€`) cost 2 septets.
 * - **UCS-2 (Unicode)**: Triggered if emojis or characters outside GSM 03.38 are present.
 *   Cuts capacity to 70 characters for a single SMS and 67 characters per segment in multipart.
 */
object CarePlanMessageBuilder {

    /** GSM 03.38 Basic character set (single 7-bit septet). */
    private val GSM_BASIC_CHARS: Set<Char> = setOf(
        '@', '£', '$', '¥', 'è', 'é', 'ù', 'ì', 'ò', 'Ç', '\n', 'Ø', 'ø', '\r', 'Å', 'å',
        'Δ', '_', 'Φ', 'Γ', 'Λ', 'Ω', 'Π', 'Ψ', 'Σ', 'Θ', 'Ξ', '\u001B', 'Æ', 'æ', 'ß', 'É',
        ' ', '!', '"', '#', '¤', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', ';', '<', '=', '>', '?',
        '¡', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
        'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'Ä', 'Ö', 'Ñ', 'Ü', '§',
        '¿', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o',
        'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'ä', 'ö', 'ñ', 'ü', 'à'
    )

    /** GSM 03.38 Extension character set (each costs 2 septets via escape 0x1B). */
    private val GSM_EXTENDED_CHARS: Set<Char> = setOf(
        '^', '{', '}', '\\', '[', '~', ']', '|', '€', '\u000C'
    )

    /**
     * Builds the formatted care plan message text.
     *
     * @param petName Name of the pet.
     * @param petBreed Breed of the pet (optional; omitted if blank).
     * @param senderName Sender name or user display name (optional).
     * @param tasks Selected care tasks to include in the message.
     * @param extraInstructions Free-text instructions from the user (optional).
     * @return Formatted multi-line plain text suitable for SMS.
     */
    fun buildMessage(
        petName: String,
        petBreed: String = "",
        senderName: String = "",
        tasks: List<TaskEntity>,
        extraInstructions: String = ""
    ): String {
        val lines = mutableListOf<String>()

        // 1. Header line: "PetCare - Care plan for Max (Golden Retriever)"
        val cleanName = petName.trim().ifBlank { "your pet" }
        val cleanBreed = petBreed.trim()
        val header = if (cleanBreed.isNotBlank()) {
            "PetCare - Care plan for $cleanName ($cleanBreed)"
        } else {
            "PetCare - Care plan for $cleanName"
        }
        lines.add(header)

        // 2. Group tasks by category in fixed canonical order
        val orderedCategories = listOf(
            TaskCategory.FEEDING,
            TaskCategory.EXERCISE,
            TaskCategory.GROOMING,
            TaskCategory.MEDICATION,
            TaskCategory.HEALTHCARE
        )

        for (category in orderedCategories) {
            val categoryTasks = tasks.filter { it.category == category }
                .sortedWith(compareBy({ it.hour }, { it.minute }, { it.name }))

            if (categoryTasks.isNotEmpty()) {
                val formattedTasks = categoryTasks.joinToString(" | ") { task ->
                    formatSingleTask(task)
                }
                val categoryName = category.name.lowercase(Locale.ROOT)
                    .replaceFirstChar { it.titlecase(Locale.ROOT) }
                lines.add("$categoryName: $formattedTasks")
            }
        }

        // 3. Extra instructions line
        val cleanNotes = extraInstructions.trim()
        if (cleanNotes.isNotBlank()) {
            lines.add("Notes: $cleanNotes")
        }

        // 4. Footer line: "Sent by Emily via PetCare" or "Sent via PetCare"
        val cleanSender = senderName.trim()
        val footer = if (cleanSender.isNotBlank()) {
            "Sent by $cleanSender via PetCare"
        } else {
            "Sent via PetCare"
        }
        lines.add(footer)

        return lines.joinToString("\n")
    }

    /**
     * Formats a single task item: "08:00 Morning meal (1 cup dry food) - with food"
     */
    private fun formatSingleTask(task: TaskEntity): String {
        val timeStr = String.format(Locale.ROOT, "%02d:%02d", task.hour, task.minute)
        return buildString {
            append(timeStr)
            append(" ")
            append(task.name.trim())
            if (task.supplies.isNotBlank()) {
                append(" (")
                append(task.supplies.trim())
                append(")")
            }
            if (task.notes.isNotBlank()) {
                append(" - ")
                append(task.notes.trim())
            }
        }
    }

    /**
     * Checks whether all characters in [text] are part of the standard GSM 03.38 7-bit charset.
     */
    fun isGsm7Bit(text: String): Boolean {
        for (ch in text) {
            if (ch !in GSM_BASIC_CHARS && ch !in GSM_EXTENDED_CHARS) {
                return false
            }
        }
        return true
    }

    /**
     * Calculates the GSM septet count (basic chars count as 1, extended count as 2).
     */
    fun calculateGsmSeptets(text: String): Int {
        var count = 0
        for (ch in text) {
            count += when {
                ch in GSM_BASIC_CHARS -> 1
                ch in GSM_EXTENDED_CHARS -> 2
                else -> 1 // fallback for counting
            }
        }
        return count
    }

    /**
     * Calculates the estimated number of SMS segments needed to transmit [text].
     *
     * - **GSM 7-bit**: 160 characters for 1 part; 153 septets per part for multipart.
     * - **UCS-2**: 70 characters for 1 part; 67 characters per part for multipart.
     */
    fun calculateSmsParts(text: String): Int {
        if (text.isEmpty()) return 0
        val isGsm = isGsm7Bit(text)

        return if (isGsm) {
            val septets = calculateGsmSeptets(text)
            if (septets <= 160) 1 else kotlin.math.ceil(septets.toDouble() / 153.0).toInt()
        } else {
            val length = text.length
            if (length <= 70) 1 else kotlin.math.ceil(length.toDouble() / 67.0).toInt()
        }
    }

    /**
     * Validates a recipient phone number.
     *
     * Rules:
     * - Only allows digits, '+', spaces, dashes, parentheses.
     * - '+' may only appear at index 0.
     * - Stripped digit count must be between 7 and 15 (inclusive, per ITU-T E.164 standard).
     */
    fun isValidPhoneNumber(phone: String): Boolean {
        val trimmed = phone.trim()
        if (trimmed.isEmpty()) return false

        // Check allowed characters
        val allowedPattern = Regex("""^[+0-9\s\-()]+$""")
        if (!allowedPattern.matches(trimmed)) return false

        // Check '+' is only at start
        val plusIndex = trimmed.indexOf('+')
        if (plusIndex > 0) return false
        if (trimmed.count { it == '+' } > 1) return false

        // Count digits
        val digitCount = trimmed.count { it.isDigit() }
        return digitCount in 7..15
    }

    /**
     * Normalizes a phone number for direct sending or smsto: intents,
     * retaining only leading '+' and all digits.
     */
    fun cleanPhoneNumber(phone: String): String {
        val trimmed = phone.trim()
        val hasLeadingPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return if (hasLeadingPlus) "+$digits" else digits
    }
}
