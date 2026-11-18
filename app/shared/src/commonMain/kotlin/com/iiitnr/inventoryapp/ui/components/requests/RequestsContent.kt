package com.iiitnr.inventoryapp.ui.components.requests

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestStatus
import com.iiitnr.inventoryapp.ui.components.common.EmptyState
import com.iiitnr.inventoryapp.ui.components.common.ErrorContent
import com.iiitnr.inventoryapp.ui.components.common.LoadingIndicator

private fun filteredEmptyMessage(
    statusFilter: RequestStatus?,
    searchQuery: String,
): String? {
    if (searchQuery.isNotBlank()) {
        return "No requests match your search"
    }

    return when (statusFilter) {
        RequestStatus.PENDING -> "No pending requests"
        RequestStatus.APPROVED -> "No approved requests"
        RequestStatus.REJECTED -> "No rejected requests"
        RequestStatus.ISSUED -> "No issued requests"
        RequestStatus.PARTIALLY_ISSUED -> "No partially issued requests"
        RequestStatus.REQUESTED_RENEW -> "No renewal requests"
        RequestStatus.PARTIALLY_RETURNED -> "No partially returned requests"
        RequestStatus.RENEWED -> "No renewed requests"
        RequestStatus.RETURNED -> "No returned requests"
        RequestStatus.EXPIRED -> "No expired requests"
        null -> null
    }
}

@Composable
fun RequestsContent(
    modifier: Modifier = Modifier,
    isLoading: Boolean,
    errorMessage: String?,
    requests: List<Request>,
    allRequests: List<Request> = requests,
    statusFilter: RequestStatus? = null,
    searchQuery: String = "",
    onRetry: () -> Unit,
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
    selectedRequestId: String? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> LoadingIndicator()
            errorMessage != null -> ErrorContent(errorMessage, onRetry)
            requests.isEmpty() && allRequests.isEmpty() ->
                EmptyState(
                    message = if (isFaculty) "No pending requests" else "No requests yet",
                    subtitle = if (!isFaculty) "Tap the + button to create a request" else null,
                )

            requests.isEmpty() -> {
                val message =
                    filteredEmptyMessage(statusFilter, searchQuery)
                        ?: if (isFaculty) "No pending requests" else "No matching requests"
                EmptyState(message = message)
            }

            else ->
                RequestsList(
                    requests = requests,
                    onDeleteRequest = onDeleteRequest,
                    onApproveRequest = onApproveRequest,
                    onRejectRequest = onRejectRequest,
                    onFulfillRequest = onFulfillRequest,
                    onReturnRequest = onReturnRequest,
                    onRequestRenew = onRequestRenew,
                    onApproveRenew = onApproveRenew,
                    onShowQr = onShowQr,
                    onCardClick = onCardClick,
                    isFaculty = isFaculty,
                    selectedRequestId = selectedRequestId,
                )
        }
    }
}
