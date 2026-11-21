package com.iiitnr.inventoryapp.ui.screens.payment

import com.iiitnr.inventoryapp.data.api.PaymentApi
import com.iiitnr.inventoryapp.data.models.AppHttpException
import com.iiitnr.inventoryapp.data.models.CreatePaymentResponse
import com.iiitnr.inventoryapp.data.models.MyPaymentsResponse
import com.iiitnr.inventoryapp.data.models.Payment
import com.iiitnr.inventoryapp.data.models.PaymentConfig
import com.iiitnr.inventoryapp.data.models.PaymentErrorCodes
import com.iiitnr.inventoryapp.data.models.PaymentStatus
import com.iiitnr.inventoryapp.data.models.PaymentUpiInfo
import com.iiitnr.inventoryapp.data.models.SubmitPaymentResponse
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.ui.platform.ImageResult
import com.iiitnr.inventoryapp.ui.platform.ProofPicker
import com.iiitnr.inventoryapp.utils.IntentLauncher
import com.iiitnr.inventoryapp.utils.LaunchResult
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
class PaymentViewModelTest {
    private lateinit var api: FakePaymentApi
    private lateinit var launcher: FakeIntentLauncher
    private lateinit var picker: FakeProofPicker
    private lateinit var viewModel: PaymentViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = FakePaymentApi()
        api.onCreate = { intentResponse() }
        launcher = FakeIntentLauncher()
        picker = FakeProofPicker()
        viewModel =
            PaymentViewModel(
                tokenManager = FakeTokenManager(),
                api = api,
                launcher = launcher,
                picker = picker,
            )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun idleToCreatingIntentOnPayTapped() {
        val gate = CompletableDeferred<Unit>()
        api.onCreate = {
            gate.await()
            intentResponse()
        }

        viewModel.onPayTapped()

        assertEquals(PaymentUiState.CreatingIntent, viewModel.uiState)
        gate.complete(Unit)
    }

    @Test
    fun intentReadyToAwaitingReturnOnLauncherSuccess() {
        launcher.result = LaunchResult.Launched

        viewModel.onPayTapped()

        assertIs<PaymentUiState.AwaitingReturn>(viewModel.uiState)
    }

    @Test
    fun intentReadyToQrFallbackWhenNoAppAvailable() {
        launcher.result = LaunchResult.NoAppAvailable

        viewModel.onPayTapped()

        val state = assertIs<PaymentUiState.QrFallback>(viewModel.uiState)
        assertTrue(state.uri.startsWith("upi://pay?"))
    }

    @Test
    fun creatingIntentToErrorOnNoUnpaidFines() {
        api.onCreate = { throw AppHttpException(400, "Nothing to pay", PaymentErrorCodes.NO_UNPAID_FINES) }

        viewModel.onPayTapped()

        assertEquals("No fines to pay", (viewModel.uiState as PaymentUiState.Error).message)
    }

    @Test
    fun creatingIntentToErrorOnPaymentAwaitingVerification() {
        api.onCreate = {
            throw AppHttpException(409, "Already submitted", PaymentErrorCodes.PAYMENT_AWAITING_VERIFICATION)
        }

        viewModel.onPayTapped()

        assertEquals(
            "Your payment is awaiting verification",
            (viewModel.uiState as PaymentUiState.Error).message,
        )
    }

    @Test
    fun enteringUtrBlocksSubmitWhenUtrIsBlank() {
        enterUtr()
        viewModel.onUtrChanged("   ")

        assertFalse(viewModel.canSubmit)
    }

    @Test
    fun utrInputKeepsOnlyDigitsUpToTwelve() {
        enterUtr()
        viewModel.onUtrChanged("12ab34 5678901234")

        val state = assertIs<PaymentUiState.EnteringUtr>(viewModel.uiState)
        assertEquals("123456789012", state.utr)
    }

    @Test
    fun elevenDigitUtrBlocksSubmit() {
        enterUtr()
        viewModel.onUtrChanged("12345678901")
        picker.image = ImageResult(ByteArray(16), "proof.jpg")
        viewModel.onPickProof()

        assertFalse(viewModel.canSubmit)
    }

    @Test
    fun enteringUtrBlocksSubmitWhenProofIsNull() {
        enterUtr()
        viewModel.onUtrChanged("123456789012")

        assertFalse(viewModel.canSubmit)
    }

    @Test
    fun submittingToSubmittedOnSuccess() {
        enterUtrWithProofAndUtr()

        viewModel.onSubmitTapped()

        assertIs<PaymentUiState.Submitted>(viewModel.uiState)
    }

    @Test
    fun submittingToSubmittedOnConflictRecovery() {
        enterUtrWithProofAndUtr()
        api.onSubmit = { throw AppHttpException(409, "Already submitted", "PAYMENT_NOT_PENDING") }

        viewModel.onSubmitTapped()

        assertIs<PaymentUiState.Submitted>(viewModel.uiState)
    }

