@file:Suppress("TooManyFunctions")

package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.Payment
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.LoadingIndicator
import com.iiitnr.inventoryapp.ui.components.common.NarrowContentWidth
import com.iiitnr.inventoryapp.ui.components.common.ProofThumbnail
import com.iiitnr.inventoryapp.ui.components.common.ResponsiveContent
import com.iiitnr.inventoryapp.ui.components.common.ScreenWidthClass
import com.iiitnr.inventoryapp.ui.components.common.WideContentWidth
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import com.iiitnr.inventoryapp.ui.components.requests.RequestCardHeader
import com.iiitnr.inventoryapp.ui.components.requests.RequestDetailDialog
import com.iiitnr.inventoryapp.ui.components.requests.RequestDetailDialogContent
import com.iiitnr.inventoryapp.utils.currentToday
import com.iiitnr.inventoryapp.utils.formatDisplayDate
import com.iiitnr.inventoryapp.utils.timeAgo
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun FinesScreen(
    onNavigateBack: () -> Unit,
    onStartPayment: () -> Unit,
    onResumePayment: () -> Unit,
    viewModel: FinesViewModel = koinViewModel(),
) {
    var selectedRequest by remember { mutableStateOf<Request?>(null) }
    val authToken by viewModel.token.collectAsState(initial = null)
    val today = currentToday
    val hasPending = pendingPayment(viewModel.payments) != null

    // A PENDING payment means the student started a transfer that may already
    // have gone through server-side; tapping Pay again before submitting the
    // UTR for that one risks a duplicate transfer whose UTR can never be
    // recorded (one-active-per-user index).
    PayAgainGuard(
        hasPending = hasPending,
        onStartPayment = onStartPayment,
        onResumePayment = onResumePayment,
    ) { guardedStartPayment ->
        ResponsiveContent(modifier = Modifier.fillMaxSize()) { widthClass ->
            val isExpanded = widthClass == ScreenWidthClass.Expanded
            val hasFines =
                !viewModel.isLoading &&
                    viewModel.errorMessage == null &&
                    (viewModel.fines.isNotEmpty() || viewModel.accruingFines.isNotEmpty())

            Scaffold(
                topBar = {
                    AppTopBar(
                        title = "Fines",
                        onNavigateBack = onNavigateBack,
                        maxContentWidth = if (isExpanded && hasFines) WideContentWidth else NarrowContentWidth,
                    )
                },
            ) { paddingValues ->
                FinesBody(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    viewModel = viewModel,
                    authToken = authToken,
                    today = today,
                    isExpanded = isExpanded,
                    selectedRequest = selectedRequest,
                    onSelect = { selectedRequest = it },
                    onStartPayment = guardedStartPayment,
                    onResumePayment = onResumePayment,
                )
            }

            if (!isExpanded) {
                selectedRequest?.let { request ->
                    RequestDetailDialog(
                        request = request,
                        onDismiss = { selectedRequest = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun PayAgainGuard(
    hasPending: Boolean,
    onStartPayment: () -> Unit,
    onResumePayment: () -> Unit,
    content: @Composable (guardedStartPayment: () -> Unit) -> Unit,
) {
    var confirmVisible by remember { mutableStateOf(false) }
    val guardedStartPayment: () -> Unit = {
        if (hasPending) confirmVisible = true else onStartPayment()
    }
    content(guardedStartPayment)
    if (confirmVisible) {
        PayAgainConfirmDialog(
            onConfirm = {
                confirmVisible = false
                onStartPayment()
            },
            onDismiss = { confirmVisible = false },
            onSubmitUtr = {
                confirmVisible = false
                onResumePayment()
            },
        )
    }
}

@Composable
private fun PayAgainConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onSubmitUtr: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pay again?") },
        text = {
            Text(
                "You already started a payment for these fines. If your transfer went through, " +
                    "submit the UTR instead — paying again now will send money that cannot be " +
                    "tracked back to your fines.",
            )
        },
        confirmButton = {
            Button(onClick = onSubmitUtr, modifier = Modifier.handCursor()) {
                Text("Submit UTR")
            }
        },
        dismissButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.handCursor()) {
                Text("Pay again anyway")
            }
        },
    )
}

@Composable
private fun FinesBody(
    viewModel: FinesViewModel,
    authToken: String?,
    today: LocalDate,
    isExpanded: Boolean,
    selectedRequest: Request?,
    onSelect: (Request) -> Unit,
    onStartPayment: () -> Unit,
    onResumePayment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            viewModel.isLoading -> {
                FinesCenteredState { LoadingIndicator() }
            }

            viewModel.errorMessage != null -> {
                FinesCenteredState {
                    FinesErrorState(
                        detail = viewModel.errorMessage,
                        onRetry = { viewModel.load() },
                    )
                }
            }

            viewModel.fines.isEmpty() && viewModel.accruingFines.isEmpty() && viewModel.payments.isEmpty() -> {
                FinesCenteredState { FinesEmptyState() }
            }

            isExpanded -> {
                FinesMasterDetail(
                    fines = viewModel.fines,
                    accruingFines = viewModel.accruingFines,
                    payments = viewModel.payments,
                    total = viewModel.totalOwed,
                    today = today,
                    selectedRequest = selectedRequest,
                    onSelect = onSelect,
                    onStartPayment = onStartPayment,
                    onResumePayment = onResumePayment,
                )
            }

            else -> {
                FinesCenteredState {
                    FinesContent(
                        fines = viewModel.fines,
                        accruingFines = viewModel.accruingFines,
                        payments = viewModel.payments,
                        authToken = authToken,
                        total = viewModel.totalOwed,
                        today = today,
                        onCardClick = onSelect,
                        onStartPayment = onStartPayment,
                        onResumePayment = onResumePayment,
                    )
                }
            }
        }
    }
}

/** Centres loading/error/empty states in the narrow [NarrowContentWidth] column. */
@Composable
private fun FinesCenteredState(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = NarrowContentWidth).fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun FinesContent(
    fines: List<Request>,
    accruingFines: List<Request>,
    payments: List<Payment>,
    authToken: String?,
    total: Int,
    today: LocalDate,
    onCardClick: (Request) -> Unit,
    onStartPayment: () -> Unit,
    onResumePayment: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        pendingPayment(payments)?.let { pending ->
            item(key = "pending-${pending.id}") {
                PendingPaymentCard(pending = pending, onResumePayment = onResumePayment)
            }
        }

        accruingSection(accruingFines = accruingFines, today = today, onCardClick = onCardClick)

        if (fines.isNotEmpty()) {
            item { FinesOverdueHeading() }
        }

        items(fines, key = { it.id }) { request ->
            FineCard(
                request = request,
                today = today,
                onClick = onCardClick,
            )
        }

        val submitted = submittedPayments(payments)
        if (submitted.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Awaiting verification",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(submitted, key = { "submitted-${it.id}" }) { payment ->
                PaymentRow(
                    title = "₹${payment.amount}",
                    detail = "Submitted ${timeAgo(payment.submittedAt)}",
                )
                payment.proofImageUrl?.let { proofUrl ->
                    ProofThumbnail(
                        url = proofUrl,
                        token = authToken,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }

        val rejected = rejectedPayments(payments)
        if (rejected.isNotEmpty()) {
            item(key = "earlier-attempts") {
                EarlierAttempts(rejected = rejected)
            }
        }

        if (fines.isNotEmpty()) {
            finesSummaryItems(
                total = total,
                fineCount = fines.size,
                canPay = canStartPayment(fines, payments),
                blockedHint = payBlockedHint(payments),
                hasPending = pendingPayment(payments) != null,
                onStartPayment = onStartPayment,
            )
        }
    }
}

@Composable
private fun PendingPaymentCard(
    pending: Payment,
    onResumePayment: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Finish your payment",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    "You started a payment of ₹${pending.amount} ${timeAgo(pending.createdAt)}. " +
                        "If you completed the transfer, submit the UTR to settle these fines.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onResumePayment,
                modifier = Modifier.fillMaxWidth().handCursor(),
            ) {
                Text("Submit UTR")
            }
        }
    }
}

