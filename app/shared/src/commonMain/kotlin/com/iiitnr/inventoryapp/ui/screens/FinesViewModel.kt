package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.ApiClient
import com.iiitnr.inventoryapp.data.models.AppError
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.utils.toAppError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FinesViewModel(
    private val tokenManager: TokenManager,
) : ViewModel() {
    var fines: List<Request> by mutableStateOf(emptyList())
        private set
    var isLoading: Boolean by mutableStateOf(false)
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set

    val totalOwed: Int get() = computeTotalOwed(fines)

    init {
        load()
    }

    fun load() {
        if (isLoading) return

        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response = ApiClient.requestApiService.getRequests("Bearer $token")
                    fines = response.requests.filter { it.lateFeeAccrued > 0 && it.returnedAt == null }
                } else {
                    errorMessage = "No authentication token"
                }
            } catch (e: Throwable) {
                handleLoadError(e)
            } finally {
                isLoading = false
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
