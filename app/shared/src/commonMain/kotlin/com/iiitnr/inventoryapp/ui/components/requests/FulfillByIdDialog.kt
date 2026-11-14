package com.iiitnr.inventoryapp.ui.components.requests

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun FulfillByIdDialog(
    modifier: Modifier = Modifier,
    requestIdInput: String,
    onRequestIdChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onScanClick: (() -> Unit)? = null,
    dialogTitle: String = "Fulfill Request",
    confirmButtonLabel: String = "Find Request",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            Column(modifier = modifier.padding(vertical = 8.dp)) {
                if (onScanClick != null) {
                    FilledTonalButton(
                        onClick = onScanClick,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text("Scan QR code")
                    }
                } else {
                    Text(
                        text = "Enter the 4-digit OTP or Request ID:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = requestIdInput,
                    onValueChange = { newValue ->
                        onRequestIdChange(newValue)
                        val trimmed = newValue.trim()
                        if (trimmed.length == 4 && trimmed.all { it.isDigit() }) {
                            onConfirm()
                        }
                    },
                    label = { Text("OTP or Request ID") },
                    placeholder = { Text("e.g. 3821 or Request ID") },
                    singleLine = true,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyUp &&
                                    (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)
                                ) {
                                    onConfirm()
                                    true
                                } else {
                                    false
                                }
                            },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = { onConfirm() },
                            onSearch = { onConfirm() },
                            onGo = { onConfirm() },
                        ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) {
                Text(confirmButtonLabel, color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
