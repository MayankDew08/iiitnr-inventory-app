package com.iiitnr.inventoryapp.ui.screens.payment

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.PaymentApi
import com.iiitnr.inventoryapp.data.models.AppHttpException
import com.iiitnr.inventoryapp.data.models.Payment
import com.iiitnr.inventoryapp.data.models.PaymentUpiInfo
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.ui.platform.ProofPicker
import com.iiitnr.inventoryapp.utils.IntentLauncher
import com.iiitnr.inventoryapp.utils.LaunchResult
import com.iiitnr.inventoryapp.utils.UpiUri
import com.iiitnr.inventoryapp.utils.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface PaymentUiState {
    data object Idle : PaymentUiState

    data object CreatingIntent : PaymentUiState

    data class IntentReady(
        val payment: Payment,
        val upi: PaymentUpiInfo,
    ) : PaymentUiState

    data object AwaitingReturn : PaymentUiState

    data class EnteringUtr(
        val payment: Payment,
        val utr: String = "",
        val proof: ByteArray? = null,
        val proofMime: String? = null,
        val submitError: String? = null,
    ) : PaymentUiState

    data object Submitting : PaymentUiState

    data class Submitted(
        val payment: Payment,
    ) : PaymentUiState

    data class QrFallback(
        val payment: Payment,
        val upi: PaymentUpiInfo,
        val uri: String,
    ) : PaymentUiState

    data class Error(
        val message: String,
    ) : PaymentUiState
}

private const val MAX_PROOF_BYTES = 10 * 1024 * 1024

private val ALLOWED_PROOF_MIMES =
    setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif")

class PaymentViewModel(
    private val tokenManager: TokenManager,
    private val api: PaymentApi,
    private val launcher: IntentLauncher,
    private val picker: ProofPicker,
) : ViewModel() {
    var uiState: PaymentUiState by mutableStateOf(PaymentUiState.Idle)
        private set

    /** True only when both a trimmed UTR and an accepted screenshot are present. */
    val canSubmit: Boolean
        get() =
            (uiState as? PaymentUiState.EnteringUtr)?.let { state ->
                state.utr.isNotBlank() && state.proof != null && state.proofMime != null
            } ?: false

    private var currentPayment: Payment? = null

    fun onPayTapped() {
        if (uiState is PaymentUiState.CreatingIntent) return
        uiState = PaymentUiState.CreatingIntent
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token == null) {
                    uiState = PaymentUiState.Error("No authentication token")
                    return@launch
                }
                val response = api.createPaymentIntent(token)
                currentPayment = response.payment
                val uri =
                    UpiUri.build(
                        amount = response.payment.amount,
                        vpa = response.upi.upiVpa,
                        payee = response.upi.payeeName,
                    )
                uiState = PaymentUiState.IntentReady(response.payment, response.upi)
                applyLaunchResult(launcher.launchUpi(uri), uri, response.payment, response.upi)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppHttpException) {
                uiState =
                    PaymentUiState.Error(
                        if (e.errorCode == "no_unpaid_fines") "No fines to pay" else e.toAppError().message,
                    )
            } catch (e: Exception) {
                uiState = PaymentUiState.Error(e.toAppError().message)
            }
        }
    }

    /** Re-enters the flow for a PENDING payment found via GET /payments/mine. */
    fun onResumePendingPayment() {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token == null) {
                    uiState = PaymentUiState.Error("No authentication token")
                    return@launch
                }
                val pending = api.listMyPayments(token).payments.firstOrNull { it.status.name == "PENDING" }
                if (pending == null) {
                    uiState = PaymentUiState.Idle
                } else {
                    currentPayment = pending
                    uiState = PaymentUiState.EnteringUtr(pending)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState = PaymentUiState.Error(e.toAppError().message)
            }
        }
    }

    fun onLauncherResult(result: LaunchResult) {
        val state = uiState as? PaymentUiState.IntentReady ?: return
        val uri =
            UpiUri.build(
                amount = state.payment.amount,
                vpa = state.upi.upiVpa,
                payee = state.upi.payeeName,
            )
        applyLaunchResult(result, uri, state.payment, state.upi)
    }

    private fun applyLaunchResult(
        result: LaunchResult,
        uri: String,
        payment: Payment,
        upi: PaymentUpiInfo,
    ) {
        uiState =
            when (result) {
                LaunchResult.Launched -> PaymentUiState.AwaitingReturn
                LaunchResult.NoAppAvailable -> PaymentUiState.QrFallback(payment, upi, uri)
                is LaunchResult.Error -> PaymentUiState.Error(result.message)
            }
    }

    fun onEnterUtrTapped() {
        val payment =
            when (val state = uiState) {
                is PaymentUiState.AwaitingReturn -> currentPayment
                is PaymentUiState.QrFallback -> state.payment
                else -> null
            } ?: currentPayment ?: return
        currentPayment = payment
        uiState = PaymentUiState.EnteringUtr(payment)
    }

    fun onUtrChanged(utr: String) {
        val state = uiState as? PaymentUiState.EnteringUtr ?: return
        uiState = state.copy(utr = utr, submitError = null)
    }

    fun onPickProof() {
        val state = uiState as? PaymentUiState.EnteringUtr ?: return
        viewModelScope.launch {
            try {
                val picked = picker.pick() ?: return@launch
                if (picked.bytes.size > MAX_PROOF_BYTES) {
                    uiState = state.copy(submitError = "Screenshot must be 10 MB or smaller")
                    return@launch
                }
                val mime = proofMime(picked.filename)
                if (mime == null || mime !in ALLOWED_PROOF_MIMES) {
                    uiState = state.copy(submitError = "Use a JPG, PNG, WebP, GIF or AVIF screenshot")
                    return@launch
                }
                uiState = state.copy(proof = picked.bytes, proofMime = mime, submitError = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState = state.copy(submitError = e.toAppError().message)
            }
        }
    }

    fun onSubmitTapped() {
        val state = uiState as? PaymentUiState.EnteringUtr ?: return
        val proof = state.proof
        val mime = state.proofMime
        if (!canSubmit || proof == null || mime == null) return
        val payment = state.payment
        currentPayment = payment

        uiState = PaymentUiState.Submitting
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token == null) {
                    uiState = PaymentUiState.Error("No authentication token")
                    return@launch
                }
                val response =
                    api.submitPayment(
                        token = token,
                        paymentId = payment.id,
                        utr = state.utr.trim(),
                        proof = proof,
                        proofMime = mime,
                    )
                uiState = PaymentUiState.Submitted(response.payment)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppHttpException) {
                uiState =
                    if (e.status == 409) {
                        PaymentUiState.Submitted(payment)
                    } else {
                        state.copy(submitError = e.toAppError().message)
                    }
            } catch (e: Exception) {
                uiState = state.copy(submitError = e.toAppError().message)
            }
        }
    }

    fun onDismissError() {
        uiState =
            when (val state = uiState) {
                is PaymentUiState.Error -> PaymentUiState.Idle
                is PaymentUiState.EnteringUtr -> state.copy(submitError = null)
                else -> uiState
            }
    }

    fun onRetry() {
        if (uiState is PaymentUiState.Error) onPayTapped()
    }
}

internal fun proofMime(filename: String): String? =
    when (filename.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        else -> null
    }
