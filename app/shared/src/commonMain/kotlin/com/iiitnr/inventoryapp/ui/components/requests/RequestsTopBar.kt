package com.iiitnr.inventoryapp.ui.components.requests

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.NotificationBell
import com.iiitnr.inventoryapp.ui.components.common.handCursor

@Composable
fun RequestsTopBar(
    onNavigateBack: () -> Unit,
    onScanRequestClick: (() -> Unit)? = null,
    showExportCsv: Boolean = false,
    onExportCsv: (() -> Unit)? = null,
    onNavigateToNotifications: (() -> Unit)? = null,
    unreadNotificationCount: Int = 0,
    maxContentWidth: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier,
) {
    AppTopBar(
        title = "Requests",
        modifier = modifier,
        onNavigateBack = onNavigateBack,
        maxContentWidth = maxContentWidth,
        actions = {
            if (showExportCsv && onExportCsv != null) {
                IconButton(onClick = onExportCsv, modifier = Modifier.handCursor()) {
                    Icon(
                        imageVector = Icons.Outlined.FileDownload,
                        contentDescription = "Export requests to CSV",
                    )
                }
            }
            if (onNavigateToNotifications != null) {
                NotificationBell(
                    unreadCount = unreadNotificationCount,
                    onClick = onNavigateToNotifications,
                )
            }
            if (onScanRequestClick != null) {
                IconButton(onClick = onScanRequestClick, modifier = Modifier.handCursor()) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "Scan request QR or enter request ID",
                    )
                }
            }
        },
    )
}
