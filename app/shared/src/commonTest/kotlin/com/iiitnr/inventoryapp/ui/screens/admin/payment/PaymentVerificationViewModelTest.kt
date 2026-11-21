package com.iiitnr.inventoryapp.ui.screens.admin.payment

import com.iiitnr.inventoryapp.data.api.PaymentApi
import com.iiitnr.inventoryapp.data.models.AllocationItem
import com.iiitnr.inventoryapp.data.models.AppHttpException
import com.iiitnr.inventoryapp.data.models.CreatePaymentResponse
import com.iiitnr.inventoryapp.data.models.MyPaymentsResponse
import com.iiitnr.inventoryapp.data.models.Payment
import com.iiitnr.inventoryapp.data.models.PaymentConfig
import com.iiitnr.inventoryapp.data.models.PaymentDetail
import com.iiitnr.inventoryapp.data.models.PaymentDetailAllocation
import com.iiitnr.inventoryapp.data.models.PaymentRejectionReason
import com.iiitnr.inventoryapp.data.models.PaymentStatus
import com.iiitnr.inventoryapp.data.models.PendingPaymentSummary
import com.iiitnr.inventoryapp.data.models.PendingPaymentsResponse
import com.iiitnr.inventoryapp.data.models.StudentInfo
import com.iiitnr.inventoryapp.data.models.SubmitPaymentResponse
import com.iiitnr.inventoryapp.data.storage.TokenManager
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentVerificationViewModelTest {
    private lateinit var api: FakePaymentApi

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = FakePaymentApi()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun listViewModelLoadsPendingOnInit() {
        api.pending =
            listOf(
                PendingPaymentSummary(
                    id = "pay-1",
                    userId = "student-1",
                    studentName = "Aarav",
                    studentEmail = "aarav@example.com",
                    amount = 50,
                    utr = "UTR123",
                    submittedAt = "2026-11-18T10:00:00Z",
                ),
            )

        val viewModel = PaymentVerificationsViewModel(FakeTokenManager(), api)

        assertFalse(viewModel.isLoading)
        assertEquals("pay-1", viewModel.payments.single().id)
        assertEquals(null, viewModel.errorMessage)
    }

    @Test
    fun verifyGoesThroughSubmittingToVerified() {
        val gate = CompletableDeferred<Unit>()
        api.onVerify = { gate.await() }
        val viewModel = detailViewModel()

        viewModel.onVerifyTapped()

        assertEquals(PaymentVerificationDetailUiState.Submitting, viewModel.uiState)
        gate.complete(Unit)
        assertEquals(PaymentVerificationDetailUiState.Verified, viewModel.uiState)
    }

    @Test
    fun rejectWithoutReasonIsBlocked() {
        val viewModel = detailViewModel()

        viewModel.onRejectTapped()

        assertIs<PaymentVerificationDetailUiState.Loaded>(viewModel.uiState)
        assertFalse(api.rejectCalled)
        assertTrue(viewModel.actionError!!.contains("reason"))
    }

    @Test
    fun conflictOnVerifySurfacesAlreadyResolvedError() {
        api.onVerify = { throw AppHttpException(409, "Conflict", "PAYMENT_NOT_SUBMITTED") }
        val viewModel = detailViewModel()

        viewModel.onVerifyTapped()

        val state = assertIs<PaymentVerificationDetailUiState.Error>(viewModel.uiState)
        assertTrue(state.message.contains("already resolved"))
    }

    private fun detailViewModel(): PaymentVerificationDetailViewModel =
        PaymentVerificationDetailViewModel(
            paymentId = "pay-1",
            tokenManager = FakeTokenManager(),
            api = api,
        )

    private class FakeTokenManager : TokenManager {
        private val _token = MutableStateFlow<String?>("token")
        override val token: Flow<String?> = _token

        override suspend fun saveToken(token: String) {
            _token.value = token
        }

        override suspend fun clearToken() {
            _token.value = null
        }
    }

    private class FakePaymentApi : PaymentApi(HttpClient {}, "http://localhost") {
        var pending: List<PendingPaymentSummary> = emptyList()
        var onVerify: (suspend () -> Unit)? = null
        var onReject: (suspend () -> Unit)? = null
        var rejectCalled = false

        override suspend fun listPendingForAdmin(token: String): PendingPaymentsResponse =
            PendingPaymentsResponse(pending)

        override suspend fun getPaymentForAdmin(
            token: String,
            paymentId: String,
        ): PaymentDetail = detail()

        override suspend fun verifyPayment(
            token: String,
            paymentId: String,
        ): Payment {
            onVerify?.invoke()
            return payment().copy(status = PaymentStatus.VERIFIED)
        }

        override suspend fun rejectPayment(
            token: String,
            paymentId: String,
            reasonCode: PaymentRejectionReason,
            notes: String?,
        ): Payment {
            rejectCalled = true
            onReject?.invoke()
            return payment().copy(status = PaymentStatus.REJECTED)
        }

        override suspend fun createPaymentIntent(token: String): CreatePaymentResponse = error("not used")

        override suspend fun submitPayment(
            token: String,
            paymentId: String,
            utr: String,
            proof: ByteArray,
            proofMime: String,
        ): SubmitPaymentResponse = error("not used")

        override suspend fun getPaymentConfig(token: String): PaymentConfig = error("not used")

        override suspend fun listMyPayments(token: String): MyPaymentsResponse = error("not used")
    }

    private companion object {
        fun payment() =
            Payment(
                id = "pay-1",
                amount = 50,
                status = PaymentStatus.SUBMITTED,
                createdAt = "2026-11-18T09:00:00Z",
                submittedAt = "2026-11-18T10:00:00Z",
                utr = "UTR123",
                proofImageUrl = "https://example.com/proof.jpg",
            )

        fun detail() =
            PaymentDetail(
                payment = payment(),
                student = StudentInfo("student-1", "Aarav", "aarav@example.com"),
                allocations =
                    listOf(
                        PaymentDetailAllocation(
                            requestId = "req-1",
                            amount = 50,
                            projectTitle = "Week 4 project",
                            returnDueAt = null,
                            returnedAt = null,
                            items = listOf(AllocationItem("Arduino Uno", 1)),
                        ),
                    ),
            )
    }
}
