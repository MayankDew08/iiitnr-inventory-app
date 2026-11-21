package com.iiitnr.inventoryapp.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.iiitnr.inventoryapp.data.api.ApiClient
import io.ktor.http.HttpHeaders

/**
 * Proofs are returned as API-relative paths (`/payments/{id}/proof`). Join
 * them with the client's full base URL — in dev that is `http://localhost:4000`
 * (no `/api` prefix), in production it is `https://.../api` and Caddy strips
 * `/api` before forwarding. Older rows hold absolute URLs, returned unchanged.
 */
fun resolveProofUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    return if (url.startsWith("/")) ApiClient.baseUrl + url else url
}

/**
 * Proof images are served by the authenticated `/payments/{id}/proof` route,
 * so the download carries the session token. [url] must already be absolute
 * (see [resolveProofUrl]).
 *
 * The token is only attached when the URL points at this app's own backend: a
 * proof path that resolved to a foreign origin must never receive the session.
 */
@Composable
fun rememberAuthedImageRequest(
    url: String?,
    token: String?,
): ImageRequest? {
    val context = LocalPlatformContext.current
    return remember(context, url, token) {
        url?.let {
            ImageRequest
                .Builder(context)
                .data(it)
                .httpHeaders(
                    NetworkHeaders
                        .Builder()
                        .apply {
                            if (token != null && isOwnBackendUrl(it)) {
                                add(HttpHeaders.Authorization, "Bearer $token")
                            }
                        }.build(),
                ).build()
        }
    }
}

/** True when [url] is served by this app's backend (relative paths included). */
internal fun isOwnBackendUrl(url: String): Boolean =
    url.startsWith(ApiClient.baseUrl) || url.startsWith(ApiClient.serverOrigin)

/**
 * View-only payment proof: a flat framed card that fits the receipt without
 * cropping, and opens a full-screen lightbox on tap. There is deliberately no
 * replace affordance — a wrong screenshot is fixed by the LA rejecting the
 * payment and the student submitting again.
 *
 * Receipt screenshots are tall and narrow; `ContentScale.Fit` plus a
 * `heightIn(min = 200.dp, max = 420.dp)` keeps them legible without stretching
 * the card to half the screen.
 */
@Composable
fun ProofThumbnail(
    url: String?,
    token: String?,
    modifier: Modifier = Modifier,
    minHeight: Dp = 200.dp,
    maxHeight: Dp = 420.dp,
) {
    val request = rememberAuthedImageRequest(resolveProofUrl(url), token)
    var enlarged by remember { mutableStateOf(false) }

    if (enlarged && request != null) {
        Dialog(
            onDismissRequest = { enlarged = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = "Payment proof (enlarged)",
                modifier =
                    Modifier
                        .fillMaxSize()
                        .handCursor()
                        .clickable { enlarged = false },
                contentScale = ContentScale.Fit,
                loading = { ProofLoading() },
                error = { ProofError() },
            )
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (request == null) {
            ProofPlaceholder(label = "No proof uploaded", minHeight = minHeight)
            return@Card
        }
        SubcomposeAsyncImage(
            model = request,
            contentDescription = "Payment proof",
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight, max = maxHeight)
                    .handCursor()
                    .clickable { enlarged = true },
            contentScale = ContentScale.Fit,
            loading = { ProofLoading() },
            error = { ProofError() },
        )
    }
}

@Composable
private fun ProofLoading() {
    Box(
        Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ProofError() {
    Box(
        Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = "Could not load proof",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProofPlaceholder(
    label: String,
    minHeight: Dp,
) {
    Box(
        Modifier.fillMaxWidth().height(minHeight),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
