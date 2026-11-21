package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.iiitnr.inventoryapp.data.api.PaymentApi
import com.iiitnr.inventoryapp.data.api.RequestApiService
import com.iiitnr.inventoryapp.data.models.CreatePaymentResponse
import com.iiitnr.inventoryapp.data.models.MyPaymentsResponse
import com.iiitnr.inventoryapp.data.models.Payment
import com.iiitnr.inventoryapp.data.models.PaymentConfig
import com.iiitnr.inventoryapp.data.models.PaymentStatus
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.data.models.RequestStatus
import com.iiitnr.inventoryapp.data.models.RequestsResponse
import com.iiitnr.inventoryapp.data.models.SubmitPaymentResponse
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.ui.theme.AppTheme
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FinesPaymentSmokeTest {
    @Test
    fun pendingBannerRendersForSeededPendingPayment() =
        runComposeUiTest {
            val pending = pendingPayment(30)
            val viewModel =
                FinesViewModel(
                    tokenManager = EmptyTokenManager(),
                    requestApiService = UnusedRequestApi(),
                    paymentApi = UnusedPaymentApi(),
                    initialPayments = listOf(pending),
                    loadOnStart = false,
                )

            setContent {
                AppTheme {
                    FinesScreen(
                        onNavigateBack = {},
                        onStartPayment = {},
                        onResumePayment = {},
                        viewModel = viewModel,
                    )
                }
            }

            onNodeWithText("Finish your payment").assertIsDisplayed()
            onNodeWithText("You started a payment of ₹30", substring = true).assertIsDisplayed()
            // Submit UTR is the primary action while a PENDING payment exists.
            onNodeWithText("Submit UTR").assertIsDisplayed()
        }

    @Test
    fun payAgainIsConfirmedBeforeStartingAnotherTransfer() =
        runComposeUiTest {
            var starts = 0
            val fine =
                Request(
                    id = "req-1",
                    userId = "student-1",
                    targetFacultyId = "faculty-1",
                    projectTitle = "IoT weather station",
                    status = RequestStatus.EXPIRED,
                    createdAt = "2026-11-01T00:00:00Z",
                    updatedAt = "2026-11-01T00:00:00Z",
                    returnedAt = "2026-11-10T00:00:00Z",
                    lateFeeAccrued = 30,
                    fineFinalizedAt = "2026-11-10T00:00:00Z",
                )
            val viewModel =
                FinesViewModel(
                    tokenManager = EmptyTokenManager(),
                    requestApiService = UnusedRequestApi(),
                    paymentApi = UnusedPaymentApi(),
                    initialFines = listOf(fine),
                    initialPayments = listOf(pendingPayment(30)),
                    loadOnStart = false,
                )

            setContent {
                AppTheme {
                    FinesScreen(
                        onNavigateBack = {},
                        onStartPayment = { starts++ },
                        onResumePayment = {},
                        viewModel = viewModel,
                    )
                }
            }

            // The bottom button is demoted to "Pay again" and must not start a
            // transfer on the first tap: it opens a confirm steering to Submit UTR.
            onNodeWithText("Pay again").assertIsEnabled().performClick()
            onNodeWithText("Pay again?").assertIsDisplayed()
            assertEquals(0, starts)

            onNodeWithText("Pay again anyway").performClick()
            assertEquals(1, starts)
        }

    @Test
    fun payButtonIsDisabledOnceAPaymentIsSubmitted() =
        runComposeUiTest {
            val fine =
                Request(
                    id = "req-1",
                    userId = "student-1",
                    targetFacultyId = "faculty-1",
                    projectTitle = "IoT weather station",
                    status = RequestStatus.EXPIRED,
                    createdAt = "2026-11-01T00:00:00Z",
                    updatedAt = "2026-11-01T00:00:00Z",
                    returnedAt = "2026-11-10T00:00:00Z",
                    lateFeeAccrued = 30,
                    fineFinalizedAt = "2026-11-10T00:00:00Z",
                )
            val viewModel =
                FinesViewModel(
                    tokenManager = EmptyTokenManager(),
                    requestApiService = UnusedRequestApi(),
                    paymentApi = UnusedPaymentApi(),
                    initialFines = listOf(fine),
                    initialPayments = listOf(pendingPayment(30).copy(status = PaymentStatus.SUBMITTED)),
                    loadOnStart = false,
                )

            setContent {
                AppTheme {
                    FinesScreen(
                        onNavigateBack = {},
                        onStartPayment = {},
                        onResumePayment = {},
                        viewModel = viewModel,
                    )
                }
            }

            onNodeWithText("Pay ₹30").assertIsNotEnabled()
            onNodeWithText("Your payment is awaiting verification").assertIsDisplayed()
        }

    private fun pendingPayment(amount: Int) =
        Payment(
            id = "pay-1",
            amount = amount,
            status = PaymentStatus.PENDING,
            createdAt = "2026-11-17T10:00:00Z",
        )

    private class EmptyTokenManager : TokenManager {
        override val token: Flow<String?> = emptyFlow()

        override suspend fun saveToken(token: String) = Unit

        override suspend fun clearToken() = Unit
    }

    private class UnusedRequestApi : RequestApiService(HttpClient {}, "http://localhost") {
        override suspend fun getRequests(
            token: String,
            status: String?,
        ): RequestsResponse = RequestsResponse(emptyList())
    }

    private class UnusedPaymentApi : PaymentApi(HttpClient {}, "http://localhost") {
        override suspend fun createPaymentIntent(token: String): CreatePaymentResponse = error("unused")

        override suspend fun submitPayment(
            token: String,
            paymentId: String,
            utr: String,
            proof: ByteArray,
            proofMime: String,
        ): SubmitPaymentResponse = error("unused")

        override suspend fun getPaymentConfig(token: String): PaymentConfig = error("unused")

        override suspend fun listMyPayments(token: String): MyPaymentsResponse = MyPaymentsResponse(emptyList())
    }
}
