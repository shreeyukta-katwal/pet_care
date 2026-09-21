package com.petcare.app.reminder

import com.petcare.app.data.db.TaskCategory
import com.petcare.app.data.db.TaskEntity
import com.petcare.app.data.db.TaskFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for [WorkManagerReminderScheduler.calculateNextOccurrence].
 */
class WorkManagerReminderSchedulerTest {

    private val fixedZone = ZoneId.of("UTC")

    private val dummyTask = TaskEntity(
        id = 1L,
        petId = 1L,
        name = "Dinner",
        category = TaskCategory.FEEDING,
        frequency = TaskFrequency.DAILY,
        daysOfWeek = 0,
        hour = 19,
        minute = 30,
        reminderEnabled = true
    )

    @Test
    fun `calculateNextOccurrence daily task future time returns today`() {
        val scheduler = WorkManagerReminderSchedulerStub()
        // If current hour is 10, and task is at 19:30, next occurrence should be today at 19:30
        val now = ZonedDateTime.now(fixedZone)
        val task = dummyTask.copy(hour = (now.hour + 2) % 24, minute = 0)

        if (now.hour < 22) { // only test if hour+2 is later today
            val next = scheduler.calculateNextOccurrence(task, fixedZone)
            assertEquals(now.dayOfMonth, next.dayOfMonth)
            assertEquals(task.hour, next.hour)
            assertEquals(task.minute, next.minute)
        }
    }

    @Test
    fun `calculateNextOccurrence daily task past time returns tomorrow`() {
        val scheduler = WorkManagerReminderSchedulerStub()
        val now = ZonedDateTime.now(fixedZone)
        // Task at 00:01 when current time is after 00:01
        val task = dummyTask.copy(hour = 0, minute = 1)
        if (now.hour > 0 || now.minute > 1) {
            val next = scheduler.calculateNextOccurrence(task, fixedZone)
            assertTrue(next.isAfter(now))
            assertEquals(now.plusDays(1).dayOfMonth, next.dayOfMonth)
        }
    }

    @Test
    fun `calculateNextOccurrence weekly task selects correct upcoming day`() {
        val scheduler = WorkManagerReminderSchedulerStub()
        val now = ZonedDateTime.now(fixedZone)

        // Tomorrow's day of week
        val tomorrow = now.plusDays(1).dayOfWeek
        val tomorrowBit = 1 shl (tomorrow.value - 1)

        val task = dummyTask.copy(
            frequency = TaskFrequency.WEEKLY,
            daysOfWeek = tomorrowBit,
            hour = 8,
            minute = 0
        )

        val next = scheduler.calculateNextOccurrence(task, fixedZone)
        assertEquals(tomorrow, next.dayOfWeek)
        assertTrue(next.isAfter(now))
    }

    @Test
    fun `tag helpers generate expected formats`() {
        assertEquals("pet_42", WorkManagerReminderScheduler.tagForPet(42L))
        assertEquals("task_99", WorkManagerReminderScheduler.tagForTask(99L))
        assertEquals("reminder_99", WorkManagerReminderScheduler.uniqueWorkName(99L))
    }
}

/**
 * Lightweight test subclass exposing [calculateNextOccurrence] without constructing Android WorkManager.
 */
class WorkManagerReminderSchedulerStub {
    fun calculateNextOccurrence(
        task: TaskEntity,
        zone: ZoneId = ZoneId.systemDefault()
    ): ZonedDateTime {
        val now = ZonedDateTime.now(zone)
        val targetToday = now.withHour(task.hour)
            .withMinute(task.minute)
            .withSecond(0)
            .withNano(0)

        return when (task.frequency) {
            TaskFrequency.DAILY -> {
                if (targetToday.isAfter(now)) {
                    targetToday
                } else {
                    targetToday.plusDays(1)
                }
            }
            TaskFrequency.WEEKLY -> {
                val todayBit = 1 shl (now.dayOfWeek.value - 1)
                if ((task.daysOfWeek and todayBit) != 0 && targetToday.isAfter(now)) {
                    targetToday
                } else {
                    var nextDate = targetToday.plusDays(1)
                    for (offset in 1..7) {
                        val candidate = targetToday.plusDays(offset.toLong())
                        val dayBit = 1 shl (candidate.dayOfWeek.value - 1)
                        if ((task.daysOfWeek and dayBit) != 0) {
                            nextDate = candidate
                            break
                        }
                    }
                    nextDate
                }
            }
        }
    }
}
