package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.NotificationApiService
import com.iiitnr.inventoryapp.data.models.AppNotification
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.utils.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class NotificationsViewModel(
    private val tokenManager: TokenManager,
    private val notificationApiService: NotificationApiService,
) : ViewModel() {
    var notifications by mutableStateOf<List<AppNotification>>(emptyList())
        private set
    var unreadCount by mutableStateOf(0)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        loadNotifications()
        startNotificationStream()
    }

    fun loadNotifications() {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first() ?: return@launch
                val response = notificationApiService.getNotifications(token)
                notifications = response.notifications
                unreadCount = response.unreadCount
                errorMessage = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                errorMessage = e.toAppError().message
            } finally {
                isLoading = false
            }
        }
    }

    private fun startNotificationStream() {
        viewModelScope.launch {
            tokenManager.token.collectLatest { token ->
                if (token == null) return@collectLatest
                streamNotifications(token)
            }
        }
    }

    private suspend fun streamNotifications(token: String) {
        while (true) {
            try {
                notificationApiService.streamNotifications(token).collect { notification ->
                    notifications = listOf(notification) + notifications
                    unreadCount++
                }
                delay(STREAM_RECONNECT_DELAY_MS.milliseconds)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                delay(STREAM_RECONNECT_DELAY_MS.milliseconds)
            }
        }
    }

    fun markRead(notificationId: String) {
        val notification = notifications.firstOrNull { it.id == notificationId } ?: return
        if (notification.read) return

        notifications = notifications.map { if (it.id == notificationId) it.copy(read = true) else it }
        unreadCount = (unreadCount - 1).coerceAtLeast(0)

        viewModelScope.launch {
            try {
                val token = tokenManager.token.first() ?: return@launch
                notificationApiService.markRead(token, notificationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                loadNotifications()
            }
        }
    }

    fun markAllRead() {
        if (unreadCount == 0) return

        notifications = notifications.map { it.copy(read = true) }
        unreadCount = 0

        viewModelScope.launch {
            try {
                val token = tokenManager.token.first() ?: return@launch
                notificationApiService.markAllRead(token)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                loadNotifications()
            }
        }
    }

    fun refreshUnreadCount() {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first() ?: return@launch
                unreadCount = notificationApiService.getUnreadCount(token).unreadCount
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private companion object {
        const val STREAM_RECONNECT_DELAY_MS = 5_000L
    }
}
