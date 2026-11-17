package com.iiitnr.inventoryapp.data.storage

import kotlinx.coroutines.flow.Flow

interface TokenManager {
    val token: Flow<String?>

    suspend fun saveToken(token: String)

    suspend fun clearToken()

    suspend fun clearSession() {
        clearToken()
    }

    suspend fun restoreSession(): String? = null
}

expect fun createTokenManager(): TokenManager
