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

class FinesAdminStudentDetailViewModel(
    private val tokenManager: TokenManager,
) : ViewModel() {
    var studentName: String by mutableStateOf("")
        private set
    var fines: List<Request> by mutableStateOf(emptyList())
        private set
    var isLoading: Boolean by mutableStateOf(false)
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set

    val total: Int get() = fines.sumOf { it.lateFeeAccrued }

    // Broad catch is deliberate: toAppError() classifies every failure
    // (HTTP, network, serialization) centrally, matching RequestsViewModel.
    @Suppress("TooGenericExceptionCaught")
    fun load(userId: String) {
        if (isLoading) return

        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response = ApiClient.requestApiService.getRequests("Bearer $token")
                    val mine =
                        response.requests.filter {
                            (it.user?.id ?: it.userId) == userId &&
                                it.lateFeeAccrued > 0 &&
                                it.returnedAt == null
                        }
                    studentName =
                        mine.firstOrNull()?.user?.name
                            ?: mine.firstOrNull()?.user?.email
                            ?: "Student"
                    fines = mine.sortedByDescending { it.lateFeeAccrued }
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
        if (appError is AppError.Unauthorized) return
        errorMessage = appError.message
    }
}
