package com.iiitnr.inventoryapp.ui.screens.payment

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.Payment
import com.iiitnr.inventoryapp.ui.components.common.ProofThumbnail
import com.iiitnr.inventoryapp.ui.components.common.handCursor

@Composable
fun SubmittedScreen(
    payment: Payment,
    onBackToFines: () -> Unit,
    modifier: Modifier = Modifier,
    authToken: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Payment submitted",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "₹${payment.amount} · ref ${payment.id}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Awaiting LA verification.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        payment.proofImageUrl?.let { proofUrl ->
            Spacer(Modifier.height(16.dp))
            ProofThumbnail(url = proofUrl, token = authToken)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBackToFines, modifier = Modifier.handCursor()) {
            Text("Back to Fines")
        }
    }
}
