package com.iiitnr.inventoryapp.ui.components.requests

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AssignmentReturn
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.Component
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestItem
import com.iiitnr.inventoryapp.data.models.RequestStatus
import com.iiitnr.inventoryapp.data.models.User
import com.iiitnr.inventoryapp.data.models.UserRole
import com.iiitnr.inventoryapp.ui.components.common.InventoryTable
import com.iiitnr.inventoryapp.ui.components.common.TableColumn
import com.iiitnr.inventoryapp.ui.components.components.ComponentImage
import com.iiitnr.inventoryapp.ui.theme.AppTheme
import com.iiitnr.inventoryapp.ui.theme.inventoryColors
import com.iiitnr.inventoryapp.utils.compactUserLabel
import com.iiitnr.inventoryapp.utils.getRelativeDays
import kotlinx.datetime.LocalDate

@Composable
fun RequestDetailDialog(
    request: Request,
    onDeleteRequest: ((String) -> Unit)? = null,
    onApproveRequest: ((String) -> Unit)? = null,
    onRejectRequest: ((String) -> Unit)? = null,
    onFulfillRequest: ((String) -> Unit)? = null,
    onReturnRequest: ((String) -> Unit)? = null,
    onRequestRenew: ((String) -> Unit)? = null,
    onApproveRenew: ((String) -> Unit)? = null,
    onShowQr: ((Request) -> Unit)? = null,
    isFaculty: Boolean = false,
    onDismiss: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = {
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
    }, confirmButton = {
        TextButton(onClick = onDismiss) {
            Text("Close")
        }
    }, text = {
        RequestDetailDialogContent(
            request,
        )
    })
}

