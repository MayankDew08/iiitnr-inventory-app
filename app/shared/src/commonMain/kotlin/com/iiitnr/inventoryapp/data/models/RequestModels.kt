package com.iiitnr.inventoryapp.data.models

import kotlinx.serialization.Serializable

@Serializable
data class RequestItem(
    val id: String,
    val requestId: String? = null,
    val componentId: String? = null,
    val quantity: Int,
    val fulfilledQuantity: Int = 0,
    val returnedQuantity: Int = 0,
    val writtenOffQuantity: Int = 0,
    val component: Component? = null,
)

@Serializable
data class Request(
    val id: String,
    val userId: String,
    val targetFacultyId: String,
    val projectTitle: String,
    val status: RequestStatus,
    val createdAt: String,
    val updatedAt: String,
    val fulfilledAt: String? = null,
    val returnedAt: String? = null,
    val items: List<RequestItem> = emptyList(),
    val user: User? = null,
    val targetFaculty: User? = null,
    val returnDueAt: String? = null,
    val lastRenewDate: String? = null,
    val lastRenewReason: String? = null,
    val renewalRequestedAt: String? = null,
    val receivedByUserId: String? = null,
    val lateFeeAccrued: Int = 0,
    val fineFinalizedAt: String? = null,
    val fineSettledAt: String? = null,
    val otp: String? = null,
)

@Serializable
data class RequestItemPayload(
    val componentId: String,
    val quantity: Int,
)

typealias IssueItemPayload = RequestItemPayload
typealias ReturnItemPayload = RequestItemPayload

@Serializable
data class CreateRequestPayload(
    val items: List<RequestItemPayload>,
    val targetFacultyId: String,
    val projectTitle: String,
)

@Serializable
data class FacultyResponse(
    val faculty: List<User>,
)

@Serializable
data class RequestResponse(
    val request: Request,
)

@Serializable
data class RequestOtpResponse(
    val otp: String,
    val expiresAt: String? = null,
)

@Serializable
data class RequestsResponse(
    val requests: List<Request>,
)

@Serializable
data class UpdateRequestStatusPayload(
    val status: RequestStatus,
    val lastRenewReason: String? = null,
    val issueItems: List<IssueItemPayload>? = null,
    val returnItems: List<ReturnItemPayload>? = null,
)
