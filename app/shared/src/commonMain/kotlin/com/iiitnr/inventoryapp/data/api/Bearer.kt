package com.iiitnr.inventoryapp.data.api

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders

internal fun HttpRequestBuilder.bearer(token: String) {
    headers {
        append(HttpHeaders.Authorization, "Bearer $token")
    }
}
