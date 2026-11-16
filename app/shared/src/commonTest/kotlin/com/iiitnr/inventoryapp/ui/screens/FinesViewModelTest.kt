package com.iiitnr.inventoryapp.ui.screens

import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinesViewModelTest {
    private fun request(
        lateFeeAccrued: Int,
        returnedAt: String? = null,
        fineSettledAt: String? = null,
    ) = Request(
        id = "req-1",
        userId = "student-1",
        targetFacultyId = "faculty-1",
        projectTitle = "IoT weather station",
        status = RequestStatus.EXPIRED,
        createdAt = "2026-02-01T00:00:00Z",
        updatedAt = "2026-02-01T00:00:00Z",
        lateFeeAccrued = lateFeeAccrued,
        returnedAt = returnedAt,
        fineSettledAt = fineSettledAt,
    )

    @Test
    fun totalOwedSumsLateFeeAccruedAcrossNonReturnedFines() {
        val fines = listOf(request(10), request(20), request(30))

        assertEquals(60, computeTotalOwed(fines))
    }

    @Test
    fun totalOwedIsZeroWhenFinesIsEmpty() {
        assertEquals(0, computeTotalOwed(emptyList()))
    }

    @Test
    fun isPayableFineIncludesAReturnedUnsettledFine() {
        val returned = request(lateFeeAccrued = 10, returnedAt = "2026-03-01T00:00:00Z", fineSettledAt = null)

        assertTrue(isPayableFine(returned))
    }

    @Test
    fun isPayableFineExcludesAReturnedSettledFine() {
        val settled =
            request(
                lateFeeAccrued = 10,
                returnedAt = "2026-03-01T00:00:00Z",
                fineSettledAt = "2026-03-02T00:00:00Z",
            )

        assertFalse(isPayableFine(settled))
    }
}
