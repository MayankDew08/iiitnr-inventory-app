package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.AuditLogApiService
import com.iiitnr.inventoryapp.data.models.AuditLogEntry
import com.iiitnr.inventoryapp.data.storage.TokenManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AuditLogViewModel(
    private val tokenManager: TokenManager,
    private val auditLogApiService: AuditLogApiService,
) : ViewModel() {
    var logs by mutableStateOf<List<AuditLogEntry>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var totalCount by mutableStateOf(0)
        private set
    var currentOffset by mutableStateOf(0)
    var selectedAction by mutableStateOf<String?>(null)

    val pageSize = 50

    init {
        loadLogs()
    }

    fun loadLogs() {
        viewModelScope.launch {
            if (logs.isEmpty()) {
                isLoading = true
            }
            errorMessage = null
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response =
                        auditLogApiService.getAuditLogs(
                            token = token,
                            limit = pageSize,
                            offset = currentOffset,
                            action = selectedAction,
                        )
                    logs = response.logs
                    totalCount = response.pagination.total
                    errorMessage = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (logs.isEmpty()) {
                    errorMessage = "Failed to load audit logs: ${e.message}"
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun onActionSelected(action: String?) {
        selectedAction = action
        currentOffset = 0
        loadLogs()
    }

    fun onNextPage() {
        currentOffset += pageSize
        loadLogs()
    }

    fun onPreviousPage() {
        currentOffset = (currentOffset - pageSize).coerceAtLeast(0)
        loadLogs()
    }
}
