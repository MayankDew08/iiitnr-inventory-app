package com.iiitnr.inventoryapp.ui.components.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Proofs are stored as server-relative paths (`/api/payments/{id}/proof`).
 * Prefix the server this client is connected to. Older rows hold absolute
 * URLs, which are returned unchanged.
 */
fun resolveProofUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    return if (url.startsWith("/")) ApiClient.serverOrigin + url else url.replace("http://", "https://")
}

/**
 * Proof images are served by the authenticated `/payments/{id}/proof` route,
 * so the download carries the session token. [url] must already be absolute
 * (see [resolveProofUrl]).
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
                            if (token != null) add(HttpHeaders.Authorization, "Bearer $token")
                        }.build(),
                ).build()
        }
    }
}

/**
 * View-only payment proof: a thumbnail that opens the full image on tap. There
 * is deliberately no replace affordance — a wrong screenshot is fixed by the
 * LA rejecting the payment and the student submitting again.
 */
@Composable
fun ProofThumbnail(
    url: String?,
    token: String?,
    modifier: Modifier = Modifier,
    thumbnailHeight: Dp = 160.dp,
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
                modifier = Modifier.fillMaxSize().clickable { enlarged = false },
                contentScale = ContentScale.Fit,
                loading = { ProofLoading() },
                error = { ProofError() },
            )
        }
    }

    if (request == null) {
        return
    }

    SubcomposeAsyncImage(
        model = request,
        contentDescription = "Payment proof",
        modifier =
            modifier
                .fillMaxWidth()
                .height(thumbnailHeight)
                .clip(RoundedCornerShape(8.dp))
                .clickable { enlarged = true },
        contentScale = ContentScale.Crop,
        loading = { ProofLoading() },
        error = { ProofError() },
    )
}

@Composable
private fun ProofLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ProofError() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Could not load proof")
    }
}
