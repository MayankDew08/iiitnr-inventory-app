package com.iiitnr.inventoryapp.utils

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
