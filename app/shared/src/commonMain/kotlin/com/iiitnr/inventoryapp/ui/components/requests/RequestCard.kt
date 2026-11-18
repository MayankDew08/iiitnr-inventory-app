package com.iiitnr.inventoryapp.ui.components.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.Component
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestItem
import com.iiitnr.inventoryapp.data.models.RequestStatus
import com.iiitnr.inventoryapp.data.models.User
import com.iiitnr.inventoryapp.data.models.UserRole
import com.iiitnr.inventoryapp.ui.components.common.StatusChip
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import com.iiitnr.inventoryapp.ui.components.common.requestStatusColor
import com.iiitnr.inventoryapp.ui.theme.AppTheme
import com.iiitnr.inventoryapp.ui.theme.LocalIsDarkTheme
import com.iiitnr.inventoryapp.utils.buildDatesLine
import com.iiitnr.inventoryapp.utils.compactUserLabel

@Composable
fun RequestCard(
    request: Request,
    onDeleteRequest: ((String) -> Unit)? = null,
    onApproveRequest: ((String) -> Unit)? = null,
    onRejectRequest: ((String) -> Unit)? = null,
    onFulfillRequest: ((String) -> Unit)? = null,
    onReturnRequest: ((String) -> Unit)? = null,
    onRequestRenew: ((String) -> Unit)? = null,
    onApproveRenew: ((String) -> Unit)? = null,
    onShowQr: ((Request) -> Unit)? = null,
    onCardClick: (Request) -> Unit,
    isFaculty: Boolean = false,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
) {
    val statusColor = requestStatusColor(request.status)
    val cardShape = RoundedCornerShape(12.dp)
    // Selection reuses the status hue so e.g. an Expired card gets a crisp red
    // border instead of a clashing primary blue one.
    val borderColor = if (isSelected) statusColor else statusColor.copy(alpha = 0.35f)
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Card(
        modifier = modifier.fillMaxWidth().handCursor().clickable { onCardClick(request) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = cardShape,
        border = BorderStroke(borderWidth, borderColor),
        colors =
            CardDefaults.cardColors(
                containerColor = requestCardColor(request.status, isSelected),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            RequestCardHeader(
                request = request,
                isFaculty = isFaculty,
                onDeleteRequest = onDeleteRequest,
                onApproveRequest = onApproveRequest,
                onRejectRequest = onRejectRequest,
                onFulfillRequest = onFulfillRequest,
                onReturnRequest = onReturnRequest,
                onRequestRenew = onRequestRenew,
                onApproveRenew = onApproveRenew,
                onShowQr = onShowQr,
            )
            Spacer(modifier = Modifier.height(8.dp))
            RequestCardMeta(request = request)
            Spacer(modifier = Modifier.height(12.dp))
            RequestCardItems(request = request)
        }
    }
}

@Composable
private fun RequestCardItems(request: Request) {
    Text(
        text = "Components",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(4.dp))
    request.items.forEach { item ->
        RequestItemRow(item = item)
    }
}

/**
 * Blends the status hue onto the card surface instead of overlaying a raw alpha:
 * a plain alpha tint over a light surface turns muddy at low values.
 */
@Composable
private fun requestCardColor(
    status: RequestStatus,
    isSelected: Boolean = false,
): Color {
    val isDark = LocalIsDarkTheme.current
    val tintFraction =
        if (isSelected) {
            if (isDark) 0.22f else 0.12f
        } else {
            if (isDark) 0.14f else 0.07f
        }
    return lerp(
        start = MaterialTheme.colorScheme.surfaceContainerLow,
        stop = requestStatusColor(status),
        fraction = tintFraction,
    )
}

@Composable
internal fun RequestCardHeader(
    request: Request,
    isFaculty: Boolean,
    onDeleteRequest: ((String) -> Unit)?,
    onApproveRequest: ((String) -> Unit)?,
    onRejectRequest: ((String) -> Unit)?,
    onFulfillRequest: ((String) -> Unit)?,
    onReturnRequest: ((String) -> Unit)?,
    onRequestRenew: ((String) -> Unit)?,
    onApproveRenew: ((String) -> Unit)?,
    onShowQr: ((Request) -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = request.projectTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            StatusChip(status = request.status)
        }
        RequestCardActions(
            request = request,
            isFaculty = isFaculty,
            onDeleteRequest = onDeleteRequest,
            onApproveRequest = onApproveRequest,
            onRejectRequest = onRejectRequest,
            onFulfillRequest = onFulfillRequest,
            onReturnRequest = onReturnRequest,
            onRequestRenew = onRequestRenew,
            onApproveRenew = onApproveRenew,
            onShowQr = onShowQr,
        )
    }
}

@Composable
private fun RequestCardMeta(request: Request) {
    val today = com.iiitnr.inventoryapp.utils.currentToday
    Text(
        text = buildDatesLine(request, today),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (request.user != null || request.targetFaculty != null) {
        Spacer(modifier = Modifier.height(4.dp))
        val requester = request.user?.let { compactUserLabel(it) }
        val faculty = request.targetFaculty?.let { it.name ?: it.email }
        val combined =
            when {
                requester != null && faculty != null -> "$requester  ← $faculty"
                requester != null -> requester
                faculty != null -> "Requested from: $faculty"
                else -> ""
            }
        Text(
            text = combined,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Preview
@Composable
fun RequestCardPreview() {
    val sampleUser =
        User(
            id = "s1",
            email = "john24100@iiitnr.edu.in",
            name = "John Doe",
            role = UserRole.STUDENT,
            batch = "2024-28",
            branch = "CSE",
        )

    val sampleFaculty =
        User(
            id = "f1",
            email = "faculty@iiitnr.edu.in",
            name = "Faculty A",
            role = UserRole.FACULTY,
        )

    val sampleComponent =
        Component(
            id = "c1",
            name = "Arduino Uno",
            totalQuantity = 10,
            availableQuantity = 5,
            createdAt = "2023-08-11T10:00:00",
            updatedAt = "2023-08-11T10:00:00",
        )

    val sampleRequest =
        Request(
            id = "r1",
            userId = "s1",
            targetFacultyId = "f1",
            projectTitle = "IoT Weather Station",
            status = RequestStatus.PENDING,
            createdAt = "2026-07-11T10:00:00",
            updatedAt = "2026-07-11T10:00:00",
            items =
                listOf(
                    RequestItem(
                        id = "ri1",
                        requestId = "r1",
                        componentId = "c1",
                        quantity = 2,
                        component = sampleComponent,
                    ),
                    RequestItem(
                        id = "ri2",
                        requestId = "r1",
                        componentId = "c1",
                        quantity = 5,
                        fulfilledQuantity = 3,
                        returnedQuantity = 2,
                        component = sampleComponent.copy(name = "Flame Sensor"),
                    ),
                ),
            user = sampleUser,
            targetFaculty = sampleFaculty,
        )

    AppTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RequestCard(
                    request = sampleRequest,
                    onDeleteRequest = {},
                    onCardClick = {},
                    isFaculty = false,
                )

                RequestCard(
                    request =
                        sampleRequest.copy(
                            status = RequestStatus.ISSUED,
                            returnDueAt = "2023-08-20T10:00:00",
                        ),
                    onReturnRequest = {},
                    onShowQr = {},
                    onCardClick = {},
                    isFaculty = false,
                )

                RequestCard(
                    request =
                        sampleRequest.copy(
                            status = RequestStatus.PARTIALLY_RETURNED,
                        ),
                    onApproveRequest = {},
                    onRejectRequest = {},
                    onCardClick = {},
                    isFaculty = true,
                )
            }
        }
    }
}
