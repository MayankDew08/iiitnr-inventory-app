package com.iiitnr.inventoryapp.data.api

import com.iiitnr.inventoryapp.data.models.UpdateUserRequest
import com.iiitnr.inventoryapp.data.models.User
import com.iiitnr.inventoryapp.data.models.UserPagination
import com.iiitnr.inventoryapp.data.models.UserResponse
import com.iiitnr.inventoryapp.data.models.UsersResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

class UserApiService(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    suspend fun getUsers(
        token: String,
        limit: Int = 50,
        offset: Int = 0,
        search: String? = null,
    ): UsersResponse {
        val response =
            client.get("$baseUrl/admin/users") {
                bearer(token)
                parameter("limit", limit)
                parameter("offset", offset)
                if (!search.isNullOrBlank()) parameter("search", search)
            }
        if (response.status == HttpStatusCode.NoContent) {
            return UsersResponse(
                users = emptyList(),
                pagination = UserPagination(0, limit, offset, false),
            )
        }
        return response.body()
    }

    suspend fun updateUser(
        token: String,
        userId: String,
        request: UpdateUserRequest,
    ): User =
        client
            .patch("$baseUrl/admin/users/$userId") {
                bearer(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<UserResponse>()
            .user
}
