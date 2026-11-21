package com.iiitnr.inventoryapp.data.api

import com.iiitnr.inventoryapp.data.BuildFlags
import com.iiitnr.inventoryapp.data.models.ApiErrorResponse
import com.iiitnr.inventoryapp.data.models.AppHttpException
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequest
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun createHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient

expect fun devBaseUrl(): String

object ApiClient {
    private val BASE_URL: String = if (BuildFlags.IS_DEBUG) devBaseUrl() else PRODUCTION_BASE_URL

    private const val PRODUCTION_BASE_URL = "https://inventory.iiitnr.ac.in/api"

    private val errorJson = Json { ignoreUnknownKeys = true }

    val client: HttpClient =
        createHttpClient {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = false
                        // Future-proof against backend-side enum additions (e.g.
                        // new NotificationType members): unknown enum values
                        // coerce to the field's default instead of crashing the
                        // screen that renders them.
                        coerceInputValues = true
                    },
                )
            }
            install(SSE)

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }

            install(HttpRequestRetry) {
                maxRetries = 3
                retryOnExceptionIf { request, _ ->
                    isRetryableRequest(request.method, request.headers[HttpHeaders.Accept])
                }
                retryIf { request, response ->
                    isRetryableRequest(request.method, request.headers[HttpHeaders.Accept]) &&
                        response.status.value >= 500
                }
                exponentialDelay()
            }

            expectSuccess = true
            HttpResponseValidator {
                handleResponseExceptionWithRequest { exception, request ->
                    if (exception !is ResponseException) return@handleResponseExceptionWithRequest

                    val response = exception.response
                    if (response.status == HttpStatusCode.Unauthorized && isSessionExpiry(request)) {
                        AuthEventManager.emit(AuthEvent.Unauthorized)
                    }

                    val body = runCatching { response.bodyAsText() }.getOrNull()
                    val apiError =
                        body?.let { runCatching { errorJson.decodeFromString<ApiErrorResponse>(it) }.getOrNull() }

                    throw AppHttpException(
                        status = response.status.value,
                        errorMessage = apiError?.error,
                        errorCode = apiError?.code,
                    )
                }
            }
        }

    private fun isSessionExpiry(request: HttpRequest): Boolean {
        val carriesToken = request.headers[HttpHeaders.Authorization] != null
        val isAuthEndpoint = request.url.encodedPath.contains("/auth/")
        return carriesToken && !isAuthEndpoint
    }

    private fun isRetryableRequest(
        method: HttpMethod,
        acceptHeader: String?,
    ): Boolean = method.value == "GET" && !acceptHeader.isEventStream()

    private fun String?.isEventStream(): Boolean = this?.contains("text/event-stream", ignoreCase = true) == true

    val authApiService: AuthApiService = AuthApiService(client, BASE_URL)
    val componentApiService: ComponentApiService = ComponentApiService(client, BASE_URL)
    val requestApiService: RequestApiService = RequestApiService(client, BASE_URL)
    val notificationApiService: NotificationApiService = NotificationApiService(client, BASE_URL)
    val auditLogApiService: AuditLogApiService = AuditLogApiService(client, BASE_URL)
    val userApiService: UserApiService = UserApiService(client, BASE_URL)
    val versionApiService: VersionApiService = VersionApiService(client, BASE_URL)
    val paymentApi: PaymentApi = PaymentApi(client, BASE_URL)

    /** Scheme and host of the server this client talks to, without the `/api` prefix. */
    val serverOrigin: String = BASE_URL.removeSuffix("/api")

    /**
     * Full base URL including the `/api` segment where production terminates
     * it (Caddy strips `/api` before forwarding). In dev this is bare
     * `http://localhost:4000`, so server-relative paths from the backend must
     * be joined against *this*, not [serverOrigin].
     */
    val baseUrl: String = BASE_URL
}
