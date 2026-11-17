package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.AuthApiService
import com.iiitnr.inventoryapp.data.models.User
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.utils.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val tokenManager: TokenManager,
    private val authApiService: AuthApiService,
) : ViewModel() {
    var user by mutableStateOf<User?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        loadUser()
    }

    fun loadUser() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response = authApiService.getMe(token)
                    user = response.user
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                errorMessage = "Failed to load profile: ${e.toAppError().message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun logout(onSuccess: () -> Unit) {
        viewModelScope.launch {
            tokenManager.clearSession()
            onSuccess()
        }
    }
}