    @Test
    fun submittingToEnteringUtrWithErrorOnInvalidUtr() {
        enterUtrWithProofAndUtr()
        api.onSubmit = { throw AppHttpException(400, "invalid_utr", PaymentErrorCodes.INVALID_UTR) }

        viewModel.onSubmitTapped()

        val state = assertIs<PaymentUiState.EnteringUtr>(viewModel.uiState)
        assertEquals("Enter the 12-digit UTR from your payment receipt", state.submitError)
    }

    @Test
    fun submittingToEnteringUtrWithErrorOnUtrAlreadyUsed() {
        enterUtrWithProofAndUtr()
        api.onSubmit = { throw AppHttpException(409, "utr_already_used", PaymentErrorCodes.UTR_ALREADY_USED) }

        viewModel.onSubmitTapped()

        val state = assertIs<PaymentUiState.EnteringUtr>(viewModel.uiState)
        assertEquals("This UTR has already been used for another payment", state.submitError)
    }

    @Test
    fun submittingToEnteringUtrWithErrorOnUtrBlocked() {
        enterUtrWithProofAndUtr()
        api.onSubmit = { throw AppHttpException(409, "utr_blocked", PaymentErrorCodes.UTR_BLOCKED) }

        viewModel.onSubmitTapped()

        val state = assertIs<PaymentUiState.EnteringUtr>(viewModel.uiState)
        assertEquals("This UTR cannot be used again", state.submitError)
    }

    @Test
    fun submittingToEnteringUtrWithErrorPreservesInputs() {
        enterUtrWithProofAndUtr()
        api.onSubmit = { throw AppHttpException(500, "Server error", null) }

        viewModel.onSubmitTapped()

        val state = assertIs<PaymentUiState.EnteringUtr>(viewModel.uiState)
        assertEquals("123456789012", state.utr)
        assertTrue(state.proof != null)
        assertTrue(state.submitError!!.isNotBlank())
    }

    @Test
    fun oversizedProofSetsErrorWithoutAdvancing() {
        enterUtr()
        picker.image = ImageResult(ByteArray(10 * 1024 * 1024 + 1), "proof.jpg")

        viewModel.onPickProof()

        val state = assertIs<PaymentUiState.EnteringUtr>(viewModel.uiState)
        assertTrue(state.proof == null)
        assertTrue(state.submitError!!.contains("10 MB"))
    }

    @Test
    fun wrongMimeSetsErrorWithoutAdvancing() {
        enterUtr()
        picker.image = ImageResult(ByteArray(16), "proof.txt")

        viewModel.onPickProof()

        val state = assertIs<PaymentUiState.EnteringUtr>(viewModel.uiState)
        assertTrue(state.proof == null)
        assertTrue(state.submitError!!.isNotBlank())
    }

    @Test
    fun resumePendingPaymentEntersUtrForPendingPayment() {
        api.payments =
            listOf(
                payment().copy(id = "pending-1", status = PaymentStatus.PENDING),
            )

        viewModel.onResumePendingPayment()

        val state = assertIs<PaymentUiState.EnteringUtr>(viewModel.uiState)
        assertEquals("pending-1", state.payment.id)
    }

    private fun enterUtr() {
        launcher.result = LaunchResult.Launched
        viewModel.onPayTapped()
        viewModel.onEnterUtrTapped()
    }

    private fun enterUtrWithProofAndUtr() {
        enterUtr()
        viewModel.onUtrChanged("123456789012")
        picker.image = ImageResult(ByteArray(16), "proof.jpg")
        viewModel.onPickProof()
        assertTrue(viewModel.canSubmit)
    }

    private fun intentResponse() =
        CreatePaymentResponse(
            payment = payment(),
            upi = PaymentUpiInfo("vpa@bank", "IIITNR", "ref-1"),
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
        var onCreate: (suspend () -> CreatePaymentResponse)? = null
        var onSubmit: (suspend () -> SubmitPaymentResponse)? = null
        var payments: List<Payment> = emptyList()

        override suspend fun createPaymentIntent(token: String): CreatePaymentResponse =
            onCreate?.invoke() ?: error("createPaymentIntent not configured")

        override suspend fun submitPayment(
            token: String,
            paymentId: String,
            utr: String,
            proof: ByteArray,
            proofMime: String,
        ): SubmitPaymentResponse =
            onSubmit?.invoke() ?: SubmitPaymentResponse(payment().copy(status = PaymentStatus.SUBMITTED))

        override suspend fun getPaymentConfig(token: String): PaymentConfig = PaymentConfig("vpa@bank", "IIITNR")

        override suspend fun listMyPayments(token: String): MyPaymentsResponse = MyPaymentsResponse(payments)
    }

    private class FakeIntentLauncher : IntentLauncher {
        var result: LaunchResult = LaunchResult.Launched

        override fun launchUpi(uri: String): LaunchResult = result
    }

    private class FakeProofPicker : ProofPicker {
        var image: ImageResult? = null

        override suspend fun pick(): ImageResult? = image
    }
}

private fun payment() =
    Payment(
        id = "pay-1",
        amount = 50,
        status = PaymentStatus.PENDING,
        createdAt = "2026-11-17T10:00:00Z",
    )
