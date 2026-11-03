package com.iiitnr.inventoryapp.ui.components.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AssignmentReturn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestStatus

@Composable
fun RequestCardActions(
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
    when (request.status) {
        RequestStatus.PENDING ->
            PendingRequestActions(
                request = request,
                isFaculty = isFaculty,
                onDeleteRequest = onDeleteRequest,
                onApproveRequest = onApproveRequest,
                onRejectRequest = onRejectRequest,
            )

        RequestStatus.APPROVED ->
            ApprovedRequestActions(
                request = request,
                isFaculty = isFaculty,
                onFulfillRequest = onFulfillRequest,
                onShowQr = onShowQr,
            )

        RequestStatus.ISSUED ->
            IssuedRequestActions(
                request = request,
                isFaculty = isFaculty,
                onReturnRequest = onReturnRequest,
                onRequestRenew = onRequestRenew,
                onShowQr = onShowQr,
            )

        RequestStatus.PARTIALLY_ISSUED ->
            IssuedRequestActions(
                request = request,
                isFaculty = isFaculty,
                onReturnRequest = onReturnRequest,
                onRequestRenew = onRequestRenew,
                onShowQr = onShowQr,
                onFulfillRequest = onFulfillRequest,
            )

        RequestStatus.REQUESTED_RENEW ->
            RequestedRenewActions(
                request = request,
                isFaculty = isFaculty,
                onApproveRenew = onApproveRenew,
            )

        RequestStatus.RENEWED,
        RequestStatus.PARTIALLY_RETURNED,
        ->
            RenewedRequestActions(
                request = request,
                isFaculty = isFaculty,
                onReturnRequest = onReturnRequest,
                onRequestRenew = onRequestRenew,
                onShowQr = onShowQr,
            )

        RequestStatus.EXPIRED ->
            ExpiredRequestActions(
                request = request,
                isFaculty = isFaculty,
                onReturnRequest = onReturnRequest,
                onShowQr = onShowQr,
            )

        else -> {}
    }
}

@Composable
private fun PendingRequestActions(
    request: Request,
    isFaculty: Boolean,
    onDeleteRequest: ((String) -> Unit)?,
    onApproveRequest: ((String) -> Unit)?,
    onRejectRequest: ((String) -> Unit)?,
) {
    if (isFaculty && onApproveRequest != null && onRejectRequest != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { onRejectRequest(request.id) }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Reject request",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = { onApproveRequest(request.id) }) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Approve request",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    } else if (!isFaculty && onDeleteRequest != null) {
        IconButton(onClick = { onDeleteRequest(request.id) }) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Retract request",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ApprovedRequestActions(
    request: Request,
    isFaculty: Boolean,
    onFulfillRequest: ((String) -> Unit)?,
    onShowQr: ((Request) -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!isFaculty && onShowQr != null) {
            IconButton(onClick = { onShowQr(request) }) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = "Show QR for LA to scan",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (onFulfillRequest != null) {
            IconButton(onClick = { onFulfillRequest(request.id) }) {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "Issue request",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun IssuedRequestActions(
    request: Request,
    isFaculty: Boolean,
    onReturnRequest: ((String) -> Unit)?,
    onRequestRenew: ((String) -> Unit)?,
    onShowQr: ((Request) -> Unit)?,
    onFulfillRequest: ((String) -> Unit)? = null,
) {
    RequestLifecycleActionRow(
        request = request,
        isFaculty = isFaculty,
        onReturnRequest = onReturnRequest,
        onRequestRenew = onRequestRenew,
        onShowQr = onShowQr,
        returnContentDescription = "Record return to inventory",
    )
    if (onFulfillRequest != null) {
        IconButton(onClick = { onFulfillRequest(request.id) }) {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Issue more items",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RequestedRenewActions(
    request: Request,
    isFaculty: Boolean,
    onApproveRenew: ((String) -> Unit)?,
) {
    if (isFaculty && onApproveRenew != null) {
        IconButton(onClick = { onApproveRenew(request.id) }) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Approve renewal request",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RenewedRequestActions(
    request: Request,
    isFaculty: Boolean,
    onReturnRequest: ((String) -> Unit)?,
    onRequestRenew: ((String) -> Unit)?,
    onShowQr: ((Request) -> Unit)?,
) {
    RequestLifecycleActionRow(
        request = request,
        isFaculty = isFaculty,
        onReturnRequest = onReturnRequest,
        onRequestRenew = onRequestRenew,
        onShowQr = onShowQr,
        returnContentDescription = "Record return to inventory",
    )
}

@Composable
private fun ExpiredRequestActions(
    request: Request,
    isFaculty: Boolean,
    onReturnRequest: ((String) -> Unit)?,
    onShowQr: ((Request) -> Unit)?,
) {
    val totalOutstanding =
        request.items.sumOf { (it.fulfilledQuantity - it.returnedQuantity).coerceAtLeast(0) }
    val totalHeld =
        request.items.sumOf {
            (it.fulfilledQuantity - it.returnedQuantity - it.writtenOffQuantity).coerceAtLeast(0)
        }
    val isReclaim = totalOutstanding > 0 && totalHeld == 0

    RequestLifecycleActionRow(
        request = request,
        isFaculty = isFaculty,
        onReturnRequest = onReturnRequest,
        onRequestRenew = null,
        onShowQr = onShowQr,
        returnContentDescription =
            if (isReclaim) "Reclaim written-off items to inventory" else "Record overdue return to inventory",
        returnTint = MaterialTheme.colorScheme.error,
        showRenew = false,
        qrContentDescription = "Show QR for LA to scan when returning overdue items",
    )
}

@Composable
private fun RequestLifecycleActionRow(
    request: Request,
    isFaculty: Boolean,
    onReturnRequest: ((String) -> Unit)?,
    onRequestRenew: ((String) -> Unit)?,
    onShowQr: ((Request) -> Unit)?,
    returnContentDescription: String,
    returnTint: Color = MaterialTheme.colorScheme.primary,
    showRenew: Boolean = true,
    qrContentDescription: String = "Show QR for LA to scan when returning items",
) {
    val hasReturnableItems = request.items.any { it.fulfilledQuantity - it.returnedQuantity > 0 }

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showRenew && !isFaculty && onRequestRenew != null) {
            IconButton(onClick = { onRequestRenew(request.id) }) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Request renewal",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (!isFaculty && onShowQr != null && hasReturnableItems) {
            IconButton(onClick = { onShowQr(request) }) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = qrContentDescription,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (onReturnRequest != null && hasReturnableItems) {
            IconButton(onClick = { onReturnRequest(request.id) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.AssignmentReturn,
                    contentDescription = returnContentDescription,
                    tint = returnTint,
                )
            }
        }
    }
}
