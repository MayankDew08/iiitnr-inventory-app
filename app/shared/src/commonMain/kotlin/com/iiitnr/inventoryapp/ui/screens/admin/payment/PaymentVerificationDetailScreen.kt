package com.iiitnr.inventoryapp.ui.screens.admin.payment

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.PaymentDetail
import com.iiitnr.inventoryapp.data.models.PaymentDetailAllocation
import com.iiitnr.inventoryapp.data.models.PaymentRejectionReason
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.InventoryTable
import com.iiitnr.inventoryapp.ui.components.common.NarrowContentWidth
import com.iiitnr.inventoryapp.ui.components.common.ProofThumbnail
import com.iiitnr.inventoryapp.ui.components.common.ResponsiveContent
import com.iiitnr.inventoryapp.ui.components.common.ScreenWidthClass
import com.iiitnr.inventoryapp.ui.components.common.TableColumn
import com.iiitnr.inventoryapp.ui.components.common.WideContentWidth
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import com.iiitnr.inventoryapp.utils.formatDisplayDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PaymentVerificationDetailScreen(
    paymentId: String,
    onNavigateBack: () -> Unit,
    onResolved: () -> Unit,
    viewModel: PaymentVerificationDetailViewModel =
        koinViewModel { parametersOf(paymentId) },
) {
    val authToken by viewModel.token.collectAsState(initial = null)

    ResponsiveContent(modifier = Modifier.fillMaxSize()) { widthClass ->
        val isExpanded = widthClass == ScreenWidthClass.Expanded
        // Resolved and error/loading states stay in the narrow column cap; the
        // Loaded state gets the wide cap on Expanded so the proof and the
        // summary column sit side by side.
        val isLoaded = viewModel.uiState is PaymentVerificationDetailUiState.Loaded
        val topBarWidth = if (isExpanded && isLoaded) WideContentWidth else NarrowContentWidth

        Scaffold(
            topBar = {
                AppTopBar(
                    title = "Verify Payment",
                    onNavigateBack = onNavigateBack,
                    maxContentWidth = topBarWidth,
                )
            },
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    when (val state = viewModel.uiState) {
                        PaymentVerificationDetailUiState.Loading,
                        PaymentVerificationDetailUiState.Submitting,
                        ->
                            ProgressContent(
                                isSubmitting = state == PaymentVerificationDetailUiState.Submitting,
                            )

                        is PaymentVerificationDetailUiState.Loaded ->
                            LoadedContent(
                                detail = state.detail,
                                authToken = authToken,
                                actionError = viewModel.actionError,
                                selectedReason = viewModel.selectedReason,
                                notes = viewModel.notes,
                                isExpanded = isExpanded,
                                onReasonSelected = viewModel::onReasonSelected,
                                onNotesChanged = viewModel::onNotesChanged,
                                onVerify = viewModel::onVerifyTapped,
                                onReject = viewModel::onRejectTapped,
                            )

                        PaymentVerificationDetailUiState.Verified -> ResolvedContent(onResolved = onResolved)

                        is PaymentVerificationDetailUiState.Error ->
                            ErrorContent(
                                message = state.message,
                                onDismiss = viewModel::onDismissError,
                                onRetry = viewModel::load,
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressContent(isSubmitting: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isSubmitting) "Resolving payment…" else "Loading payment…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResolvedContent(onResolved: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("Payment resolved", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "The student has been notified.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onResolved,
                modifier = Modifier.handCursor(),
            ) {
                Text("Back to verifications")
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .widthIn(max = NarrowContentWidth)
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) { Text("Dismiss") }
            TextButton(onClick = onRetry, modifier = Modifier.handCursor()) { Text("Retry") }
        }
    }
}

@Composable
private fun LoadedContent(
    detail: PaymentDetail,
    authToken: String?,
    actionError: String?,
    selectedReason: PaymentRejectionReason?,
    notes: String,
    isExpanded: Boolean,
    onReasonSelected: (PaymentRejectionReason?) -> Unit,
    onNotesChanged: (String) -> Unit,
    onVerify: () -> Unit,
    onReject: () -> Unit,
) {
    var rejectSheetVisible by remember { mutableStateOf(false) }
    var verifyConfirmVisible by remember { mutableStateOf(false) }

    if (isExpanded) {
        LoadedExpanded(
            detail = detail,
            authToken = authToken,
            actionError = actionError,
            onVerifyTap = { verifyConfirmVisible = true },
            onRejectTap = { rejectSheetVisible = true },
        )
    } else {
        LoadedStacked(
            detail = detail,
            authToken = authToken,
            actionError = actionError,
            onVerifyTap = { verifyConfirmVisible = true },
            onRejectTap = { rejectSheetVisible = true },
        )
    }

    if (rejectSheetVisible) {
        RejectPaymentSheet(
            selectedReason = selectedReason,
            notes = notes,
            actionError = actionError,
            onReasonSelected = onReasonSelected,
            onNotesChanged = onNotesChanged,
            onSubmit = {
                onReject()
                rejectSheetVisible = false
            },
            onDismiss = { rejectSheetVisible = false },
        )
    }

    if (verifyConfirmVisible) {
        VerifyConfirmDialog(
            detail = detail,
            onConfirm = {
                verifyConfirmVisible = false
                onVerify()
            },
            onDismiss = { verifyConfirmVisible = false },
        )
    }
}

@Composable
private fun VerifyConfirmDialog(
    detail: PaymentDetail,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val studentLabel = detail.student.name?.takeIf { it.isNotBlank() } ?: detail.student.email
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verify this payment?") },
        text = {
            Text(
                "This will settle ${detail.allocations.size} fine" +
                    (if (detail.allocations.size == 1) "" else "s") +
                    " for $studentLabel and mark ₹${detail.payment.amount} as received. " +
                    "This cannot be undone.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.handCursor()) {
                Text("Verify payment")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun LoadedExpanded(
    detail: PaymentDetail,
    authToken: String?,
    actionError: String?,
    onVerifyTap: () -> Unit,
    onRejectTap: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .widthIn(max = WideContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Payment proof")
            ProofThumbnail(url = detail.payment.proofImageUrl, token = authToken)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PaymentSummaryCard(detail = detail)
            AllocatedFinesCard(allocations = detail.allocations)
            ActionArea(
                actionError = actionError,
                onVerifyTap = onVerifyTap,
                onRejectTap = onRejectTap,
            )
        }
    }
}

@Composable
private fun LoadedStacked(
    detail: PaymentDetail,
    authToken: String?,
    actionError: String?,
    onVerifyTap: () -> Unit,
    onRejectTap: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .widthIn(max = NarrowContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PaymentSummaryCard(detail = detail)
        SectionLabel("Payment proof")
        ProofThumbnail(url = detail.payment.proofImageUrl, token = authToken)
        AllocatedFinesCard(allocations = detail.allocations)
        ActionArea(
            actionError = actionError,
            onVerifyTap = onVerifyTap,
            onRejectTap = onRejectTap,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ActionArea(
    actionError: String?,
    onVerifyTap: () -> Unit,
    onRejectTap: () -> Unit,
) {
    if (actionError != null) {
        Text(
            text = actionError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onRejectTap,
            modifier = Modifier.weight(1f).handCursor(),
        ) {
            Text("Reject payment")
        }
        Button(
            onClick = onVerifyTap,
            modifier = Modifier.weight(1f).handCursor(),
        ) {
            Text("Verify payment")
        }
    }
}

@Composable
private fun PaymentSummaryCard(detail: PaymentDetail) {
    FlatCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = detail.student.name ?: "Student",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail.student.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            DetailRow("Amount", "₹${detail.payment.amount}")
            DetailRow("UTR", detail.payment.utr ?: "—")
            DetailRow("Submitted", formatDisplayDate(detail.payment.submittedAt))
        }
    }
}

@Composable
private fun AllocatedFinesCard(allocations: List<PaymentDetailAllocation>) {
    FlatCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Allocated fines",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            if (allocations.isEmpty()) {
                Text(
                    "No fines are allocated to this payment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            InventoryTable(
                items = allocations,
                columns =
                    listOf(
                        TableColumn(
                            header = "Request",
                            weight = 0.4f,
                            content = { allocation ->
                                Text(
                                    text = allocation.projectTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        ),
                        TableColumn(
                            header = "Fine",
                            weight = 0.2f,
                            content = { allocation ->
                                Text("₹${allocation.amount}", style = MaterialTheme.typography.labelSmall)
                            },
                        ),
                        TableColumn(
                            header = "Components",
                            weight = 0.4f,
                            content = { allocation ->
                                Text(
                                    text =
                                        allocation.items.joinToString { item ->
                                            "${item.componentName} ×${item.quantity}"
                                        },
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FlatCard(
    modifier: Modifier = Modifier,
    contentPaddingBottom: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        content()
        if (contentPaddingBottom > 0.dp) {
            Spacer(Modifier.height(contentPaddingBottom))
        }
    }
}
