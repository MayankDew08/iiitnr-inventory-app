package com.iiitnr.inventoryapp.data.api

import com.iiitnr.inventoryapp.data.models.AuditLogsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

class AuditLogApiService(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    suspend fun getAuditLogs(
        token: String,
        limit: Int = 50,
        offset: Int = 0,
        userId: String? = null,
        action: String? = null,
        entityType: String? = null,
    ): AuditLogsResponse =
        client
            .get("$baseUrl/admin/audit-logs") {
                bearer(token)
                parameter("limit", limit)
                parameter("offset", offset)
                if (!userId.isNullOrBlank()) parameter("userId", userId)
                if (!action.isNullOrBlank()) parameter("action", action)
                if (!entityType.isNullOrBlank()) parameter("entityType", entityType)
            }.body()
}
