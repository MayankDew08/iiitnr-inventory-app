package com.iiitnr.inventoryapp.utils

import io.ktor.http.encodeURLParameter

object UpiUri {
    fun build(
        amount: Int,
        vpa: String,
        payee: String,
        reference: String,
    ): String {
        require(amount > 0) { "amount must be positive" }
        require(vpa.isNotBlank()) { "vpa is required" }
        require(payee.isNotBlank()) { "payee is required" }
        require(reference.isNotBlank()) { "reference is required" }

        return "upi://pay?pa=$vpa&pn=${payee.encodeURLParameter()}" +
            "&am=$amount&cu=INR&tr=${reference.encodeURLParameter()}"
    }
}
