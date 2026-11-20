package com.iiitnr.inventoryapp.ui.screens.payment

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PaymentScreen(
    start: String,
    onNavigateBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: PaymentViewModel = koinViewModel(),
) {
    LaunchedEffect(start) {
        if (start == "resume") {
            viewModel.onResumePendingPayment()
        } else {
            viewModel.onPayTapped()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Payment",
                onNavigateBack = onNavigateBack,
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (val state = viewModel.uiState) {
                PaymentUiState.Idle,
                PaymentUiState.CreatingIntent,
                PaymentUiState.Submitting,
                -> LoadingState(state)

                PaymentUiState.AwaitingReturn ->
                    AwaitingReturnContent(onEnterUtr = viewModel::onEnterUtrTapped)

                is PaymentUiState.IntentReady -> Unit

                is PaymentUiState.QrFallback ->
                    UpiFallbackScreen(
                        uri = state.uri,
                        amount = state.payment.amount,
                        vpa = state.upi.upiVpa,
                        onEnterUtr = viewModel::onEnterUtrTapped,
                    )

                is PaymentUiState.EnteringUtr ->
                    SubmitUtrScreen(
                        state = state,
                        canSubmit = viewModel.canSubmit,
                        onUtrChanged = viewModel::onUtrChanged,
                        onPickProof = viewModel::onPickProof,
                        onSubmit = viewModel::onSubmitTapped,
                    )

                is PaymentUiState.Submitted ->
                    SubmittedScreen(payment = state.payment, onBackToFines = onDone)

                is PaymentUiState.Error ->
                    ErrorState(message = state.message, onRetry = viewModel::onRetry)
            }
        }
    }
}

@Composable
private fun LoadingState(state: PaymentUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text =
                when (state) {
                    PaymentUiState.CreatingIntent -> "Preparing your payment…"
                    PaymentUiState.Submitting -> "Submitting…"
                    else -> ""
                },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AwaitingReturnContent(onEnterUtr: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Return here after paying in your UPI app.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onEnterUtr) {
            Text("I've paid, enter UTR")
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) {
            Text("Try again")
        }
    }
}
