package com.iiitnr.inventoryapp.data.api

import com.iiitnr.inventoryapp.data.models.AppNotification
import com.iiitnr.inventoryapp.data.models.NotificationsResponse
import com.iiitnr.inventoryapp.data.models.UnreadCountResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class NotificationApiService(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getNotifications(
        token: String,
        limit: Int = 50,
    ): NotificationsResponse =
        client
            .get("$baseUrl/notifications") {
                bearer(token)
                parameter("limit", limit)
            }.body()

    suspend fun getUnreadCount(token: String): UnreadCountResponse =
        client
            .get("$baseUrl/notifications/unread-count") {
                bearer(token)
            }.body()

    suspend fun markRead(
        token: String,
        id: String,
    ) {
        client.put("$baseUrl/notifications/$id/read") {
            bearer(token)
        }
    }

    suspend fun markAllRead(token: String) {
        client.put("$baseUrl/notifications/read-all") {
            bearer(token)
        }
    }

    fun streamNotifications(token: String): Flow<AppNotification> =
        flow {
            client.sse("$baseUrl/notifications/events", {
                bearer(token)
            }) {
                incoming.collect { event: ServerSentEvent ->
                    val data = event.data ?: return@collect
                    val notification =
                        runCatching { json.decodeFromString<AppNotification>(data) }.getOrNull()
                    if (notification != null) {
                        emit(notification)
                    }
                }
            }
        }
}
