package com.iiitnr.inventoryapp.ui.screens

import com.iiitnr.inventoryapp.data.models.Component
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestItem
import com.iiitnr.inventoryapp.data.models.RequestStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestsCsvExportTest {
    private val component =
        Component(
            id = "comp-1",
            name = "Flame Sensor",
            totalQuantity = 10,
            availableQuantity = 4,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )

    private fun request(
        lateFeeAccrued: Int = 0,
        returnDueAt: String? = "2026-03-01T00:00:00Z",
        items: List<RequestItem> = emptyList(),
    ) = Request(
        id = "req-1",
        userId = "student-1",
        targetFacultyId = "faculty-1",
        projectTitle = "IoT weather station",
        status = RequestStatus.EXPIRED,
        createdAt = "2026-02-01T00:00:00Z",
        updatedAt = "2026-02-01T00:00:00Z",
        returnDueAt = returnDueAt,
        lateFeeAccrued = lateFeeAccrued,
        items = items,
    )

    @Test
    fun headerCarriesTheDueDateAndTheLateFee() {
        val csv = buildRequestsCsv(listOf(request()))

        val header = csv.lines().first()
        assertEquals(
            "Request ID,User Email,User Name,Target Faculty,Project Title,Status,Created At,Return Due At,Late Fee (INR),Items Summary",
            header,
        )
    }

    @Test
    fun rowCarriesTheDueDateAndTheAccruedFee() {
        val csv = buildRequestsCsv(listOf(request(lateFeeAccrued = 30)))
        val row = csv.lines()[1]

        assertTrue(row.contains("2026-03-01T00:00:00Z"), "due date missing: $row")
        assertTrue(row.contains(",30,"), "late fee missing: $row")
    }

    @Test
    fun itemSummaryReportsTheWrittenOffQuantity() {
        val item =
            RequestItem(
                id = "item-1",
                requestId = "req-1",
                componentId = "comp-1",
                quantity = 5,
                fulfilledQuantity = 5,
                returnedQuantity = 2,
                writtenOffQuantity = 3,
                component = component,
            )

        val csv = buildRequestsCsv(listOf(request(items = listOf(item))))
        val row = csv.lines()[1]

        assertTrue(row.contains("Flame Sensor (Req:5, Iss:5, Ret:2, Wrn:3)"), "item summary wrong: $row")
    }

    @Test
    fun aRequestWithoutAChargeStillExports() {
        val csv = buildRequestsCsv(listOf(request(lateFeeAccrued = 0)))
        val row = csv.lines()[1]

        assertTrue(row.contains(",0,"), "fee should be zero, not blank: $row")
    }
}