@Composable
fun RequestDetailDialogContent(
    request: Request,
    modifier: Modifier = Modifier,
) {
    val today = com.iiitnr.inventoryapp.utils.currentToday

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        item {
            RequestDetailDatesFlow(request = request, today = today)
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                request.user?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Requester",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = compactUserLabel(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                request.targetFaculty?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.School,
                            contentDescription = "Faculty",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = compactUserLabel(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Text(
                text = "Components",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            val hasIssued = request.items.any { it.fulfilledQuantity > 0 }
            val hasReturned = request.items.any { it.returnedQuantity > 0 }
            val showReqColumn =
                (!hasIssued && !hasReturned) || request.items.any { it.quantity != it.fulfilledQuantity }

            val componentWeight =
                when {
                    !hasIssued && !hasReturned -> 0.75f
                    hasIssued && !hasReturned -> if (showReqColumn) 0.58f else 0.79f
                    else -> if (showReqColumn) 0.52f else 0.68f
                }

            InventoryTable(
                items = request.items,
                columns =
                    buildList {
                        add(
                            TableColumn(
                                header = "Component",
                                weight = componentWeight,
                                content = { item ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ComponentImage(
                                            imageUrl = item.component?.imageUrl,
                                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = item.component?.name ?: item.componentId ?: "Unknown",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            )
                                            item.component?.description?.let { desc ->
                                                Text(
                                                    text = desc,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                )
                                            }
                                        }
                                    }
                                },
                            ),
                        )
                        if (showReqColumn) {
                            add(
                                TableColumn(
                                    header = "REQ",
                                    weight = if (!hasIssued && !hasReturned) 0.25f else 0.21f,
                                    alignment = Alignment.Center,
                                    content = { item ->
                                        Text(
                                            text = item.quantity.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                ),
                            )
                        }
                        if (hasIssued) {
                            add(
                                TableColumn(
                                    header = "ISS",
                                    weight = 0.21f,
                                    alignment = Alignment.Center,
                                    content = { item ->
                                        Text(
                                            text = item.fulfilledQuantity.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color =
                                                if (item.fulfilledQuantity > 0) {
                                                    MaterialTheme.inventoryColors.info
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                        )
                                    },
                                ),
                            )
                        }
                        if (hasReturned) {
                            add(
                                TableColumn(
                                    header = "RET",
                                    weight = 0.16f,
                                    alignment = Alignment.Center,
                                    content = { item ->
                                        Text(
                                            text = item.returnedQuantity.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color =
                                                if (item.returnedQuantity > 0) {
                                                    MaterialTheme.inventoryColors.neutral
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                        )
                                    },
                                ),
                            )
                        }
                    },
            )
        }
    }
}

@Composable
private fun RequestDetailDatesFlow(
    request: Request,
    today: LocalDate,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DateItem(
            icon = Icons.Default.History,
            label = "Created",
            value = getRelativeDays(request.createdAt, today),
        )

        request.fulfilledAt?.let {
            DateItem(
                icon = Icons.Default.CheckCircle,
                label = "Issued",
                value = getRelativeDays(it, today),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        request.returnDueAt?.let {
            if (request.status != RequestStatus.RETURNED) {
                DateItem(
                    icon = Icons.Default.Timer,
                    label = "Return Due",
                    value = getRelativeDays(it, today),
                    tint =
                        if (getRelativeDays(it, today).contains("ago")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                )
            }
        }

        if (request.lateFeeAccrued > 0) {
            DateItem(
                icon = Icons.Default.Payments,
                label = "Late Charge",
                value = "₹${request.lateFeeAccrued}",
                tint = MaterialTheme.colorScheme.error,
            )
        }

        request.returnedAt?.let {
            DateItem(
                icon = Icons.AutoMirrored.Filled.AssignmentReturn,
                label = "Returned",
                value = getRelativeDays(it, today),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        request.lastRenewDate?.let {
            DateItem(
                icon = Icons.Default.Refresh,
                label = "Last Renewed",
                value = getRelativeDays(it, today),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            request.lastRenewReason?.takeIf { reason -> reason.isNotBlank() }?.let { reason ->
                Row(
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Reason: ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DateItem(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}

@Preview
@Composable
fun RequestDetailDialogContentPreview() {
    val sampleStudent =
        User(
            id = "s1",
            name = "John Doe",
            role = UserRole.STUDENT,
            email = "student24100@iiitnr.edu.in",
            batch = "2024-28",
            branch = "CSE",
        )

    val sampleFaculty =
        User(
            id = "f1",
            name = "Faculty A",
            role = UserRole.FACULTY,
            email = "faculty@iiitnr.edu.in",
        )

    val sampleComponent =
        Component(
            id = "c1",
            name = "Arduino Uno",
            description = "Classic microcontroller board for IoT projects",
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
            status = RequestStatus.RENEWED,
            createdAt = "2026-07-11T10:00:00",
            updatedAt = "2026-08-11T10:00:00",
            fulfilledAt = "2026-07-12T10:00:00",
            lastRenewDate = "2026-08-01T10:00:00",
            lastRenewReason = "Need more time for field testing.",
            returnedAt = "2026-08-11T15:00:00",
            items =
                listOf(
                    RequestItem(
                        id = "ri1",
                        requestId = "r1",
                        componentId = "c1",
                        quantity = 5,
                        fulfilledQuantity = 5,
                        returnedQuantity = 1,
                        component = sampleComponent,
                    ),
                    RequestItem(
                        id = "ri2",
                        requestId = "r1",
                        componentId = "c1",
                        quantity = 2,
                        fulfilledQuantity = 2,
                        returnedQuantity = 2,
                        component =
                            sampleComponent.copy(
                                name = "DHT11 Sensor",
                                description = "Temperature and Humidity sensor",
                            ),
                    ),
                    RequestItem(
                        id = "ri3",
                        requestId = "r1",
                        componentId = "c1",
                        quantity = 1,
                        fulfilledQuantity = 1,
                        returnedQuantity = 0,
                        component = sampleComponent.copy(name = "ESP8266", description = "WiFi Module"),
                    ),
                ),
            user = sampleStudent,
            targetFaculty = sampleFaculty,
            returnDueAt = "2026-08-20T10:00:00",
        )

    AppTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            RequestDetailDialogContent(
                request = sampleRequest,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
