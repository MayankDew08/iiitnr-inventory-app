package com.iiitnr.inventoryapp.ui.components.requests

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestStatus
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

const val REQUEST_QR_PREFIX = "inventoryapp://fulfill/"

@Composable
fun RequestQrDialog(
    modifier: Modifier = Modifier,
    request: Request,
    onDismiss: () -> Unit,
    onFetchOtp: (suspend () -> String?)? = null,
) {
    val qrPayload = REQUEST_QR_PREFIX + request.id
    val qrPainter = rememberQrCodePainter(data = qrPayload)
    var displayOtp by remember { mutableStateOf(request.otp) }

    LaunchedEffect(request.id) {
        if (onFetchOtp != null) {
            val fetched = onFetchOtp()
            if (!fetched.isNullOrBlank()) {
                displayOtp = fetched
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            when (request.status) {
                                RequestStatus.ISSUED, RequestStatus.RENEWED -> "Return Components"
                                else -> "Issue Request"
                            },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = request.projectTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        color = androidx.compose.ui.graphics.Color.White,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Image(
                            painter = qrPainter,
                            contentDescription =
                                when (request.status) {
                                    RequestStatus.ISSUED ->
                                        "QR for LA to scan and record return to inventory"

                                    else -> "Request QR code for LA to scan"
                                },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentScale = ContentScale.FillWidth,
                        )
                    }

                    if (!displayOtp.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = "OTP for LA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    text = displayOtp!!,
                                    style =
                                        MaterialTheme.typography.headlineMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 4.sp,
                                        ),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Request ID:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        text = request.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
