package com.iiitnr.inventoryapp.ui.screens.payment

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.NarrowContentWidth
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import org.koin.compose.viewmodel.koinViewModel

/** Max width for the single-column student payment flow. */
private val FormMaxWidth = 560.dp

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

    val authToken by viewModel.token.collectAsState(initial = null)
    Scaffold(
        topBar = {
            AppTopBar(
                title = "Payment",
                onNavigateBack = onNavigateBack,
                maxContentWidth = NarrowContentWidth,
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier.widthIn(max = FormMaxWidth).padding(16.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    PaymentStateContent(
                        viewModel = viewModel,
                        authToken = authToken,
                        onDone = onDone,
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentStateContent(
    viewModel: PaymentViewModel,
    authToken: String?,
    onDone: () -> Unit,
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
                payeeName = state.upi.payeeName,
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
            SubmittedScreen(
                payment = state.payment,
                authToken = authToken,
                onBackToFines = onDone,
            )

        is PaymentUiState.Error ->
            ErrorState(message = state.message, onRetry = viewModel::onRetry)
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
        Button(onClick = onEnterUtr, modifier = Modifier.handCursor()) {
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
        Button(onClick = onRetry, modifier = Modifier.handCursor()) {
            Text("Try again")
        }
    }
}
