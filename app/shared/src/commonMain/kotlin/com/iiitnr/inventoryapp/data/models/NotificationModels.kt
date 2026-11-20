package com.iiitnr.inventoryapp.data.models

import kotlinx.serialization.Serializable

@Serializable
enum class NotificationType {
    REQUEST_SUBMITTED,
    REQUEST_APPROVED,
    REQUEST_REJECTED,
    REQUEST_ISSUED,
    REQUEST_PARTIALLY_ISSUED,
    REQUEST_RETURNED,
    REQUEST_PARTIALLY_RETURNED,
    REQUEST_EXPIRED,
    RENEWAL_REQUESTED,
    RENEWAL_APPROVED,
    RENEWAL_REJECTED,
    PAYMENT_VERIFIED,
    PAYMENT_REJECTED,
    UNKNOWN,
}

@Serializable
data class AppNotification(
    val id: String,
    val type: NotificationType = NotificationType.UNKNOWN,
    val title: String,
    val body: String? = null,
    val requestId: String? = null,
    val read: Boolean = false,
    val createdAt: String,
)

@Serializable
data class NotificationsResponse(
    val notifications: List<AppNotification>,
    val unreadCount: Int = 0,
)

@Serializable
data class UnreadCountResponse(
    val unreadCount: Int = 0,
)