@Composable
private fun PaymentRow(
    title: String,
    detail: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EarlierAttempts(rejected: List<Payment>) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text("Earlier attempts (${rejected.size})")
        }
        if (expanded) {
            rejected.forEach { payment ->
                PaymentRow(
                    title = "₹${payment.amount} · Rejected ${timeAgo(payment.rejectedAt)}",
                    detail = payment.rejectionReason ?: "No reason given",
                )
            }
        }
    }
}

/**
 * Two-pane layout for wide windows: the overdue fines list with the payment
 * summary on the left, the selected request's full detail inline on the right.
 */
@Composable
private fun FinesMasterDetail(
    fines: List<Request>,
    accruingFines: List<Request>,
    payments: List<Payment>,
    total: Int,
    today: LocalDate,
    selectedRequest: Request?,
    onSelect: (Request) -> Unit,
    onStartPayment: () -> Unit,
    onResumePayment: () -> Unit,
) {
    // Keep a valid selection whenever the fine list changes. Prefer payable
    // fines, fall back to accruing so the detail pane is never empty when a
    // request of either kind exists.
    LaunchedEffect(fines, accruingFines) {
        val all = fines + accruingFines
        if (selectedRequest == null || all.none { it.id == selectedRequest.id }) {
            all.firstOrNull()?.let(onSelect)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Row(
            modifier = Modifier.widthIn(max = WideContentWidth).fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(modifier = Modifier.width(440.dp).fillMaxHeight()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    pendingPayment(payments)?.let { pending ->
                        item(key = "pending-${pending.id}") {
                            PendingPaymentCard(pending = pending, onResumePayment = onResumePayment)
                        }
                    }

                    accruingSection(
                        accruingFines = accruingFines,
                        today = today,
                        selectedId = selectedRequest?.id,
                        onCardClick = onSelect,
                    )

                    if (fines.isNotEmpty()) {
                        item { FinesOverdueHeading() }
                    }

                    items(fines, key = { it.id }) { request ->
                        FineCard(
                            request = request,
                            today = today,
                            selected = request.id == selectedRequest?.id,
                            onClick = onSelect,
                        )
                    }

                    if (fines.isNotEmpty()) {
                        finesSummaryItems(
                            total = total,
                            fineCount = fines.size,
                            canPay = canStartPayment(fines, payments),
                            blockedHint = payBlockedHint(payments),
                            hasPending = pendingPayment(payments) != null,
                            onStartPayment = onStartPayment,
                        )
                    }
                }
            }

            FinesDetailPane(
                request = selectedRequest,
                modifier =
                    Modifier
                        .weight(1f)
                        .wrapContentHeight(Alignment.Top)
                        .padding(top = 16.dp, end = 16.dp),
            )
        }
    }
}

