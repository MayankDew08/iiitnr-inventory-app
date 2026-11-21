package com.iiitnr.inventoryapp.utils

import io.ktor.http.encodeURLParameter

object UpiUri {
    fun build(
        amount: Int,
        vpa: String,
        payee: String,
    ): String {
        require(amount > 0) { "amount must be positive" }
        require(vpa.isNotBlank()) { "vpa is required" }
        require(payee.isNotBlank()) { "payee is required" }

        // No transaction reference (tr=): some UPI apps reject a personal VPA
        // link that carries one, so the QR and intent carry only the payee and amount.
        return "upi://pay?pa=$vpa&pn=${payee.encodeURLParameter()}" +
            "&am=$amount&cu=INR"
    }
}
