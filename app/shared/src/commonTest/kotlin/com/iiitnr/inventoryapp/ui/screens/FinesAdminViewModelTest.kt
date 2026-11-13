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
    fun excludesReturnedRequestsEvenIfLateFeeAccruedIsPositive() {
        val students = computeStudentsWithFines(listOf(request("r1", userA, 50, returnedAt = "2026-01-05T00:00:00Z")))

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
