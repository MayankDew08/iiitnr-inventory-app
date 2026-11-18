package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.iiitnr.inventoryapp.ui.components.common.handCursor
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
                maxContentWidth = 900.dp,
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(modifier = Modifier.widthIn(max = 900.dp).fillMaxSize()) {
            item {
                Text(
                    text = "Overdue requests",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }

            items(fines, key = { it.id }) { request ->
                AdminFineCard(
                    request = request,
                    today = today,
                    onClick = onRequestClick,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Inline variant of [StudentFinesContent] used by the two-pane (Expanded)
 * outstanding-fines layout on wide windows.
 */
@Composable
internal fun StudentFinesPane(
    userId: String,
    studentName: String,
    requestCount: Int,
    onRequestClick: (Request) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FinesAdminStudentDetailViewModel = koinViewModel(),
) {
    var requestedUserId by remember { mutableStateOf<String?>(null) }

    // Loads the selected student, and retries once an in-flight load finishes so
    // switching students mid-fetch never leaves the pane on the previous one.
    LaunchedEffect(userId, viewModel.isLoading) {
        if (!viewModel.isLoading && requestedUserId != userId) {
            requestedUserId = userId
            viewModel.load(userId)
        }
    }

    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
        ) {
            Text(
                text = studentName.ifBlank { "Student" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "$requestCount overdue request${if (requestCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            StudentFinesPaneBody(
                userId = userId,
                viewModel = viewModel,
                onRequestClick = onRequestClick,
            )
        }
    }
}

@Composable
private fun StudentFinesPaneBody(
    userId: String,
    viewModel: FinesAdminStudentDetailViewModel,
    onRequestClick: (Request) -> Unit,
) {
    val today = currentToday

    when {
        viewModel.isLoading -> {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        viewModel.errorMessage != null -> {
            FinesPaneErrorState(
                message = viewModel.errorMessage.orEmpty(),
                onRetry = { viewModel.load(userId) },
            )
        }

        viewModel.fines.isEmpty() -> {
            Text(
                text = "No outstanding fines for this student.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        else -> {
            viewModel.fines.forEach { request ->
                AdminFineCard(
                    request = request,
                    today = today,
                    onClick = onRequestClick,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "₹${viewModel.total}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FinesPaneErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = "Couldn't load this student's fines.",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.handCursor(),
        ) {
            Text("Try again")
        }
    }
}

@Composable
private fun AdminFineCard(
    request: Request,
    today: LocalDate,
    onClick: (Request) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { onClick(request) },
        modifier = modifier.fillMaxWidth().handCursor(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
        } catch (_: IllegalArgumentException) {
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
