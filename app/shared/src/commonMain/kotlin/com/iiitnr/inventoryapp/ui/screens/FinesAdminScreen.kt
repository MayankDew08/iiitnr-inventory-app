package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.ResponsiveContent
import com.iiitnr.inventoryapp.ui.components.common.ScreenWidthClass
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import com.iiitnr.inventoryapp.ui.components.requests.RequestDetailDialog
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun FinesAdminScreen(
    onNavigateBack: () -> Unit,
    onStudentClick: (userId: String) -> Unit,
    onNavigateToPaymentVerifications: () -> Unit,
    viewModel: FinesAdminViewModel = koinViewModel(),
) {
    var selectedRequest by remember { mutableStateOf<Request?>(null) }

    ResponsiveContent(modifier = Modifier.fillMaxSize()) { widthClass ->
        val isExpanded = widthClass == ScreenWidthClass.Expanded

        Scaffold(
            topBar = {
                AppTopBar(
                    title = "Outstanding fines",
                    onNavigateBack = onNavigateBack,
                    maxContentWidth = if (isExpanded) 1280.dp else 900.dp,
                )
            },
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                PaymentVerificationsEntry(
                    onClick = onNavigateToPaymentVerifications,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        viewModel.isLoading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }

                        viewModel.errorMessage != null -> {
                            FinesAdminErrorState(
                                detail = viewModel.errorMessage,
                                onRetry = { viewModel.load() },
                            )
                        }

                        viewModel.students.isEmpty() -> {
                            FinesAdminEmptyState()
                        }

                        isExpanded -> {
                            FinesAdminMasterDetail(
                                students = viewModel.students,
                                grandTotal = viewModel.grandTotal,
                                onRequestClick = { selectedRequest = it },
                            )
                        }

                        else -> {
                            FinesAdminContent(
                                students = viewModel.students,
                                grandTotal = viewModel.grandTotal,
                                onStudentClick = onStudentClick,
                            )
                        }
                    }
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

/**
 * Entry row at the top of the LA fines screen that opens the pending-payment
 * verification list. Lives here (not under Profile) because LAs reach for the
 * Fines icon for anything student-fine-related.
 */
@Composable
private fun PaymentVerificationsEntry(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text("Payment Verifications") },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .fillMaxWidth()
                .handCursor()
                .clickable(onClick = onClick),
    )
}

@Composable
private fun FinesAdminContent(
    students: List<StudentWithFines>,
    grandTotal: Int,
    onStudentClick: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(modifier = Modifier.widthIn(max = 900.dp).fillMaxSize()) {
            item { FinesAdminSummary(studentCount = students.size, grandTotal = grandTotal) }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            items(students, key = { it.userId }) { student ->
                StudentFineCard(student = student, onClick = onStudentClick)
            }
        }
    }
}

/**
 * Two-pane layout for wide windows: the outstanding-fines list on the left and
 * the selected student's overdue requests inline on the right.
 */
@Composable
private fun FinesAdminMasterDetail(
    students: List<StudentWithFines>,
    grandTotal: Int,
    onRequestClick: (Request) -> Unit,
) {
    var selectedUserId by remember { mutableStateOf<String?>(null) }

    // Keep a valid selection whenever the student list changes.
    LaunchedEffect(students) {
        if (selectedUserId == null || students.none { it.userId == selectedUserId }) {
            selectedUserId = students.firstOrNull()?.userId
        }
    }

    val selectedStudent = students.firstOrNull { it.userId == selectedUserId }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier.widthIn(max = 1280.dp).fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(modifier = Modifier.width(420.dp).fillMaxHeight()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item { FinesAdminSummary(studentCount = students.size, grandTotal = grandTotal) }

                    item {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    items(students, key = { it.userId }) { student ->
                        StudentFineCard(
                            student = student,
                            selected = student.userId == selectedUserId,
                            onClick = { selectedUserId = it },
                        )
                    }
                }
            }

            if (selectedStudent != null) {
                StudentFinesPane(
                    userId = selectedStudent.userId,
                    studentName = selectedStudent.studentName,
                    requestCount = selectedStudent.requestCount,
                    onRequestClick = onRequestClick,
                    modifier =
                        Modifier
                            .weight(1f)
                            .wrapContentHeight(Alignment.Top)
                            .padding(top = 16.dp, end = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun FinesAdminSummary(
    studentCount: Int,
    grandTotal: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "$studentCount student${if (studentCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "owe fines",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "₹$grandTotal",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StudentFineCard(
    student: StudentWithFines,
    selected: Boolean = false,
    onClick: (String) -> Unit,
) {
    Card(
        onClick = { onClick(student.userId) },
        modifier = Modifier.fillMaxWidth().handCursor().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border =
            BorderStroke(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = student.studentName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${student.requestCount} overdue request${if (student.requestCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "₹${student.total}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun FinesAdminEmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Payments,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No outstanding fines.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Every return is up to date.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FinesAdminErrorState(
    detail: String?,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Couldn't load outstanding fines.",
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
