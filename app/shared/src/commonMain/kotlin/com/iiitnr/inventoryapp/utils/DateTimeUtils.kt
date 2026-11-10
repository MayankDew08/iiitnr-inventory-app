package com.iiitnr.inventoryapp.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Clock

fun currentSystemDate(): LocalDate =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date

val LocalToday =
    staticCompositionLocalOf<LocalDate> {
        currentSystemDate()
    }

val currentToday: LocalDate
    @Composable @ReadOnlyComposable
    get() = LocalToday.current

fun getRelativeDays(
    dateTimeString: String?,
    today: LocalDate,
): String {
    if (dateTimeString == null) return ""
    val dateTime =
        try {
            LocalDateTime.parse(dateTimeString.replace(' ', 'T'))
        } catch (e: Exception) {
            return ""
        }
    val date = dateTime.date

    val days = date.toEpochDays() - today.toEpochDays()

    return if (days == 0L) {
        "Today"
    } else if (days < 0L) {
        "${abs(days)}d ago"
    } else {
        "Due in ${abs(days)}d"
    }
}

fun buildDatesLine(
    request: Request,
    today: LocalDate,
): String {
    val tokens = mutableListOf<String>()
    request.returnDueAt?.let {
        if (request.status != RequestStatus.RETURNED) {
            var s = getRelativeDays(it, today)
            if (s.startsWith("Due in ")) s = "Due: " + s.removePrefix("Due in ")
            tokens += s
        }
    }
    tokens += "Created: ${getRelativeDays(request.createdAt, today)}"
    request.fulfilledAt?.let { tokens += "Issued: ${getRelativeDays(it, today)}" }
    request.lastRenewDate?.let { tokens += "Renewed: ${getRelativeDays(it, today)}" }
    request.returnedAt?.let { tokens += "Returned: ${getRelativeDays(it, today)}" }
    if (request.lateFeeAccrued > 0) {
        tokens += "Charge: ₹${request.lateFeeAccrued}"
    }
    return tokens.joinToString(" · ")
}
