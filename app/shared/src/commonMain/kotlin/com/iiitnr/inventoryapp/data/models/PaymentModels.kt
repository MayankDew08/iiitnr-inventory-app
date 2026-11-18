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
enum class PaymentRejectionReason {
    UTR_NOT_FOUND,
    AMOUNT_MISMATCH,
    PROOF_UNCLEAR,
    DUPLICATE_PAYMENT,
    OTHER,
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
    val rejectionReasonCode: PaymentRejectionReason? = null,
    val verifiedAt: String? = null,
    val verifiedByUserId: String? = null,
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

@Serializable
data class PendingPaymentSummary(
    val id: String,
    val userId: String,
    val studentName: String?,
    val studentEmail: String,
    val amount: Int,
    val utr: String?,
    val submittedAt: String?,
)

@Serializable
data class PendingPaymentsResponse(
    val payments: List<PendingPaymentSummary>,
)

@Serializable
data class AllocationItem(
    val componentName: String,
    val quantity: Int,
)

@Serializable
data class PaymentDetailAllocation(
    val requestId: String,
    val amount: Int,
    val projectTitle: String,
    val returnDueAt: String?,
    val returnedAt: String?,
    val items: List<AllocationItem>,
)

@Serializable
data class StudentInfo(
    val id: String,
    val name: String?,
    val email: String,
)

@Serializable
data class PaymentDetail(
    val payment: Payment,
    val student: StudentInfo,
    val allocations: List<PaymentDetailAllocation>,
)

@Serializable
data class RejectPaymentRequest(
    val reasonCode: PaymentRejectionReason,
    val notes: String? = null,
)
