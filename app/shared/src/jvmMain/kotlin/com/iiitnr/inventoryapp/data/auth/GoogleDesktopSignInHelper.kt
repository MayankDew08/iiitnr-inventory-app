package com.iiitnr.inventoryapp.data.auth

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class GoogleDesktopSignInHelper(
    private val clientId: String,
    private val clientSecret: String?,
    private val redirectUri: String,
    private val scopes: List<String> = listOf("openid", "email", "profile"),
    private val httpClient: HttpClient,
) {
    private val json =
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    suspend fun signIn(): String? =
        run {
            if (clientId.isBlank() || redirectUri.isBlank()) {
                return@run null
            }

            val (server, codeDeferred) =
                try {
                    startLocalServerForCode()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    println("Failed to start local OAuth callback server: ${e.message}")
                    return@run null
                }

            try {
                buildAuthUrl().also { openInBrowser(it) }
                codeDeferred.await()?.let { code ->
                    exchangeCodeForToken(code)
                }
            } finally {
                try {
                    server.stop(0)
                } catch (_: Throwable) {
                }
            }
        }

    private suspend fun exchangeCodeForToken(code: String): String? {
        return try {
            val response: HttpResponse =
                httpClient.submitForm(
                    url = "https://oauth2.googleapis.com/token",
                    formParameters =
                        Parameters.build {
                            append("client_id", clientId)
                            append("code", code)
                            append("redirect_uri", redirectUri)
                            append("grant_type", "authorization_code")
                            clientSecret?.let { append("client_secret", it) }
                        },
                )

            if (response.status.value !in 200..299) {
                return null
            }

            json.decodeFromString<TokenResponse>(response.bodyAsText()).id_token
        } catch (_: Exception) {
            null
        }
    }

    private fun buildAuthUrl(): String {
        val encodedParams =
            listOf(
                "response_type=code",
                "client_id=${URLEncoder.encode(clientId, StandardCharsets.UTF_8)}",
                "redirect_uri=${URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)}",
                "scope=${URLEncoder.encode(scopes.joinToString(" "), StandardCharsets.UTF_8)}",
                "access_type=offline",
                "prompt=consent",
            ).joinToString("&", prefix = "?")

        return "https://accounts.google.com/o/oauth2/v2/auth$encodedParams"
    }

    private fun openInBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                println("Open this URL in your browser to continue Google Sign-In:\n$url")
            }
        } catch (e: Exception) {
            println("Failed to open browser automatically. URL: $url, error: ${e.message}")
        }
    }

    private fun startLocalServerForCode(): Pair<HttpServer, CompletableDeferred<String?>> {
        val uri = URI(redirectUri)
        require(uri.scheme == "http") {
            "Only http redirect URIs are supported for desktop sign-in"
        }

        val host =
            uri.host?.lowercase()
                ?: throw IllegalArgumentException("Redirect URI must include a host")

        require(host == "localhost" || host == "127.0.0.1") {
            "Redirect URI host must be localhost or 127.0.0.1"
        }

        val port = if (uri.port != -1) uri.port else 80
        val path = uri.path.ifBlank { "/" }

        val server = HttpServer.create(InetSocketAddress(host, port), 0)
        val codeDeferred = CompletableDeferred<String?>()

        server.createContext(path) { exchange ->
            val queryParams =
                exchange.requestURI.query?.split("&")?.associate { param ->
                    val (key, value) = param.split("=", limit = 2)
                    key to value
                } ?: emptyMap()

            codeDeferred.complete(queryParams["code"])

            val responseHtml =
                """
                <!doctype html>
                <html lang="en">
                  <body>
                      <p>Login successful. You can now close this tab and return to the app.</p>
                    <script>
                      try {
                        window.close();
                        setTimeout(() => window.close(), 250);
                      } catch (_) {}
                    </script>
                  </body>
                </html>
                """.trimIndent()

            val bytes = responseHtml.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        Thread { server.start() }.start()

        return server to codeDeferred
    }

    @kotlinx.serialization.Serializable
    private data class TokenResponse(
        val access_token: String? = null,
        val id_token: String? = null,
        val expires_in: Long? = null,
        val token_type: String? = null,
        val refresh_token: String? = null,
    )
}
