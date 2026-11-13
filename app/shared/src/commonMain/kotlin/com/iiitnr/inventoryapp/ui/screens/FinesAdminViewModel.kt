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

data class StudentWithFines(
    val userId: String,
    val studentName: String,
    val requestCount: Int,
    val total: Int,
)

class FinesAdminViewModel(
    private val tokenManager: TokenManager,
) : ViewModel() {
    var students: List<StudentWithFines> by mutableStateOf(emptyList())
        private set
    var isLoading: Boolean by mutableStateOf(false)
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set

    val grandTotal: Int get() = students.sumOf { it.total }

    init {
        load()
    }

    // Broad catch is deliberate: toAppError() classifies every failure
    // (HTTP, network, serialization) centrally, matching RequestsViewModel.
    @Suppress("TooGenericExceptionCaught")
    fun load() {
        if (isLoading) return

        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response = ApiClient.requestApiService.getRequests("Bearer $token")
                    students = computeStudentsWithFines(response.requests)
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

internal fun computeStudentsWithFines(requests: List<Request>): List<StudentWithFines> =
    requests
        .filter { it.lateFeeAccrued > 0 && it.returnedAt == null }
        .groupBy { it.user?.id ?: it.userId }
        .map { (userId, reqs) ->
            StudentWithFines(
                userId = userId,
                studentName =
                    reqs.firstOrNull()?.user?.name
                        ?: reqs.firstOrNull()?.user?.email
                        ?: "Unknown",
                requestCount = reqs.size,
                total = reqs.sumOf { it.lateFeeAccrued },
            )
        }.sortedByDescending { it.total }
