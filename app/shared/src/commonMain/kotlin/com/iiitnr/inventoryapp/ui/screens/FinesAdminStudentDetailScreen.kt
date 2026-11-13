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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
fun FinesAdminStudentDetailScreen(
    userId: String,
    onNavigateBack: () -> Unit,
    viewModel: FinesAdminStudentDetailViewModel = koinViewModel(),
) {
    var selectedRequest by remember { mutableStateOf<Request?>(null) }
    val today = currentToday

    LaunchedEffect(userId) {
        viewModel.load(userId)
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = viewModel.studentName.ifEmpty { "Student" },
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
                    StudentDetailErrorState(
                        detail = viewModel.errorMessage,
                        onRetry = { viewModel.load(userId) },
                    )
                }

                viewModel.fines.isEmpty() -> {
                    StudentDetailEmptyState()
                }

                else -> {
                    StudentFinesContent(
                        fines = viewModel.fines,
                        total = viewModel.total,
                        today = today,
                        onRequestClick = { request -> selectedRequest = request },
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
private fun StudentFinesContent(
    fines: List<Request>,
    total: Int,
    today: LocalDate,
    onRequestClick: (Request) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "Overdue requests",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }

        items(fines, key = { it.id }) { request ->
            AdminFineCard(request = request, today = today, onClick = onRequestClick)
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
            ) {
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "₹$total",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AdminFineCard(
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
                text = adminItemsSummary(request),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = adminDaysLateLabel(request.returnDueAt, today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun adminItemsSummary(request: Request): String {
    if (request.items.isEmpty()) return "No items on this request"
    return request.items.joinToString(", ") { item ->
        val name = item.component?.name?.takeIf { it.isNotBlank() } ?: item.componentId ?: "Unknown item"
        "$name × ${item.quantity}"
    }
}

private fun adminDaysLateLabel(
    returnDueAt: String?,
    today: LocalDate,
): String {
    val dueDate =
        try {
            returnDueAt?.let { LocalDateTime.parse(it.replace(' ', 'T')).date }
        } catch (e: IllegalArgumentException) {
            null
        } ?: return "Overdue"

    // Clamp instead of trusting the raw difference: a stale due date or a client
    // clock behind the server could otherwise render a negative days-late value.
    val daysLate = maxOf(0L, today.toEpochDays() - dueDate.toEpochDays())
    val unit = if (daysLate == 1L) "day" else "days"
    return "$daysLate $unit late · due ${formatDisplayDate(returnDueAt)}"
}

@Composable
private fun StudentDetailEmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "No outstanding fines for this student.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StudentDetailErrorState(
    detail: String?,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Couldn't load this student's fines.",
                style = MaterialTheme.typography.titleMedium,
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry) {
                Text("Try again")
            }
        }
    }
}
