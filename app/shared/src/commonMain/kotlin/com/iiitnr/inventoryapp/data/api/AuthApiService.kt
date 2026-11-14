package com.iiitnr.inventoryapp.data.api

import com.iiitnr.inventoryapp.data.models.AuthResponse
import com.iiitnr.inventoryapp.data.models.GoogleSignInRequest
import com.iiitnr.inventoryapp.data.models.LoginRequest
import com.iiitnr.inventoryapp.data.models.MeResponse
import com.iiitnr.inventoryapp.data.models.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class AuthApiService(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    suspend fun register(request: RegisterRequest): AuthResponse =
        client
            .post("$baseUrl/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

    suspend fun login(request: LoginRequest): AuthResponse =
        client
            .post("$baseUrl/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

    suspend fun signInWithGoogle(request: GoogleSignInRequest): AuthResponse =
        client
            .post("$baseUrl/auth/google") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

    suspend fun getMe(token: String): MeResponse =
        client
            .get("$baseUrl/auth/me") {
                bearer(token)
            }.body()
}