/**
 * Accruing fines are view-only: fines on expired-but-unreturned requests. The
 * amount is still growing day by day, so there is no Pay affordance and every
 * row carries a "so far" label so the figure is never mistaken for the final
 * bill.
 */
private fun LazyListScope.accruingSection(
    accruingFines: List<Request>,
    today: LocalDate,
    selectedId: String? = null,
    onCardClick: (Request) -> Unit,
) {
    if (accruingFines.isEmpty()) return

    item(key = "accruing-header") {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            Text(
                text = "Accruing",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "These fines grow each day until you return the item.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    items(accruingFines, key = { "accruing-${it.id}" }) { request ->
        AccruingFineCard(
            request = request,
            today = today,
            selected = request.id == selectedId,
            onClick = onCardClick,
        )
    }
}

@Composable
private fun AccruingFineCard(
    request: Request,
    today: LocalDate,
    selected: Boolean = false,
    onClick: (Request) -> Unit,
) {
    Card(
        onClick = { onClick(request) },
        modifier = Modifier.fillMaxWidth().handCursor().padding(horizontal = 16.dp, vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border =
            BorderStroke(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = request.projectTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "₹${request.lateFeeAccrued}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "so far",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = itemsSummary(request),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = daysLateLabel(request.returnDueAt, today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FinesOverdueHeading() {
    Text(
        text = "Overdue",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Total + payment call-to-action shared by both the single-column and master layouts. */
private fun LazyListScope.finesSummaryItems(
    total: Int,
    fineCount: Int,
    canPay: Boolean,
    blockedHint: String?,
    hasPending: Boolean,
    onStartPayment: () -> Unit,
) {
    item {
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(16.dp))
    }

    item {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "$fineCount payable fine${if (fineCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "₹$total",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    item {
        PayButtonBlock(
            total = total,
            canPay = canPay,
            blockedHint = blockedHint,
            hasPending = hasPending,
            onStartPayment = onStartPayment,
        )
    }
}

@Composable
private fun PayButtonBlock(
    total: Int,
    canPay: Boolean,
    blockedHint: String?,
    hasPending: Boolean,
    onStartPayment: () -> Unit,
) {
    Column {
        Spacer(Modifier.height(20.dp))
        // When a PENDING payment already exists the primary action lives on
        // PendingPaymentCard (Submit UTR). This bottom button becomes an
        // outlined escape hatch, and the screen gates it with a confirm.
        val buttonModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).handCursor()
        if (hasPending) {
            OutlinedButton(onClick = onStartPayment, enabled = canPay, modifier = buttonModifier) {
                Text("Pay again")
            }
        } else {
            Button(onClick = onStartPayment, enabled = canPay, modifier = buttonModifier) {
                Text("Pay ₹$total")
            }
        }
        if (!canPay && blockedHint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = blockedHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FineCard(
    request: Request,
    today: LocalDate,
    selected: Boolean = false,
    onClick: (Request) -> Unit,
) {
    Card(
        onClick = { onClick(request) },
        modifier = Modifier.fillMaxWidth().handCursor().padding(horizontal = 16.dp, vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border =
            BorderStroke(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = request.projectTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "₹${request.lateFeeAccrued}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = itemsSummary(request),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = daysLateLabel(request.returnDueAt, today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Inline detail pane for the selected overdue request on wide windows. */
@Composable
private fun FinesDetailPane(
    request: Request?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (request == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Select a fine to view details",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                RequestCardHeader(
                    request = request,
                    isFaculty = false,
                    onDeleteRequest = null,
                    onApproveRequest = null,
                    onRejectRequest = null,
                    onFulfillRequest = null,
                    onReturnRequest = null,
                    onRequestRenew = null,
                    onApproveRenew = null,
                    onShowQr = null,
                )
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                RequestDetailDialogContent(
                    request = request,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun itemsSummary(request: Request): String {
    if (request.items.isEmpty()) return "No items on this request"
    return request.items.joinToString(", ") { item ->
        val name = item.component?.name?.takeIf { it.isNotBlank() } ?: item.componentId ?: "Unknown item"
        "$name × ${item.quantity}"
    }
}

private fun daysLateLabel(
    returnDueAt: String?,
    today: LocalDate,
): String {
    val dueDate =
        try {
            returnDueAt?.let { LocalDateTime.parse(it.replace(' ', 'T')).date }
        } catch (e: Exception) {
            null
        } ?: return "Overdue"

    // Clamp instead of trusting the raw difference: a stale/incorrect due date
    // (or a client clock behind the server) could otherwise render "-2 days late".
    val daysLate = maxOf(0L, today.toEpochDays() - dueDate.toEpochDays())
    val unit = if (daysLate == 1L) "day" else "days"
    return "$daysLate $unit late · due ${formatDisplayDate(returnDueAt)}"
}

@Composable
private fun FinesEmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Payments,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Nothing to pay.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Return items on time to keep it that way.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FinesErrorState(
    detail: String?,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Couldn't load your fines.",
                style = MaterialTheme.typography.titleMedium,
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry) {
                Text("Try again")
            }
        }
    }
}
