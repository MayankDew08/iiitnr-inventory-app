package com.iiitnr.inventoryapp.ui.screens

import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestStatus
import com.iiitnr.inventoryapp.data.models.User
import com.iiitnr.inventoryapp.data.models.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinesAdminViewModelTest {
    private val userA = User(id = "A", email = "a@example.com", name = "Student A", role = UserRole.STUDENT)
    private val userB = User(id = "B", email = "b@example.com", name = "Student B", role = UserRole.STUDENT)

    private fun request(
        id: String,
        user: User,
        lateFeeAccrued: Int,
        returnedAt: String? = null,
        fineSettledAt: String? = null,
    ) = Request(
        id = id,
        userId = user.id,
        targetFacultyId = "faculty-1",
        projectTitle = "Project $id",
        status = RequestStatus.EXPIRED,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        user = user,
        lateFeeAccrued = lateFeeAccrued,
        returnedAt = returnedAt,
        fineSettledAt = fineSettledAt,
    )

    private val mixedFixture =
        listOf(
            request("r1", userA, 10),
            request("r2", userA, 20),
            request("r3", userB, 30),
            request("r4", userB, 5),
        )

    @Test
    fun groupsRequestsByStudentAndSumsTheirTotals() {
        val students = computeStudentsWithFines(mixedFixture)

        assertEquals(2, students.size)
        assertEquals(30, students.first { it.userId == "A" }.total)
        assertEquals(35, students.first { it.userId == "B" }.total)
    }

    @Test
    fun sortsStudentsByTotalDescending() {
        val students = computeStudentsWithFines(mixedFixture)

        assertEquals("B", students.first().userId)
    }

    @Test
    fun grandTotalSumsAcrossAllStudents() {
        val students = computeStudentsWithFines(mixedFixture)

        assertEquals(65, students.sumOf { it.total })
    }

    @Test
    fun includesFinalizedUnpaidFinesEvenAfterReturn() {
        // A returned request whose fine is finalized but not yet settled is
        // exactly what the student's Pay flow covers; the LA must see it too.
        val students =
            computeStudentsWithFines(
                listOf(request("r1", userA, 50, returnedAt = "2026-01-05T00:00:00Z")),
            )

        assertEquals(1, students.size)
        assertEquals(50, students.first().total)
    }

    @Test
    fun excludesSettledFinesEvenIfLateFeeAccruedIsPositive() {
        val students =
            computeStudentsWithFines(
                listOf(
                    request(
                        "r1",
                        userA,
                        50,
                        returnedAt = "2026-01-05T00:00:00Z",
                        fineSettledAt = "2026-01-06T00:00:00Z",
                    ),
                ),
            )

        assertTrue(students.isEmpty())
    }

    @Test
    fun excludesRequestsWithZeroLateFeeAccrued() {
        val students = computeStudentsWithFines(listOf(request("r1", userA, 0)))

        assertTrue(students.isEmpty())
    }

    @Test
    fun requestCountCountsRequestsPerStudentCorrectly() {
        val students = computeStudentsWithFines(mixedFixture)

        assertEquals(2, students.first { it.userId == "A" }.requestCount)
        assertEquals(2, students.first { it.userId == "B" }.requestCount)
    }
}
