package com.iiitnr.inventoryapp.ui.screens.payment

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import com.iiitnr.inventoryapp.data.models.Payment

@Composable
fun SubmitUtrScreen(
    state: PaymentUiState.EnteringUtr,
    canSubmit: Boolean,
    onUtrChanged: (String) -> Unit,
    onPickProof: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = "Submit payment details",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Payment of ₹${state.payment.amount} · ref ${state.payment.id}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        androidx.compose.material3.OutlinedTextField(
            value = state.utr,
            onValueChange = onUtrChanged,
            label = { Text("UPI transaction reference (UTR)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = onPickProof) {
            Text(if (state.proof == null) "Attach screenshot" else "Change screenshot")
        }
        state.proof?.let { bytes ->
            Spacer(Modifier.height(8.dp))
            Image(
                painter = rememberAsyncImagePainter(bytes),
                contentDescription = "Payment proof preview",
                modifier = Modifier.size(120.dp).align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.height(12.dp))

        state.submitError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = onSubmit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Submit for verification")
        }
        Text(
            text = "Both the UTR and a screenshot are required.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

internal fun paymentSummary(payment: Payment): String = "₹${payment.amount} · ${payment.id}"
