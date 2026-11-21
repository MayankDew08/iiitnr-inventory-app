package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestItem
import com.iiitnr.inventoryapp.data.models.RequestStatus
import com.iiitnr.inventoryapp.data.models.UserRole
import com.iiitnr.inventoryapp.ui.components.common.ResponsiveContent
import com.iiitnr.inventoryapp.ui.components.common.ScreenWidthClass
import com.iiitnr.inventoryapp.ui.components.common.SearchBar
import com.iiitnr.inventoryapp.ui.components.common.WideContentWidth
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import com.iiitnr.inventoryapp.ui.components.requests.FulfillByIdDialog
import com.iiitnr.inventoryapp.ui.components.requests.RenewReasonDialog
import com.iiitnr.inventoryapp.ui.components.requests.RequestDetailDialog
import com.iiitnr.inventoryapp.ui.components.requests.RequestDetailPane
import com.iiitnr.inventoryapp.ui.components.requests.RequestQrDialog
import com.iiitnr.inventoryapp.ui.components.requests.RequestsContent
import com.iiitnr.inventoryapp.ui.components.requests.RequestsTopBar
import com.iiitnr.inventoryapp.ui.platform.QrScannerContent
import com.iiitnr.inventoryapp.ui.platform.isQrScanAvailable
import com.iiitnr.inventoryapp.ui.theme.AppTheme
import com.iiitnr.inventoryapp.utils.requestStatusDisplayLabel
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RequestsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToNotifications: () -> Unit = {},
    onExportCsv: ((String) -> Boolean)? = null,
    viewModel: RequestsViewModel = koinViewModel(),
    notificationsViewModel: NotificationsViewModel = koinViewModel(),
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val dialogState = remember { RequestsDialogState() }

    val requests = viewModel.requests
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage
    val isRefreshing = viewModel.isRefreshing
    val currentUser = viewModel.currentUser
    val filteredRequests = viewModel.filteredRequests
    val isFaculty = viewModel.currentUser?.role == UserRole.FACULTY
    val isAdminOrLA = viewModel.currentUser?.role == UserRole.ADMIN || viewModel.currentUser?.role == UserRole.LA
    val canExportCsv = viewModel.canExportCsv

    fun exportRequestsCsv() {
        val export = onExportCsv
        if (!canExportCsv || export == null || requests.isEmpty()) return
        viewModel.exportRequestsCsv { csvContent ->
            val success = csvContent != null && export(csvContent)
            scope.launch {
                snackbarHostState.showSnackbar(
                    message =
                        if (success) {
                            "Exported requests.csv successfully"
                        } else {
                            "Failed to export requests.csv"
                        },
                )
            }
        }
    }

    fun updateRequestStatusPartialIssue(request: Request) {
        dialogState.pendingPartialIssueRequest = request
        dialogState.issueItemsInput =
            request.items
                .mapNotNull { item ->
                    item.componentId?.let { componentId ->
                        componentId to (item.quantity - item.fulfilledQuantity).coerceAtLeast(0)
                    }
                }.toMap()
    }

    fun updateRequestStatusPartialReturn(request: Request) {
        dialogState.pendingPartialReturnRequest = request
        dialogState.returnItemsInput =
            request.items
                .filter { it.fulfilledQuantity > it.returnedQuantity }
                .mapNotNull { item ->
                    item.componentId?.let { componentId ->
                        componentId to (item.fulfilledQuantity - item.returnedQuantity).coerceAtLeast(0)
                    }
                }.toMap()
    }

    val onApproveRequest = { id: String -> viewModel.updateRequestStatus(id, RequestStatus.APPROVED) }
    val onRejectRequest = { id: String -> viewModel.updateRequestStatus(id, RequestStatus.REJECTED) }
    val onFulfillRequest = { requestId: String ->
        requests.firstOrNull { it.id == requestId }?.let(::updateRequestStatusPartialIssue)
        Unit
    }
    val onReturnRequest = { requestId: String ->
        requests.firstOrNull { it.id == requestId }?.let(::updateRequestStatusPartialReturn)
        Unit
    }
    val onApproveRenew = { id: String -> viewModel.updateRequestStatus(id, RequestStatus.RENEWED) }

    fun confirmPartialIssue() {
        val request = dialogState.pendingPartialIssueRequest ?: return
        val items = dialogState.issueItemsInput
        dialogState.pendingPartialIssueRequest = null
        dialogState.issueItemsInput = emptyMap()
        viewModel.submitPartialIssue(request.id, items)
    }

    fun confirmPartialReturn() {
        val request = dialogState.pendingPartialReturnRequest ?: return
        val items = dialogState.returnItemsInput
        dialogState.pendingPartialReturnRequest = null
        dialogState.returnItemsInput = emptyMap()
        viewModel.submitPartialReturn(request.id, items)
    }

    fun openScannedRequest(rawValue: String) {
        val input = rawValue.trim()
        if (input.isBlank()) return

        dialogState.showRequestIdDialog = false
        dialogState.showQrScanner = false

        viewModel.lookupRequest(input) { request ->
            if (request != null) {
                dialogState.selectedRequestForDetail = request
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Keeps the open detail (modal or inline pane) in sync with refreshed data,
    // so an approve/issue/return shows its new status immediately.
    LaunchedEffect(requests) {
        val current = dialogState.selectedRequestForDetail ?: return@LaunchedEffect
        requests.firstOrNull { it.id == current.id }?.let {
            dialogState.selectedRequestForDetail = it
        }
    }

    ResponsiveContent(modifier = Modifier.fillMaxSize()) { widthClass ->
        val isExpanded = widthClass == ScreenWidthClass.Expanded

        Box(modifier = Modifier.fillMaxSize()) {
            RequestsDialogs(
                dialogState = dialogState,
                showDetailDialog = !isExpanded,
                onDismissDelete = { dialogState.pendingDeleteRequestId = null },
                onConfirmDelete = { id -> viewModel.deleteRequest(id) },
                onDismissQr = { dialogState.requestToShowQr = null },
                onRenewReasonChange = { dialogState.renewReasonInput = it },
                onConfirmRenew = {
                    val id = dialogState.pendingRenewRequestId
                    val reasonInput = dialogState.renewReasonInput
                    dialogState.pendingRenewRequestId = null
                    dialogState.renewReasonInput = ""
                    submitRenewalIfValid(id, reasonInput) { requestId, reason ->
                        viewModel.updateRequestStatus(
                            requestId,
                            RequestStatus.REQUESTED_RENEW,
                            lastRenewReason = reason,
                        )
                    }
                },
                onDismissRenew = {
                    dialogState.pendingRenewRequestId = null
                    dialogState.renewReasonInput = ""
                },
                onRequestIdChange = { dialogState.requestIdInput = it },
                onConfirmRequestId = { openScannedRequest(dialogState.requestIdInput) },
                onDismissRequestId = {
                    dialogState.showRequestIdDialog = false
                    dialogState.requestIdInput = ""
                },
                onScanClick =
                    if (isAdminOrLA && isQrScanAvailable()) {
                        { dialogState.showQrScanner = true }
                    } else {
                        null
                    },
                onIssueItemQtyChange = { componentId, quantity ->
                    dialogState.issueItemsInput = dialogState.issueItemsInput + (componentId to quantity)
                },
                onConfirmPartialIssue = { confirmPartialIssue() },
                onDismissPartialIssue = {
                    dialogState.pendingPartialIssueRequest = null
                    dialogState.issueItemsInput = emptyMap()
                },
                onReturnItemQtyChange = { componentId, quantity ->
                    dialogState.returnItemsInput = dialogState.returnItemsInput + (componentId to quantity)
                },
                onConfirmPartialReturn = { confirmPartialReturn() },
                onDismissPartialReturn = {
                    dialogState.pendingPartialReturnRequest = null
                    dialogState.returnItemsInput = emptyMap()
                },
                onDismissRequestDetail = { dialogState.selectedRequestForDetail = null },
                isFaculty = isFaculty,
                onDeleteRequest = { id -> dialogState.pendingDeleteRequestId = id },
                onApproveRequest = onApproveRequest,
                onRejectRequest = onRejectRequest,
                onFulfillRequest = onFulfillRequest,
                onReturnRequest = onReturnRequest,
                onRequestRenew = { id ->
                    dialogState.pendingRenewRequestId = id
                    dialogState.renewReasonInput = ""
                },
                onApproveRenew = onApproveRenew,
                onShowQr = { request -> dialogState.requestToShowQr = request },
                onFetchOtp = { requestId ->
                    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                        viewModel.generateRequestOtp(requestId) { otp ->
                            if (continuation.isActive) {
                                continuation.resume(otp) { _, _, _ -> }
                            }
                        }
                    }
                },
            )

            RequestsScreenScaffold(
                onNavigateBack = onNavigateBack,
                searchQuery = viewModel.searchQuery,
                onSearchQueryChange = { viewModel.searchQuery = it },
                statusFilter = viewModel.statusFilter,
                onStatusFilterChange = { viewModel.statusFilter = it },
                isLoading = isLoading,
                errorMessage = errorMessage,
                requests = requests,
                filteredRequests = filteredRequests,
                onRetry = { viewModel.loadRequests() },
                isFaculty = isFaculty,
                isAdminOrLA = isAdminOrLA,
                canExportCsv = canExportCsv && requests.isNotEmpty(),
                onExportCsv = { exportRequestsCsv() },
                onScanRequestClick =
                    if (isAdminOrLA) {
                        {
                            dialogState.showRequestIdDialog = true
                            dialogState.requestIdInput = ""
                        }
                    } else {
                        null
                    },
                snackbarHostState = snackbarHostState,
                onDeleteRequestClick = { requestId -> dialogState.pendingDeleteRequestId = requestId },
                onApproveRequest = onApproveRequest,
                onRejectRequest = onRejectRequest,
                onFulfillRequest = onFulfillRequest,
                onReturnRequest = onReturnRequest,
                onRequestRenewClick = { requestId ->
                    dialogState.pendingRenewRequestId = requestId
                    dialogState.renewReasonInput = ""
                },
                onApproveRenew = onApproveRenew,
                onShowQr = { request -> dialogState.requestToShowQr = request },
                onCardClick = { request -> dialogState.selectedRequestForDetail = request },
                onNavigateToNotifications = onNavigateToNotifications,
                unreadNotificationCount = notificationsViewModel.unreadCount,
                widthClass = widthClass,
                selectedRequestId = dialogState.selectedRequestForDetail?.id,
                onSelectRequest = { request -> dialogState.selectedRequestForDetail = request },
                selectedRequest =
                    dialogState.selectedRequestForDetail?.let { selected ->
                        requests.firstOrNull { it.id == selected.id } ?: selected
                    },
            )

            RequestsQrScannerOverlay(
                visible = dialogState.showQrScanner,
                onResult = { rawValue ->
                    openScannedRequest(rawValue)
                    dialogState.showQrScanner = false
                },
                onCancel = {
                    dialogState.showQrScanner = false
                },
            )
        }
    }
}

@Composable
private fun RequestsDialogs(
    dialogState: RequestsDialogState,
    showDetailDialog: Boolean = true,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
    onDismissQr: () -> Unit,
    onRenewReasonChange: (String) -> Unit,
    onConfirmRenew: () -> Unit,
    onDismissRenew: () -> Unit,
    onRequestIdChange: (String) -> Unit,
    onConfirmRequestId: () -> Unit,
    onDismissRequestId: () -> Unit,
    onScanClick: (() -> Unit)?,
    // Partial issue/return dialogs
    onIssueItemQtyChange: (String, Int) -> Unit,
    onConfirmPartialIssue: () -> Unit,
    onDismissPartialIssue: () -> Unit,
    onReturnItemQtyChange: (String, Int) -> Unit,
    onConfirmPartialReturn: () -> Unit,
    onDismissPartialReturn: () -> Unit,
    onDismissRequestDetail: () -> Unit,
    isFaculty: Boolean,
    onDeleteRequest: ((String) -> Unit)?,
    onApproveRequest: ((String) -> Unit)?,
    onRejectRequest: ((String) -> Unit)?,
    onFulfillRequest: ((String) -> Unit)?,
    onReturnRequest: ((String) -> Unit)?,
    onRequestRenew: ((String) -> Unit)?,
    onApproveRenew: ((String) -> Unit)?,
    onShowQr: ((Request) -> Unit)?,
    onFetchOtp: (suspend (String) -> String?)? = null,
) {
    val selectedRequestForDetail = dialogState.selectedRequestForDetail
    if (showDetailDialog && selectedRequestForDetail != null) {
        RequestDetailDialog(
            request = selectedRequestForDetail,
            onDeleteRequest = onDeleteRequest,
            onApproveRequest = onApproveRequest,
            onRejectRequest = onRejectRequest,
            onFulfillRequest = onFulfillRequest,
            onReturnRequest = onReturnRequest,
            onRequestRenew = onRequestRenew,
            onApproveRenew = onApproveRenew,
            onShowQr = onShowQr,
            isFaculty = isFaculty,
            onDismiss = onDismissRequestDetail,
        )
    }

    val pendingDeleteRequestId = dialogState.pendingDeleteRequestId
    if (pendingDeleteRequestId != null) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Retract request?") },
            text = { Text("This will delete the request permanently") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissDelete()
                        onConfirmDelete(pendingDeleteRequestId)
                    },
                ) {
                    Text("Retract", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) {
                    Text("Cancel")
                }
            },
        )
    }

    dialogState.requestToShowQr?.let { request ->
        RequestQrDialog(
            request = request,
            onDismiss = onDismissQr,
            onFetchOtp =
                if (onFetchOtp != null) {
                    { onFetchOtp(request.id) }
                } else {
                    null
                },
        )
    }

    if (dialogState.pendingRenewRequestId != null) {
        RenewReasonDialog(
            reason = dialogState.renewReasonInput,
            onReasonChange = onRenewReasonChange,
            onConfirm = onConfirmRenew,
            onDismiss = onDismissRenew,
        )
    }

    if (dialogState.showRequestIdDialog && !dialogState.showQrScanner) {
        FulfillByIdDialog(
            requestIdInput = dialogState.requestIdInput,
            onRequestIdChange = onRequestIdChange,
            dialogTitle = "Scan request QR / ID",
            confirmButtonLabel = "Review",
            onConfirm = onConfirmRequestId,
            onDismiss = onDismissRequestId,
            onScanClick = onScanClick,
        )
    }

    dialogState.pendingPartialIssueRequest?.let { request ->
        PartialIssueDialog(
            request = request,
            itemsInput = dialogState.issueItemsInput,
            onItemQtyChange = onIssueItemQtyChange,
            onConfirm = onConfirmPartialIssue,
            onDismiss = onDismissPartialIssue,
        )
    }

    dialogState.pendingPartialReturnRequest?.let { request ->
        PartialReturnDialog(
            request = request,
            itemsInput = dialogState.returnItemsInput,
            onItemQtyChange = onReturnItemQtyChange,
            onConfirm = onConfirmPartialReturn,
            onDismiss = onDismissPartialReturn,
        )
    }
}

