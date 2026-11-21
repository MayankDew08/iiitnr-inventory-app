package com.iiitnr.inventoryapp.ui.screens.admin.payment

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.PaymentApi
import com.iiitnr.inventoryapp.data.models.AppHttpException
import com.iiitnr.inventoryapp.data.models.PaymentDetail
import com.iiitnr.inventoryapp.data.models.PaymentRejectionReason
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.utils.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface PaymentVerificationDetailUiState {
    data object Loading : PaymentVerificationDetailUiState

    data class Loaded(
        val detail: PaymentDetail,
    ) : PaymentVerificationDetailUiState

    data object Submitting : PaymentVerificationDetailUiState

    data object Verified : PaymentVerificationDetailUiState

    data class Error(
        val message: String,
    ) : PaymentVerificationDetailUiState
}

private const val MAX_REJECT_NOTES = 500

class PaymentVerificationDetailViewModel(
    private val paymentId: String,
    private val tokenManager: TokenManager,
    private val api: PaymentApi,
) : ViewModel() {
    var uiState: PaymentVerificationDetailUiState by mutableStateOf(
        PaymentVerificationDetailUiState.Loading,
    )
        private set
    var selectedReason by mutableStateOf<PaymentRejectionReason?>(null)
        private set
    var notes by mutableStateOf("")
        private set
    var actionError by mutableStateOf<String?>(null)
        private set

    val canReject: Boolean
        get() = selectedReason != null && notes.length <= MAX_REJECT_NOTES

    /** Session token stream; the screen uses it for the authenticated proof image. */
    val token: Flow<String?> get() = tokenManager.token

    private var lastDetail: PaymentDetail? = null

    init {
        load()
    }

    @Suppress("TooGenericExceptionCaught")
    fun load() {
        uiState = PaymentVerificationDetailUiState.Loading
        actionError = null
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token == null) {
                    uiState = PaymentVerificationDetailUiState.Error("No authentication token")
                    return@launch
                }
                val detail = api.getPaymentForAdmin(token, paymentId)
                lastDetail = detail
                uiState = PaymentVerificationDetailUiState.Loaded(detail)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                uiState = PaymentVerificationDetailUiState.Error(e.toAppError().message)
            }
        }
    }

    fun onReasonSelected(reason: PaymentRejectionReason?) {
        selectedReason = reason
        actionError = null
    }

    fun onNotesChanged(value: String) {
        notes = value.take(MAX_REJECT_NOTES)
        actionError = null
    }

    fun onVerifyTapped() {
        if (uiState !is PaymentVerificationDetailUiState.Loaded) return
        submit { token, id -> api.verifyPayment(token, id) }
    }

    fun onRejectTapped() {
        if (uiState !is PaymentVerificationDetailUiState.Loaded) return
        val reason = selectedReason
        when {
            reason == null -> actionError = "Select a reason before rejecting."
            !canReject -> actionError = "Notes must be $MAX_REJECT_NOTES characters or fewer."
            else -> submit { token, id -> api.rejectPayment(token, id, reason, notes) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun submit(action: suspend (String, String) -> Any) {
        val paymentIdToUse = lastDetail?.payment?.id ?: paymentId
        uiState = PaymentVerificationDetailUiState.Submitting
        actionError = null
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token == null) {
                    uiState = PaymentVerificationDetailUiState.Error("No authentication token")
                    return@launch
                }
                action(token, paymentIdToUse)
                uiState = PaymentVerificationDetailUiState.Verified
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                uiState =
                    PaymentVerificationDetailUiState.Error(
                        if (e is AppHttpException && e.status == 409) {
                            "This payment was already resolved by another LA."
                        } else {
                            e.toAppError().message
                        },
                    )
            }
        }
    }

    fun onDismissError() {
        uiState =
            lastDetail?.let(PaymentVerificationDetailUiState::Loaded)
                ?: PaymentVerificationDetailUiState.Loading
    }
}
