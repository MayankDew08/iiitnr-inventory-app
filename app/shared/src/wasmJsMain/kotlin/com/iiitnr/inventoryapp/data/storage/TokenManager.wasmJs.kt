package com.iiitnr.inventoryapp.data.storage

import com.iiitnr.inventoryapp.data.api.ApiClient
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

class WasmTokenManager : TokenManager {
    private val _token = MutableStateFlow<String?>(null)
    override val token: Flow<String?> = _token.asStateFlow()

    init {
        runCatching { window.localStorage.removeItem(LEGACY_STORAGE_KEY) }
    }

    override suspend fun saveToken(token: String) {
        _token.value = token
    }

    override suspend fun clearToken() {
        _token.value = null
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun clearSession() {
        try {
            withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { ApiClient.authApiService.logout() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        clearToken()
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun restoreSession(): String? {
        val response =
            try {
                ApiClient.authApiService.createSession()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return null
            }
        _token.value = response.token
        return response.token
    }
}

actual fun createTokenManager(): TokenManager = WasmTokenManager()

private const val LEGACY_STORAGE_KEY = "auth_token"

private const val LOGOUT_TIMEOUT_MS = 5_000L
