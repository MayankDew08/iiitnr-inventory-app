package com.iiitnr.inventoryapp.data.models

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorResponse(
    val error: String,
    val code: String? = null,
)

class AppHttpException(
    val status: Int,
    val errorMessage: String?,
    val errorCode: String?,
) : Exception(errorMessage ?: "HTTP $status")

sealed class AppError(
    open val message: String,
) {
    data class NetworkError(
        val originalException: Throwable,
    ) : AppError("Network error occurred")

    data class ApiError(
        override val message: String,
    ) : AppError(message)

    data object Unauthorized : AppError("Session expired. Please login again.")

    data class Forbidden(
        override val message: String = "Access denied",
    ) : AppError(message)

    data class NotFound(
        val entity: String,
    ) : AppError("$entity not found")

    data class Unknown(
        val originalException: Throwable,
    ) : AppError(
            originalException.message ?: "An unexpected error occurred",
        )
}
