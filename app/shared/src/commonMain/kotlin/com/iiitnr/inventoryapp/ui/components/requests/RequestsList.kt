package com.iiitnr.inventoryapp.ui.components.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.Request
import com.iiitnr.inventoryapp.ui.platform.VerticalScrollbarOrEmpty

@Composable
fun RequestsList(
    requests: List<Request>,
    onDeleteRequest: ((String) -> Unit)? = null,
    onApproveRequest: ((String) -> Unit)? = null,
    onRejectRequest: ((String) -> Unit)? = null,
    onFulfillRequest: ((String) -> Unit)? = null,
    onReturnRequest: ((String) -> Unit)? = null,
    onRequestRenew: ((String) -> Unit)? = null,
    onApproveRenew: ((String) -> Unit)? = null,
    onShowQr: ((Request) -> Unit)? = null,
    onCardClick: (Request) -> Unit,
    isFaculty: Boolean = false,
    modifier: Modifier = Modifier,
    selectedRequestId: String? = null,
) {
    val state = rememberLazyListState()
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.padding(end = 12.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(requests) { request ->
                RequestCard(
                    request = request,
                    onDeleteRequest = onDeleteRequest,
                    onApproveRequest = onApproveRequest,
                    onRejectRequest = onRejectRequest,
                    onFulfillRequest = onFulfillRequest,
                    onReturnRequest = onReturnRequest,
                    onRequestRenew = onRequestRenew,
                    onApproveRenew = onApproveRenew,
                    onShowQr = onShowQr,
                    onCardClick = onCardClick,
                    isFaculty = isFaculty,
                    isSelected = request.id == selectedRequestId,
                )
            }
        }
        VerticalScrollbarOrEmpty(
            state = state,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}
