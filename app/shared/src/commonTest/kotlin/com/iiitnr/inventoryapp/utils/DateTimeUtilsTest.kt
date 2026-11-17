package com.iiitnr.inventoryapp.utils

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DateTimeUtilsTest {
    @Test
    fun formatDisplayDateReturnsEmptyForNull() {
        assertEquals("", formatDisplayDate(null))
    }

    @Test
    fun formatDisplayDateReturnsEmptyForUnparseableInput() {
        assertEquals("", formatDisplayDate("not a date"))
    }

    @Test
    fun formatDisplayDateFormatsNormalIsoDateTime() {
        assertEquals("20 Aug 2026", formatDisplayDate("2026-08-20T10:00:00"))
    }

    @Test
    fun formatDisplayDateHandlesSpaceSeparator() {
        assertEquals("20 Aug 2026", formatDisplayDate("2026-08-20 10:00:00"))
    }

    @Test
    fun formatDisplayDateDoesNotPadSingleDigitDay() {
        assertEquals("7 Nov 2026", formatDisplayDate("2026-11-07T10:00:00"))
    }

    @Test
    fun formatDisplayDateFormatsJanuary() {
        assertEquals("15 Jan 2026", formatDisplayDate("2026-01-15T10:00:00"))
    }

    @Test
    fun isOverdueReturnsFalseForNull() {
        val today = LocalDate(2026, 11, 10)
        assertFalse(isOverdue(null, today))
    }

    @Test
    fun isOverdueReturnsFalseForUnparseableInput() {
        val today = LocalDate(2026, 11, 10)
        assertFalse(isOverdue("not a date", today))
    }

    @Test
    fun isOverdueReturnsTrueForDateBeforeToday() {
        val today = LocalDate(2026, 11, 10)
        assertTrue(isOverdue("2026-11-09T10:00:00", today))
    }

    @Test
    fun isOverdueReturnsFalseForDateEqualToToday() {
        val today = LocalDate(2026, 11, 10)
        assertFalse(isOverdue("2026-11-10T10:00:00", today))
    }

    @Test
    fun isOverdueReturnsFalseForDateAfterToday() {
        val today = LocalDate(2026, 11, 10)
        assertFalse(isOverdue("2026-11-11T10:00:00", today))
    }

    @Test
    fun timeAgoReturnsHoursForRecentEvents() {
        val event = LocalDateTime.parse("2026-11-17T10:00:00")
        val now = event.toInstant(TimeZone.currentSystemDefault()) + 2.hours

        assertEquals("2h ago", timeAgo("2026-11-17 10:00:00", now))
    }

    @Test
    fun timeAgoReturnsMinutesForVeryRecentEvents() {
        val event = LocalDateTime.parse("2026-11-17T10:00:00")
        val now = event.toInstant(TimeZone.currentSystemDefault()) + 15.minutes

        assertEquals("15 min ago", timeAgo("2026-11-17T10:00:00", now))
    }

    @Test
    fun timeAgoParsesBackendIsoTimestamps() {
        val event = Instant.parse("2026-11-17T10:00:00Z")
        val now = event + 2.hours

        assertEquals("2h ago", timeAgo("2026-11-17T10:00:00.000Z", now))
    }

    @Test
    fun timeAgoHandlesNullAndUnparseable() {
        assertEquals("", timeAgo(null))
        assertEquals("", timeAgo("not a date"))
    }
}
