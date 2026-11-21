package com.iiitnr.inventoryapp.ui.screens.payment

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

@Composable
fun UpiFallbackScreen(
    uri: String,
    amount: Int,
    vpa: String,
    payeeName: String,
    onEnterUtr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pay ₹$amount to $payeeName",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Scan this code, or use the details below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        Image(
            painter = rememberQrCodePainter(data = uri),
            contentDescription = "UPI payment QR code",
            modifier = Modifier.size(220.dp),
        )
        Spacer(Modifier.height(16.dp))

        SelectionContainer {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "UPI ID: $vpa", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(20.dp))

        Button(onClick = onEnterUtr, modifier = Modifier.fillMaxWidth().handCursor()) {
            Text("I've paid, submit UTR")
        }
    }
}
