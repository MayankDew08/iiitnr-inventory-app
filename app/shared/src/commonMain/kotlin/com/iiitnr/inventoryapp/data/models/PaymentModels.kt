package com.iiitnr.inventoryapp.data.models

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentStatus {
    PENDING,
    SUBMITTED,
    VERIFIED,
    REJECTED,
}

@Serializable
data class PaymentAllocation(
    val requestId: String,
    val amount: Int,
)

@Serializable
data class Payment(
    val id: String,
    val amount: Int,
    val status: PaymentStatus,
    val createdAt: String,
    val submittedAt: String? = null,
    val rejectedAt: String? = null,
    val rejectionReason: String? = null,
    val utr: String? = null,
    val proofImageUrl: String? = null,
    val allocations: List<PaymentAllocation> = emptyList(),
)

@Serializable
data class PaymentConfig(
    val upiVpa: String,
    val payeeName: String,
)

@Serializable
data class PaymentUpiInfo(
    val upiVpa: String,
    val payeeName: String,
    val reference: String,
)

@Serializable
data class CreatePaymentResponse(
    val payment: Payment,
    val upi: PaymentUpiInfo,
)

@Serializable
data class SubmitPaymentResponse(
    val payment: Payment,
)

@Serializable
data class MyPaymentsResponse(
    val payments: List<Payment>,
)
