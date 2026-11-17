package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.iiitnr.inventoryapp.data.api.PaymentApi
import com.iiitnr.inventoryapp.data.api.RequestApiService
import com.iiitnr.inventoryapp.data.models.CreatePaymentResponse
import com.iiitnr.inventoryapp.data.models.MyPaymentsResponse
import com.iiitnr.inventoryapp.data.models.Payment
import com.iiitnr.inventoryapp.data.models.PaymentConfig
import com.iiitnr.inventoryapp.data.models.PaymentStatus
import com.iiitnr.inventoryapp.data.models.RequestsResponse
import com.iiitnr.inventoryapp.data.models.SubmitPaymentResponse
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.ui.theme.AppTheme
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class FinesPaymentSmokeTest {
    @Test
    fun pendingBannerRendersForSeededPendingPayment() =
        runComposeUiTest {
            val pending =
                Payment(
                    id = "pay-1",
                    amount = 30,
                    status = PaymentStatus.PENDING,
                    createdAt = "2026-11-17T10:00:00Z",
                )
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

            onNodeWithText("Payment in progress").assertIsDisplayed()
            onNodeWithText("You started a payment of ₹30,", substring = true).assertIsDisplayed()
        }

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
