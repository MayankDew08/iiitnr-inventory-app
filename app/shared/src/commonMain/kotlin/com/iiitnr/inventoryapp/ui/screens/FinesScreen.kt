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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.LoadingIndicator
import com.iiitnr.inventoryapp.ui.components.common.ResponsiveContent
import com.iiitnr.inventoryapp.ui.components.common.ScreenWidthClass
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import com.iiitnr.inventoryapp.ui.components.requests.RequestCardHeader
import com.iiitnr.inventoryapp.ui.components.requests.RequestDetailDialog
import com.iiitnr.inventoryapp.ui.components.requests.RequestDetailDialogContent
import com.iiitnr.inventoryapp.utils.currentToday
import com.iiitnr.inventoryapp.utils.formatDisplayDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun FinesScreen(
    onNavigateBack: () -> Unit,
    viewModel: FinesViewModel = koinViewModel(),
) {
    var selectedRequest by remember { mutableStateOf<Request?>(null) }
    val today = currentToday

    ResponsiveContent(modifier = Modifier.fillMaxSize()) { widthClass ->
        val isExpanded = widthClass == ScreenWidthClass.Expanded
        val hasFines = !viewModel.isLoading && viewModel.errorMessage == null && viewModel.fines.isNotEmpty()

        Scaffold(
            topBar = {
                AppTopBar(
                    title = "Fines",
                    onNavigateBack = onNavigateBack,
                    maxContentWidth = if (isExpanded && hasFines) 1280.dp else 800.dp,
                )
            },
        ) { paddingValues ->
            FinesBody(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                viewModel = viewModel,
                today = today,
                isExpanded = isExpanded,
                selectedRequest = selectedRequest,
                onSelect = { selectedRequest = it },
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

@Composable
private fun FinesBody(
    viewModel: FinesViewModel,
    today: LocalDate,
    isExpanded: Boolean,
    selectedRequest: Request?,
    onSelect: (Request) -> Unit,
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

            viewModel.fines.isEmpty() -> {
                FinesCenteredState { FinesEmptyState() }
            }

            isExpanded -> {
                FinesMasterDetail(
                    fines = viewModel.fines,
                    total = viewModel.totalOwed,
                    today = today,
                    selectedRequest = selectedRequest,
                    onSelect = onSelect,
                )
            }

            else -> {
                FinesContent(
                    fines = viewModel.fines,
                    total = viewModel.totalOwed,
                    today = today,
                    onCardClick = onSelect,
                )
            }
        }
    }
}

/** Centres loading/error/empty states in the narrow 800.dp column. */
@Composable
private fun FinesCenteredState(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = 800.dp).fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun FinesContent(
    fines: List<Request>,
    total: Int,
    today: LocalDate,
    onCardClick: (Request) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(modifier = Modifier.widthIn(max = 800.dp).fillMaxSize()) {
            item { FinesOverdueHeading() }

            items(fines, key = { it.id }) { request ->
                FineCard(
                    request = request,
                    today = today,
                    onClick = onCardClick,
                )
            }

            finesSummaryItems(total = total, fineCount = fines.size)
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
    total: Int,
    today: LocalDate,
    selectedRequest: Request?,
    onSelect: (Request) -> Unit,
) {
    // Keep a valid selection whenever the fine list changes.
    LaunchedEffect(fines) {
        if (selectedRequest == null || fines.none { it.id == selectedRequest.id }) {
            fines.firstOrNull()?.let(onSelect)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Row(
            modifier = Modifier.widthIn(max = 1280.dp).fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(modifier = Modifier.width(440.dp).fillMaxHeight()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item { FinesOverdueHeading() }

                    items(fines, key = { it.id }) { request ->
                        FineCard(
                            request = request,
                            today = today,
                            selected = request.id == selectedRequest?.id,
                            onClick = onSelect,
                        )
                    }

                    finesSummaryItems(total = total, fineCount = fines.size)
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
                    text = "$fineCount overdue request${if (fineCount == 1) "" else "s"}",
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
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text("Pay ₹$total")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Coming soon",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
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
                    text = "Select an overdue request to view details",
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
