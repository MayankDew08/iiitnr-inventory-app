package com.iiitnr.inventoryapp.data.api

import com.iiitnr.inventoryapp.data.models.CreatePaymentResponse
import com.iiitnr.inventoryapp.data.models.MyPaymentsResponse
import com.iiitnr.inventoryapp.data.models.PaymentConfig
import com.iiitnr.inventoryapp.data.models.SubmitPaymentResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

open class PaymentApi(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    open suspend fun createPaymentIntent(token: String): CreatePaymentResponse =
        client
            .post("$baseUrl/payments") {
                bearer(token)
            }.body()

    open suspend fun submitPayment(
        token: String,
        paymentId: String,
        utr: String,
        proof: ByteArray,
        proofMime: String,
    ): SubmitPaymentResponse =
        client
            .submitFormWithBinaryData(
                url = "$baseUrl/payments/$paymentId/submit",
                formData =
                    formData {
                        append("utr", utr.trim())
                        append(
                            "proof",
                            proof,
                            Headers.build {
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "filename=proof.${proofExtension(proofMime)}",
                                )
                                append(HttpHeaders.ContentType, proofMime)
                            },
                        )
                    },
            ) {
                bearer(token)
            }.body()

    open suspend fun getPaymentConfig(token: String): PaymentConfig =
        client
            .get("$baseUrl/payments/config") {
                bearer(token)
            }.body()

    open suspend fun listMyPayments(token: String): MyPaymentsResponse =
        client
            .get("$baseUrl/payments/mine") {
                bearer(token)
            }.body()

    private fun proofExtension(mime: String): String =
        when (mime.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/avif" -> "avif"
            else -> "img"
        }
}
