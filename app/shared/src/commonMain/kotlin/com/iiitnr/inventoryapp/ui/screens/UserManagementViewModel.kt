package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.UserApiService
import com.iiitnr.inventoryapp.data.models.UpdateUserRequest
import com.iiitnr.inventoryapp.data.models.User
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.utils.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class UserManagementViewModel(
    private val tokenManager: TokenManager,
    private val userApiService: UserApiService,
) : ViewModel() {
    var users by mutableStateOf<List<User>>(emptyList())
        private set
    var isLoading by mutableStateOf(value = true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var totalCount by mutableStateOf(0)
        private set
    var currentOffset by mutableStateOf(0)
    var searchQuery by mutableStateOf("")

    val pageSize = 50

    init {
        loadUsers()
    }

    fun loadUsers() {
        viewModelScope.launch {
            if (users.isEmpty()) {
                isLoading = true
            }
            errorMessage = null
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response =
                        userApiService.getUsers(
                            token = token,
                            limit = pageSize,
                            offset = currentOffset,
                            search = searchQuery.trim().ifBlank { null },
                        )
                    users = response.users
                    totalCount = response.pagination.total
                    errorMessage = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errorMsg = e.toAppError().message
                if (users.isEmpty()) {
                    errorMessage = errorMsg
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        searchQuery = query
        currentOffset = 0
        viewModelScope.launch {
            delay(300.milliseconds)
            loadUsers()
        }
    }

    fun onNextPage() {
        currentOffset += pageSize
        loadUsers()
    }

    fun onPreviousPage() {
        currentOffset = (currentOffset - pageSize).coerceAtLeast(0)
        loadUsers()
    }

    fun updateUser(
        userId: String,
        request: UpdateUserRequest,
    ) {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    userApiService.updateUser(token, userId, request)
                    loadUsers()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                errorMessage = "Failed to update user: ${e.toAppError().message}"
            }
        }
    }
}
