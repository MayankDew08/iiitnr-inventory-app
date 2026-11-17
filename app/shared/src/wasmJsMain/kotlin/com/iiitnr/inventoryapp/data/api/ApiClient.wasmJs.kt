package com.iiitnr.inventoryapp.data.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js
import kotlin.js.JsAny

private val includeCredentials: JsAny = js("'include'")

@OptIn(ExperimentalWasmJsInterop::class)
actual fun createHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Js) {
        engine {
            configureRequest {
                credentials = includeCredentials
            }
        }
        block()
    }
