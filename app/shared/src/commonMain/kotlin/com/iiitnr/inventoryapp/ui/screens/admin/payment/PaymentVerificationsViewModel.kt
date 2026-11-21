package com.iiitnr.inventoryapp.ui.screens.admin.payment

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.PaymentApi
import com.iiitnr.inventoryapp.data.models.PendingPaymentSummary
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.utils.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PaymentVerificationsViewModel(
    private val tokenManager: TokenManager,
    private val api: PaymentApi,
) : ViewModel() {
    var payments by mutableStateOf<List<PendingPaymentSummary>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        loadPending()
    }

    @Suppress("TooGenericExceptionCaught")
    fun loadPending() {
        viewModelScope.launch {
            if (payments.isEmpty()) {
                isLoading = true
            }
            errorMessage = null
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    payments = api.listPendingForAdmin(token).payments
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (payments.isEmpty()) {
                    errorMessage = e.toAppError().message
                }
            } finally {
                isLoading = false
            }
        }
    }
}
