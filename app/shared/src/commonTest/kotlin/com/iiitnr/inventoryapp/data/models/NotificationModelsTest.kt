package com.iiitnr.inventoryapp.data.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the Notifications screen against backend-side [NotificationType]
 * additions: unknown enum values must coerce to [NotificationType.UNKNOWN]
 * instead of throwing, or every student's Notifications screen crashes when a
 * new type ships on the server before their app updates.
 *
 * Mirror the production config used at the two deserialisation boundaries
 * ([com.iiitnr.inventoryapp.data.api.ApiClient] ContentNegotiation for REST,
 * [com.iiitnr.inventoryapp.data.api.NotificationApiService] for SSE).
 */
class NotificationModelsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    @Test
    fun deserialisingANotificationWithAnUnknownTypeCoercesToUnknown() {
        val payload =
            """{"id":"x","type":"SOMETHING_NEW","title":"t","createdAt":"2026-11-20T00:00:00Z"}"""

        val notification = json.decodeFromString<AppNotification>(payload)

        assertEquals(NotificationType.UNKNOWN, notification.type)
        assertEquals("t", notification.title)
    }

    @Test
    fun deserialisingANotificationWithAKnownTypePreservesIt() {
        val payload =
            """{"id":"x","type":"PAYMENT_VERIFIED","title":"t","createdAt":"2026-11-20T00:00:00Z"}"""

        val notification = json.decodeFromString<AppNotification>(payload)

        assertEquals(NotificationType.PAYMENT_VERIFIED, notification.type)
    }
}
