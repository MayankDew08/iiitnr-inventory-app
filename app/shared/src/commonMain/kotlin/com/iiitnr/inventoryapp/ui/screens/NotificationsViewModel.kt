package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.NotificationApiService
import com.iiitnr.inventoryapp.data.models.AppHttpException
import com.iiitnr.inventoryapp.data.models.AppNotification
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.utils.toAppError
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.http.HttpHeaders
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
        var backoffMs = STREAM_RECONNECT_DELAY_MS
        while (true) {
            try {
                notificationApiService.streamNotifications(token).collect { notification ->
                    notifications = listOf(notification) + notifications
                    unreadCount++
                }
                backoffMs = STREAM_RECONNECT_DELAY_MS
                delay(backoffMs.milliseconds)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                delay((e.streamRetryDelayMs() ?: backoffMs).milliseconds)
                backoffMs = (backoffMs * 2).coerceAtMost(STREAM_MAX_RECONNECT_DELAY_MS)
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
        const val STREAM_MAX_RECONNECT_DELAY_MS = 60_000L
        const val RATE_LIMIT_STATUS = 429
        const val DEFAULT_RATE_LIMIT_WAIT_MS = 60_000L

        fun Throwable.streamRetryDelayMs(): Long? =
            when (this) {
                is SSEClientException -> {
                    val response = response
                    if (response?.status?.value != RATE_LIMIT_STATUS) {
                        null
                    } else {
                        retryAfterMs(response.headers[HttpHeaders.RetryAfter])
                            ?: DEFAULT_RATE_LIMIT_WAIT_MS
                    }
                }

                is AppHttpException ->
                    if (status == RATE_LIMIT_STATUS) DEFAULT_RATE_LIMIT_WAIT_MS else null

                else -> null
            }

        fun retryAfterMs(header: String?): Long? =
            header
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.times(1_000)
    }
}
