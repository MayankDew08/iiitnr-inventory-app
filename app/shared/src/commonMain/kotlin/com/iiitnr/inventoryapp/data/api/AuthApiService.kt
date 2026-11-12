package com.iiitnr.inventoryapp.data.api

import com.iiitnr.inventoryapp.data.models.AuthResponse
import com.iiitnr.inventoryapp.data.models.ErrorResponse
import com.iiitnr.inventoryapp.data.models.GoogleSignInRequest
import com.iiitnr.inventoryapp.data.models.LoginRequest
import com.iiitnr.inventoryapp.data.models.MeResponse
import com.iiitnr.inventoryapp.data.models.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

class AuthApiService(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    private suspend fun parseErrorResponse(response: HttpResponse): String =
        try {
            response.body<ErrorResponse>().error
        } catch (_: Exception) {
            "Request failed"
        }

    suspend fun register(request: RegisterRequest): AuthResponse {
        val response =
            client.post("$baseUrl/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        return when (response.status.value) {
            in 200..299 -> response.body()
            else -> throw IllegalStateException("${response.status.value}: ${parseErrorResponse(response)}")
        }
    }

    suspend fun login(request: LoginRequest): AuthResponse {
        val response =
            client.post("$baseUrl/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        return when (response.status.value) {
            in 200..299 -> response.body()
            else -> throw IllegalStateException("${response.status.value}: ${parseErrorResponse(response)}")
        }
    }

    suspend fun signInWithGoogle(request: GoogleSignInRequest): AuthResponse {
        val response =
            client.post("$baseUrl/auth/google") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        return when (response.status.value) {
            in 200..299 -> response.body()
            else -> throw IllegalStateException("${response.status.value}: ${parseErrorResponse(response)}")
        }
    }

    suspend fun getMe(token: String): MeResponse =
        client
            .get("$baseUrl/auth/me") {
                headers {
                    append(HttpHeaders.Authorization, token)
                }
            }.body()
}
