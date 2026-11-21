package com.iiitnr.inventoryapp.ui.screens.admin.payment

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.PaymentDetail
import com.iiitnr.inventoryapp.data.models.PaymentDetailAllocation
import com.iiitnr.inventoryapp.data.models.PaymentRejectionReason
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.InventoryTable
import com.iiitnr.inventoryapp.ui.components.common.ProofThumbnail
import com.iiitnr.inventoryapp.ui.components.common.TableColumn
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
    Scaffold(
        topBar = {
            AppTopBar(
                title = "Verify Payment",
                onNavigateBack = onNavigateBack,
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (val state = viewModel.uiState) {
                PaymentVerificationDetailUiState.Loading,
                PaymentVerificationDetailUiState.Submitting,
                -> ProgressContent(isSubmitting = state == PaymentVerificationDetailUiState.Submitting)

                is PaymentVerificationDetailUiState.Loaded ->
                    LoadedContent(
                        detail = state.detail,
                        authToken = authToken,
                        actionError = viewModel.actionError,
                        selectedReason = viewModel.selectedReason,
                        notes = viewModel.notes,
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

@Composable
private fun ProgressContent(isSubmitting: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isSubmitting) "Resolving payment…" else "Loading payment…",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ResolvedContent(onResolved: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
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
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onResolved, modifier = Modifier.fillMaxWidth()) {
            Text("Back to verifications")
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
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            TextButton(onClick = onRetry) { Text("Retry") }
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
    onReasonSelected: (PaymentRejectionReason?) -> Unit,
    onNotesChanged: (String) -> Unit,
    onVerify: () -> Unit,
    onReject: () -> Unit,
) {
    var rejectSheetVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PaymentSummaryCard(detail = detail)
        Text("Payment proof", style = MaterialTheme.typography.titleSmall)
        ProofImage(detail.payment.proofImageUrl, authToken)
        AllocatedFines(allocations = detail.allocations)
        if (actionError != null) {
            Text(
                text = actionError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
            Text("Verify payment")
        }
        OutlinedButton(
            onClick = { rejectSheetVisible = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reject payment")
        }
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
}

@Composable
private fun PaymentSummaryCard(detail: PaymentDetail) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
private fun AllocatedFines(allocations: List<PaymentDetailAllocation>) {
    Text("Allocated fines", style = MaterialTheme.typography.titleSmall)
    if (allocations.isEmpty()) {
        Text(
            "No fines are allocated to this payment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
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

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
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
private fun ProofImage(
    url: String?,
    authToken: String?,
) {
    if (url.isNullOrBlank()) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No proof uploaded",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    ProofThumbnail(url = url, token = authToken, thumbnailHeight = 220.dp)
}
