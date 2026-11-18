package com.iiitnr.inventoryapp.ui.screens.admin.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.PaymentRejectionReason

private val reasonLabels =
    mapOf(
        PaymentRejectionReason.UTR_NOT_FOUND to "UTR not found in bank records",
        PaymentRejectionReason.AMOUNT_MISMATCH to "Amount received did not match",
        PaymentRejectionReason.PROOF_UNCLEAR to "Payment proof was unclear",
        PaymentRejectionReason.DUPLICATE_PAYMENT to "Payment was already verified",
        PaymentRejectionReason.OTHER to "Other (see notes)",
    )

@Composable
fun RejectPaymentSheet(
    selectedReason: PaymentRejectionReason?,
    notes: String,
    actionError: String?,
    onReasonSelected: (PaymentRejectionReason?) -> Unit,
    onNotesChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reject payment") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "The fine stays unpaid. The student can start a new payment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReasonDropdown(selectedReason = selectedReason, onSelect = onReasonSelected)
                NotesField(notes = notes, onChange = onNotesChanged)
                if (actionError != null) {
                    Text(
                        text = actionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = selectedReason != null) {
                Text("Reject payment")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasonDropdown(
    selectedReason: PaymentRejectionReason?,
    onSelect: (PaymentRejectionReason?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedReason?.let { reasonLabels[it] } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Reason") },
            placeholder = { Text("Select a reason") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier.fillMaxWidth().menuAnchor(
                    ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                ),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            reasonLabels.forEach { (reason, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(reason)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NotesField(
    notes: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = notes,
        onValueChange = onChange,
        label = { Text("Notes (optional)") },
        minLines = 3,
        maxLines = 5,
        modifier = Modifier.fillMaxWidth(),
    )
}
