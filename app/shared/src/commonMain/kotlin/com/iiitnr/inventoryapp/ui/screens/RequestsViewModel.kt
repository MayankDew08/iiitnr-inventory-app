package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.AuthApiService
import com.iiitnr.inventoryapp.data.api.RequestApiService
import com.iiitnr.inventoryapp.data.models.AppError
import com.iiitnr.inventoryapp.data.models.AppHttpException
import com.iiitnr.inventoryapp.data.models.IssueItemPayload
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestStatus
import com.iiitnr.inventoryapp.data.models.ReturnItemPayload
import com.iiitnr.inventoryapp.data.models.UpdateRequestStatusPayload
import com.iiitnr.inventoryapp.data.models.User
import com.iiitnr.inventoryapp.data.models.UserRole
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.ui.components.requests.REQUEST_QR_PREFIX
import com.iiitnr.inventoryapp.ui.components.requests.requestStatusActionSnackbarMessage
import com.iiitnr.inventoryapp.utils.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class RequestsViewModel(
    private val tokenManager: TokenManager,
    private val authApiService: AuthApiService,
    private val requestApiService: RequestApiService,
) : ViewModel() {
    var requests by mutableStateOf<List<Request>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var currentUser by mutableStateOf<User?>(null)
        private set
    var searchQuery by mutableStateOf("")
    var statusFilter by mutableStateOf<RequestStatus?>(null)

    private val _snackbarMessages = MutableSharedFlow<String>()
    val snackbarMessages = _snackbarMessages.asSharedFlow()

    val canExportCsv get() =
        currentUser?.role == UserRole.ADMIN ||
            currentUser?.role == UserRole.LA ||
            currentUser?.role == UserRole.FACULTY

    fun exportRequestsCsv(onResult: (String?) -> Unit) {
        val user = currentUser
        if (user == null || user.role !in setOf(UserRole.ADMIN, UserRole.LA, UserRole.FACULTY)) {
            onResult(null)
            return
        }
        if (requests.isEmpty()) {
            onResult(null)
            return
        }

        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    onResult(requestApiService.exportRequestsCsv(token))
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onResult(null)
            }
        }
    }

    val filteredRequests: List<Request>
        get() {
            val query = searchQuery.trim()
            return requests.filter { request ->
                val matchesStatus = statusFilter?.let { it == request.status } ?: true
                val textMatches =
                    listOfNotNull(
                        request.projectTitle,
                        request.user?.name,
                        request.user?.email,
                        request.targetFaculty?.name,
                        request.targetFaculty?.email,
                    ).any { it.contains(query, ignoreCase = true) }
                val itemMatches = request.items.any { it.component?.name?.contains(query, ignoreCase = true) == true }

                matchesStatus && (query.isBlank() || textMatches || itemMatches)
            }
        }

    init {
        loadUserData()
        loadRequests(pollingMode = false)
        startSseOrPolling()
    }

    private fun loadUserData() {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response = authApiService.getMe(token)
                    currentUser = response.user
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun loadRequests(pollingMode: Boolean = false) {
        viewModelScope.launch { refreshRequests(pollingMode) }
    }

    /** Suspend rather than fire-and-forget, so the polling loop can await a completed load. */
    private suspend fun refreshRequests(pollingMode: Boolean) {
        if (pollingMode && isRefreshing) return

        beginRequestLoad(pollingMode)

        try {
            val token = tokenManager.token.first()
            if (token != null) {
                val response = requestApiService.getRequests(token)
                requests = response.requests
                errorMessage = null
            } else {
                if (!pollingMode && requests.isEmpty()) {
                    errorMessage = "No authentication token"
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            handleRequestLoadError(e, pollingMode)
        } finally {
            isLoading = false
            isRefreshing = false
        }
    }

    private fun beginRequestLoad(pollingMode: Boolean) {
        if (pollingMode) {
            isRefreshing = true
        } else {
            if (requests.isEmpty()) {
                isLoading = true
            } else {
                isRefreshing = true
            }
            errorMessage = null
        }
    }

    private suspend fun handleRequestLoadError(
        e: Throwable,
        pollingMode: Boolean,
    ) {
        val appError = e.toAppError()
        if (appError is AppError.Unauthorized) return

        if (!pollingMode) {
            if (requests.isEmpty()) {
                errorMessage = appError.message
            } else {
                _snackbarMessages.emit("Network error: Using latest data")
            }
        }
    }

    private fun startSseOrPolling() {
        viewModelScope.launch {
            tokenManager.token.collectLatest { token ->
                if (token == null) return@collectLatest
                streamWithPollingFallback(token)
            }
        }
    }

    private suspend fun streamWithPollingFallback(token: String) {
        var pollAttempts = 0
        while (true) {
            try {
                requestApiService.streamRequestEvents(token).collect {
                    pollAttempts = 0
                    refreshRequests(pollingMode = true)
                }
                delay(SSE_RECONNECT_DELAY_MS.milliseconds)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                while (pollAttempts < MAX_POLL_ATTEMPTS) {
                    val backoff = POLL_BASE_DELAY_MS * (1L shl pollAttempts)
                    delay(minOf(backoff, MAX_POLL_DELAY_MS).milliseconds)
                    refreshRequests(pollingMode = true)
                    pollAttempts++
                }
                pollAttempts = 0
            }
        }
    }

    fun deleteRequest(requestId: String) {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    requestApiService.deleteRequest(token, requestId)
                    loadRequests()
                } else {
                    errorMessage = "No authentication token"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                errorMessage = e.toAppError().message
            }
        }
    }

    fun updateRequestStatus(
        requestId: String,
        status: RequestStatus,
        lastRenewReason: String? = null,
        issueItems: List<IssueItemPayload>? = null,
        returnItems: List<ReturnItemPayload>? = null,
    ) {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    requestApiService.updateRequestStatus(
                        token,
                        requestId,
                        UpdateRequestStatusPayload(
                            status = status,
                            lastRenewReason = lastRenewReason,
                            issueItems = issueItems,
                            returnItems = returnItems,
                        ),
                    )
                    loadRequests()
                    requestStatusActionSnackbarMessage(status)?.let { message ->
                        _snackbarMessages.emit(message)
                    }
                } else {
                    errorMessage = "No authentication token"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                errorMessage = e.toAppError().message
            }
        }
    }

    fun generateRequestOtp(
        requestId: String,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val res = requestApiService.generateRequestOtp(token, requestId)
                    onResult(res.otp)
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onResult(null)
            }
        }
    }

    fun lookupRequest(
        query: String,
        onResult: (Request?) -> Unit,
    ) {
        val trimmed = query.trim().removePrefix(REQUEST_QR_PREFIX).trim()
        if (trimmed.isBlank()) {
            onResult(null)
            return
        }

        val localMatch = requests.firstOrNull { it.id == trimmed || it.otp == trimmed }
        if (localMatch != null) {
            onResult(localMatch)
            return
        }

        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response = requestApiService.getRequestByIdOrOtp(token, trimmed)
                    onResult(response.request)
                } else {
                    _snackbarMessages.emit("Authentication error")
                    onResult(null)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                val message =
                    (e as? AppHttpException)?.errorMessage
                        ?: "Request not found for: $trimmed"
                _snackbarMessages.emit(message)
                onResult(null)
            }
        }
    }

    fun handleQrResult(rawValue: String): Request? {
        val requestId = rawValue.trim().removePrefix(REQUEST_QR_PREFIX).trim()
        if (requestId.isBlank()) return null

        val request = requests.firstOrNull { it.id == requestId || it.otp == requestId }
        if (request == null) {
            viewModelScope.launch {
                _snackbarMessages.emit("Request not found. Refresh and try again.")
            }
            return null
        }
        return request
    }

    private companion object {
        const val MAX_POLL_ATTEMPTS = 6
        const val POLL_BASE_DELAY_MS = 5_000L
        const val MAX_POLL_DELAY_MS = 60_000L
        const val SSE_RECONNECT_DELAY_MS = 3_000L
    }
}