@Composable
private fun RequestsScreenBody(
    paddingValues: PaddingValues,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    statusFilter: RequestStatus?,
    onStatusFilterChange: (RequestStatus?) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    requests: List<Request>,
    filteredRequests: List<Request>,
    onRetry: () -> Unit,
    isFaculty: Boolean,
    onDeleteRequest: ((String) -> Unit)?,
    onApproveRequest: ((String) -> Unit)?,
    onRejectRequest: ((String) -> Unit)?,
    onFulfillRequest: ((String) -> Unit)?,
    onReturnRequest: ((String) -> Unit)?,
    onRequestRenew: ((String) -> Unit)?,
    onApproveRenew: ((String) -> Unit)?,
    onShowQr: ((Request) -> Unit)?,
    onCardClick: (Request) -> Unit,
    widthClass: ScreenWidthClass = ScreenWidthClass.Compact,
    selectedRequestId: String? = null,
    onSelectRequest: (Request) -> Unit = {},
    selectedRequest: Request? = null,
) {
    Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
        RequestsHeader(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            statusFilter = statusFilter,
            onStatusFilterChange = onStatusFilterChange,
        )

        if (widthClass == ScreenWidthClass.Expanded) {
            RequestsMasterDetail(
                requests = requests,
                filteredRequests = filteredRequests,
                isLoading = isLoading,
                errorMessage = errorMessage,
                statusFilter = statusFilter,
                searchQuery = searchQuery,
                isFaculty = isFaculty,
                onRetry = onRetry,
                onDeleteRequest = onDeleteRequest,
                onApproveRequest = onApproveRequest,
                onRejectRequest = onRejectRequest,
                onFulfillRequest = onFulfillRequest,
                onReturnRequest = onReturnRequest,
                onRequestRenew = onRequestRenew,
                onApproveRenew = onApproveRenew,
                onShowQr = onShowQr,
                onCardClick = onCardClick,
                selectedRequestId = selectedRequestId,
                selectedRequest = selectedRequest,
                onSelectRequest = onSelectRequest,
                modifier = Modifier.weight(1f),
            )
        } else {
            RequestsContent(
                modifier = Modifier.padding(),
                isLoading = isLoading,
                errorMessage = errorMessage,
                requests = filteredRequests,
                allRequests = requests,
                statusFilter = statusFilter,
                searchQuery = searchQuery.trim(),
                onRetry = onRetry,
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

/** Search + status filters, centred and capped above a divider. */
@Composable
private fun RequestsHeader(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    statusFilter: RequestStatus?,
    onStatusFilterChange: (RequestStatus?) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = WideContentWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SearchBar(
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                placeholder = "Search requests...",
                modifier = Modifier.fillMaxWidth(),
            )

            RequestStatusFilterRow(
                statusFilter = statusFilter,
                onStatusFilterChange = onStatusFilterChange,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Two-pane layout for wide windows: the request list on the left, the selected
 * request's full detail (with role-gated actions) on the right.
 */
@Composable
private fun RequestsMasterDetail(
    requests: List<Request>,
    filteredRequests: List<Request>,
    isLoading: Boolean,
    errorMessage: String?,
    statusFilter: RequestStatus?,
    searchQuery: String,
    isFaculty: Boolean,
    onRetry: () -> Unit,
    onDeleteRequest: ((String) -> Unit)?,
    onApproveRequest: ((String) -> Unit)?,
    onRejectRequest: ((String) -> Unit)?,
    onFulfillRequest: ((String) -> Unit)?,
    onReturnRequest: ((String) -> Unit)?,
    onRequestRenew: ((String) -> Unit)?,
    onApproveRenew: ((String) -> Unit)?,
    onShowQr: ((Request) -> Unit)?,
    onCardClick: (Request) -> Unit,
    selectedRequestId: String?,
    selectedRequest: Request?,
    onSelectRequest: (Request) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredIds = filteredRequests.map { it.id }

    // Keep a valid selection whenever the filter/search changes.
    LaunchedEffect(filteredIds, selectedRequestId) {
        if (selectedRequestId == null || filteredIds.none { it == selectedRequestId }) {
            filteredRequests.firstOrNull()?.let(onSelectRequest)
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier.widthIn(max = WideContentWidth).fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(modifier = Modifier.width(440.dp).fillMaxHeight()) {
                RequestsContent(
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    requests = filteredRequests,
                    allRequests = requests,
                    statusFilter = statusFilter,
                    searchQuery = searchQuery.trim(),
                    onRetry = onRetry,
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

            RequestDetailPane(
                request = selectedRequest,
                isFaculty = isFaculty,
                onDeleteRequest = onDeleteRequest,
                onApproveRequest = onApproveRequest,
                onRejectRequest = onRejectRequest,
                onFulfillRequest = onFulfillRequest,
                onReturnRequest = onReturnRequest,
                onRequestRenew = onRequestRenew,
                onApproveRenew = onApproveRenew,
                onShowQr = onShowQr,
                modifier =
                    Modifier
                        .weight(1f)
                        .wrapContentHeight(Alignment.Top)
                        .padding(top = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun RequestStatusFilterRow(
    statusFilter: RequestStatus?,
    onStatusFilterChange: (RequestStatus?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        items(REQUEST_STATUS_OPTIONS) { option ->
            val isSelected = statusFilter == option
            TextButton(
                onClick = { onStatusFilterChange(option) },
                modifier = Modifier.handCursor(),
            ) {
                Text(
                    text = if (option != null) requestStatusDisplayLabel(option) else "All",
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

private val REQUEST_STATUS_OPTIONS: List<RequestStatus?> = listOf(null) + RequestStatus.entries

private fun routeScannedRequestAction(
    request: Request,
    onStartPartialIssue: (Request) -> Unit,
    onShowActionSelection: (Request) -> Unit,
    onStartPartialReturn: (Request) -> Unit,
    onNoActionAvailable: (Request) -> Unit,
) {
    when (request.status) {
        RequestStatus.APPROVED -> onStartPartialIssue(request)
        RequestStatus.PARTIALLY_ISSUED -> onShowActionSelection(request)

        RequestStatus.ISSUED,
        RequestStatus.RENEWED,
        RequestStatus.EXPIRED,
        RequestStatus.PARTIALLY_RETURNED,
        -> onStartPartialReturn(request)

        else -> onNoActionAvailable(request)
    }
}

private fun submitRenewalIfValid(
    requestId: String?,
    reasonInput: String,
    onSubmit: (String, String) -> Unit,
) {
    val reason = reasonInput.trim()
    if (requestId != null && reason.isNotEmpty()) {
        onSubmit(requestId, reason)
    }
}

@Composable
private fun RequestsScreenScaffold(
    onNavigateBack: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    statusFilter: RequestStatus?,
    onStatusFilterChange: (RequestStatus?) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    requests: List<Request>,
    filteredRequests: List<Request>,
    onRetry: () -> Unit,
    isFaculty: Boolean,
    isAdminOrLA: Boolean,
    canExportCsv: Boolean,
    onExportCsv: () -> Unit,
    onScanRequestClick: (() -> Unit)?,
    snackbarHostState: SnackbarHostState,
    onDeleteRequestClick: (String) -> Unit,
    onApproveRequest: (String) -> Unit,
    onRejectRequest: (String) -> Unit,
    onFulfillRequest: (String) -> Unit,
    onReturnRequest: (String) -> Unit,
    onRequestRenewClick: (String) -> Unit,
    onApproveRenew: (String) -> Unit,
    onShowQr: (Request) -> Unit,
    onCardClick: (Request) -> Unit,
    onNavigateToNotifications: () -> Unit,
    unreadNotificationCount: Int,
    widthClass: ScreenWidthClass = ScreenWidthClass.Compact,
    selectedRequestId: String? = null,
    onSelectRequest: (Request) -> Unit = {},
    selectedRequest: Request? = null,
) {
    Scaffold(
        topBar = {
            RequestsTopBar(
                onNavigateBack = onNavigateBack,
                onScanRequestClick = onScanRequestClick,
                showExportCsv = canExportCsv,
                onExportCsv = onExportCsv,
                onNavigateToNotifications = onNavigateToNotifications,
                unreadNotificationCount = unreadNotificationCount,
                maxContentWidth = WideContentWidth,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        RequestsScreenBody(
            paddingValues = paddingValues,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            statusFilter = statusFilter,
            onStatusFilterChange = onStatusFilterChange,
            isLoading = isLoading,
            errorMessage = errorMessage,
            requests = requests,
            filteredRequests = filteredRequests,
            onRetry = onRetry,
            isFaculty = isFaculty,
            onDeleteRequest =
                if (isFaculty) {
                    null
                } else {
                    onDeleteRequestClick
                },
            onApproveRequest =
                if (isFaculty) {
                    onApproveRequest
                } else {
                    null
                },
            onRejectRequest =
                if (isFaculty) {
                    onRejectRequest
                } else {
                    null
                },
            onFulfillRequest =
                if (isAdminOrLA) {
                    onFulfillRequest
                } else {
                    null
                },
            onReturnRequest =
                if (isAdminOrLA) {
                    onReturnRequest
                } else {
                    null
                },
            onRequestRenew =
                if (!isFaculty && !isAdminOrLA) {
                    onRequestRenewClick
                } else {
                    null
                },
            onApproveRenew =
                if (isFaculty) {
                    onApproveRenew
                } else {
                    null
                },
            onShowQr =
                if (!isFaculty) {
                    onShowQr
                } else {
                    null
                },
            onCardClick = onCardClick,
            widthClass = widthClass,
            selectedRequestId = selectedRequestId,
            onSelectRequest = onSelectRequest,
            selectedRequest = selectedRequest,
        )
    }
}

@Composable
private fun RequestsQrScannerOverlay(
    visible: Boolean,
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    if (visible) {
        Box(modifier = Modifier.fillMaxSize()) {
            QrScannerContent(
                onResult = onResult,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
fun PartialIssueDialog(
    request: Request,
    itemsInput: Map<String, Int>,
    onItemQtyChange: (String, Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Issue Items") },
        text = {
            PartialIssueDialogContent(
                request = request,
                itemsInput = itemsInput,
                onItemQtyChange = onItemQtyChange,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Issue", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun PartialIssueDialogContent(
    request: Request,
    itemsInput: Map<String, Int> = emptyMap(),
    onItemQtyChange: (String, Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Text(
            text = request.projectTitle,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Adjust quantities to issue",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        request.items.filter { it.quantity - it.fulfilledQuantity > 0 }.forEach { item ->
            val compId = item.componentId ?: ""
            val total = item.quantity
            val fulfilled = item.fulfilledQuantity
            val remaining = (total - fulfilled).coerceAtLeast(0)
            val qty = itemsInput[compId] ?: 0
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.component?.name ?: compId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    val displayQty =
                        when {
                            fulfilled == 0 && item.returnedQuantity == 0 -> "$total"
                            total == fulfilled -> "$total / ${item.returnedQuantity}"
                            else -> "$total / $fulfilled / ${item.returnedQuantity}"
                        }
                    Text(
                        text = displayQty,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    val targetTotal = fulfilled + qty
                    OutlinedTextField(
                        value = targetTotal.toString(),
                        onValueChange = { text ->
                            val inputVal = text.toIntOrNull() ?: fulfilled
                            val newDelta = (inputVal - fulfilled).coerceIn(0, remaining)
                            onItemQtyChange(compId, newDelta)
                        },
                        modifier = Modifier.width(90.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("Issued") },
                        trailingIcon = {
                            Column(
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                IconButton(
                                    onClick = { onItemQtyChange(compId, (qty + 1).coerceAtMost(remaining)) },
                                    enabled = fulfilled + qty < total,
                                    modifier = Modifier.size(24.dp).handCursor(),
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Increase")
                                }
                                IconButton(
                                    onClick = { onItemQtyChange(compId, (qty - 1).coerceAtLeast(0)) },
                                    enabled = qty > 0,
                                    modifier = Modifier.size(24.dp).handCursor(),
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Decrease")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun PartialReturnDialog(
    request: Request,
    itemsInput: Map<String, Int>,
    onItemQtyChange: (String, Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Return Items (Partial)") },
        text = {
            PartialReturnDialogContent(
                request = request,
                itemsInput = itemsInput,
                onItemQtyChange = onItemQtyChange,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Return", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun PartialReturnDialogContent(
    request: Request,
    itemsInput: Map<String, Int> = emptyMap(),
    onItemQtyChange: (String, Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Text(
            text = request.projectTitle,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Adjust quantities to return",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        request.items.filter { it.fulfilledQuantity - it.returnedQuantity > 0 }.forEach { item ->
            val compId = item.componentId ?: ""
            val total = item.quantity
            val issued = item.fulfilledQuantity
            val returned = item.returnedQuantity
            val currentHeld = (issued - returned).coerceAtLeast(0)
            val qty = itemsInput[compId] ?: 0
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.component?.name ?: compId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    val displayQty =
                        when {
                            issued == 0 && returned == 0 -> "$total"
                            total == issued -> "$total / $returned"
                            else -> "$total / $issued / $returned"
                        }
                    Text(
                        text = displayQty,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    val targetTotal = returned + qty
                    OutlinedTextField(
                        value = targetTotal.toString(),
                        onValueChange = { text ->
                            val inputVal = text.toIntOrNull() ?: returned
                            val newDelta = (inputVal - returned).coerceIn(0, currentHeld)
                            onItemQtyChange(compId, newDelta)
                        },
                        modifier = Modifier.width(90.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("Returned") },
                        trailingIcon = {
                            Column(
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                IconButton(
                                    onClick = { onItemQtyChange(compId, (qty + 1).coerceAtMost(currentHeld)) },
                                    enabled = returned + qty < issued,
                                    modifier = Modifier.size(24.dp).handCursor(),
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Increase")
                                }
                                IconButton(
                                    onClick = { onItemQtyChange(compId, (qty - 1).coerceAtLeast(0)) },
                                    enabled = qty > 0,
                                    modifier = Modifier.size(24.dp).handCursor(),
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Decrease")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun PartialDialogsPreview() {
    val requestItems =
        listOf(
            RequestItem(
                id = "item1",
                componentId = "partially issued",
                quantity = 100,
                fulfilledQuantity = 99,
                returnedQuantity = 2,
            ),
            RequestItem(
                id = "item2",
                componentId = "fully issued",
                quantity = 5,
                fulfilledQuantity = 5,
                returnedQuantity = 2,
            ),
            RequestItem(
                id = "item3",
                componentId = "not issued",
                quantity = 10,
                fulfilledQuantity = 0,
                returnedQuantity = 0,
            ),
        )
    val request =
        Request(
            id = "req1",
            userId = "user1",
            targetFacultyId = "faculty1",
            projectTitle = "Issue Example",
            status = RequestStatus.PARTIALLY_ISSUED,
            createdAt = "2023-03-01T12:00:00Z",
            updatedAt = "2023-03-01T12:00:00Z",
            items = requestItems,
        )

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                PartialIssueDialogContent(
                    request = request,
                    onItemQtyChange = { _, _ -> },
                )
                PartialReturnDialogContent(
                    request = request.copy(projectTitle = "Return Example"),
                    onItemQtyChange = { _, _ -> },
                )
            }
        }
    }
}

@Stable
class RequestsDialogState {
    var pendingDeleteRequestId by mutableStateOf<String?>(null)
    var pendingRenewRequestId by mutableStateOf<String?>(null)
    var renewReasonInput by mutableStateOf("")
    var requestToShowQr by mutableStateOf<Request?>(null)
    var showRequestIdDialog by mutableStateOf(false)
    var showQrScanner by mutableStateOf(false)
    var requestIdInput by mutableStateOf("")
    var pendingPartialIssueRequest by mutableStateOf<Request?>(null)
    var issueItemsInput by mutableStateOf<Map<String, Int>>(emptyMap())
    var pendingPartialReturnRequest by mutableStateOf<Request?>(null)
    var returnItemsInput by mutableStateOf<Map<String, Int>>(emptyMap())
    var selectedRequestForDetail by mutableStateOf<Request?>(null)
}
