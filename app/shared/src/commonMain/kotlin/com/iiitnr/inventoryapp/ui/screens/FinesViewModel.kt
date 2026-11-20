package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.PaymentApi
import com.iiitnr.inventoryapp.data.api.RequestApiService
import com.iiitnr.inventoryapp.data.models.AppError
import com.iiitnr.inventoryapp.data.models.Payment
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.utils.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FinesViewModel(
    private val tokenManager: TokenManager,
    private val requestApiService: RequestApiService,
    private val paymentApi: PaymentApi,
    initialFines: List<Request> = emptyList(),
    initialPayments: List<Payment> = emptyList(),
    loadOnStart: Boolean = true,
) : ViewModel() {
    var fines: List<Request> by mutableStateOf(initialFines)
        private set

    /**
     * Still-growing fines on expired-but-unreturned requests. View-only — the
     * student cannot pay until return finalizes the amount.
     */
    var accruingFines: List<Request> by mutableStateOf(emptyList())
        private set
    var payments: List<Payment> by mutableStateOf(initialPayments)
        private set
    var isLoading: Boolean by mutableStateOf(false)
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set

    val totalOwed: Int get() = computeTotalOwed(fines)

    init {
        if (loadOnStart) load()
    }

    fun load() {
        loadFines()
        loadPayments()
    }

    private fun loadFines() {
        if (isLoading) return

        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response = requestApiService.getRequests(token)
                    val all = response.requests
                    fines = all.filter(::isPayableFine)
                    accruingFines = all.filter(::isAccruingFine)
                } else {
                    errorMessage = "No authentication token"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                handleLoadError(e)
            } finally {
                isLoading = false
            }
        }
    }

    private fun loadPayments() {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    payments = paymentApi.listMyPayments(token).payments
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                handleLoadError(e)
            }
        }
    }

    private suspend fun handleLoadError(e: Throwable) {
        val appError = e.toAppError()
        // Unauthorized is handled globally (AuthEventManager redirects to login);
        // surfacing a fines-specific error here would just flash before that redirect.
        if (appError is AppError.Unauthorized) return
        errorMessage = appError.message
    }
}

internal fun computeTotalOwed(fines: List<Request>): Int = fines.sumOf { it.lateFeeAccrued }

/** A payable bill: returned, has a fine, and not yet settled. */
internal fun isPayableFine(request: Request): Boolean =
    request.returnedAt != null && request.lateFeeAccrued > 0 && request.fineSettledAt == null

/** A fine that is still growing: request is overdue, not yet returned. View-only. */
internal fun isAccruingFine(request: Request): Boolean =
    request.returnedAt == null && request.lateFeeAccrued > 0 && request.fineSettledAt == null

internal fun pendingPayment(payments: List<Payment>): Payment? = payments.firstOrNull { it.status.name == "PENDING" }

internal fun submittedPayments(payments: List<Payment>): List<Payment> =
    payments.filter { it.status.name == "SUBMITTED" }

internal fun rejectedPayments(payments: List<Payment>): List<Payment> = payments.filter { it.status.name == "REJECTED" }

/**
 * The Pay button stays available with unpaid fines until a payment has been
 * submitted for verification. A PENDING intent does not block it: the student
 * may have abandoned or failed the UPI transfer, and re-tapping Pay reuses and
 * refreshes that same PENDING row server-side. Only a SUBMITTED payment blocks,
 * so a second transfer cannot be made for the same fines.
 */
internal fun canStartPayment(
    fines: List<Request>,
    payments: List<Payment>,
): Boolean = fines.isNotEmpty() && submittedPayments(payments).isEmpty()

/** Shown under the disabled Pay button, or null when Pay is tappable. */
internal fun payBlockedHint(payments: List<Payment>): String? =
    if (submittedPayments(payments).isNotEmpty()) {
        "Your payment is awaiting verification"
    } else {
        null
    }
