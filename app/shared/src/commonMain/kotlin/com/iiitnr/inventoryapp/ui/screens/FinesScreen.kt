package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.iiitnr.inventoryapp.ui.components.requests.RequestDetailDialog
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

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Fines",
                onNavigateBack = onNavigateBack,
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                viewModel.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                viewModel.errorMessage != null -> {
                    FinesErrorState(
                        detail = viewModel.errorMessage,
                        onRetry = { viewModel.load() },
                    )
                }

                viewModel.fines.isEmpty() -> {
                    FinesEmptyState()
                }

                else -> {
                    FinesContent(
                        fines = viewModel.fines,
                        total = viewModel.totalOwed,
                        today = today,
                        onCardClick = { request -> selectedRequest = request },
                    )
                }
            }
        }
    }

    selectedRequest?.let { request ->
        RequestDetailDialog(
            request = request,
            onDismiss = { selectedRequest = null },
        )
    }
}

@Composable
private fun FinesContent(
    fines: List<Request>,
    total: Int,
    today: LocalDate,
    onCardClick: (Request) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "Overdue",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }

        items(fines, key = { it.id }) { request ->
            FineCard(
                request = request,
                today = today,
                onClick = onCardClick,
            )
        }

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
                        text = "${fines.size} overdue request${if (fines.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "₹$total",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
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
}

@Composable
private fun FineCard(
    request: Request,
    today: LocalDate,
    onClick: (Request) -> Unit,
) {
    Card(
        onClick = { onClick(request) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(),
        elevation = CardDefaults.cardElevation(),
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

            Spacer(Modifier.height(4.dp))

            Text(
                text = itemsSummary(request),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = daysLateLabel(request.returnDueAt, today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
