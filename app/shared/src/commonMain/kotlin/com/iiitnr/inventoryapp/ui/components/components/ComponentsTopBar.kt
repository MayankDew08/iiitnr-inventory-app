package com.iiitnr.inventoryapp.ui.components.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.NotificationBell
import com.iiitnr.inventoryapp.ui.components.common.handCursor

@Composable
fun ComponentsTopBar(
    onNavigateToHome: () -> Unit,
    onNavigateToRequests: () -> Unit,
    pendingRequestsCount: Int? = null,
    showExportCsv: Boolean = false,
    onExportCsv: (() -> Unit)? = null,
    showImportCsv: Boolean = false,
    onImportCsv: (() -> Unit)? = null,
    isImportingCsv: Boolean = false,
    unreadNotificationCount: Int = 0,
    onNavigateToNotifications: (() -> Unit)? = null,
    onNavigateToFines: (() -> Unit)? = null,
    maxContentWidth: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier,
) {
    AppTopBar(
        title = "Components",
        modifier = modifier,
        maxContentWidth = maxContentWidth,
        actions = {
            CsvActions(
                showImportCsv = showImportCsv,
                onImportCsv = onImportCsv,
                isImportingCsv = isImportingCsv,
                showExportCsv = showExportCsv,
                onExportCsv = onExportCsv,
            )
            RequestsAction(
                onNavigateToRequests = onNavigateToRequests,
                pendingRequestsCount = pendingRequestsCount,
            )
            onNavigateToFines?.let { nav ->
                IconButton(onClick = nav) {
                    Icon(
                        imageVector = Icons.Default.Payments,
                        contentDescription = "Fines",
                    )
                }
            }
            if (onNavigateToNotifications != null) {
                NotificationBell(
                    unreadCount = unreadNotificationCount,
                    onClick = onNavigateToNotifications,
                )
            }
            IconButton(
                onClick = onNavigateToHome,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountCircle,
                    contentDescription = "Profile",
                )
            }
        },
    )
}

/**
 * CSV utility icons, followed by a subtle divider that separates them from the
 * navigation icons so the two groups read as distinct clusters.
 */
@Composable
private fun CsvActions(
    showImportCsv: Boolean,
    onImportCsv: (() -> Unit)?,
    isImportingCsv: Boolean,
    showExportCsv: Boolean,
    onExportCsv: (() -> Unit)?,
) {
    val showImport = showImportCsv && onImportCsv != null
    val showExport = showExportCsv && onExportCsv != null

    if (showImport) {
        IconButton(onClick = onImportCsv, enabled = !isImportingCsv) {
            Icon(
                imageVector = Icons.Outlined.FileUpload,
                contentDescription = "Import CSV",
            )
        }
    }
    if (showExport) {
        IconButton(onClick = onExportCsv) {
            Icon(
                imageVector = Icons.Outlined.FileDownload,
                contentDescription = "Export CSV",
            )
        }
    }
    if (showImport || showExport) {
        VerticalDivider(
            modifier = Modifier.padding(horizontal = 8.dp).height(16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestsAction(
    onNavigateToRequests: () -> Unit,
    pendingRequestsCount: Int?,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text("Requests") } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onNavigateToRequests,
            modifier = Modifier.handCursor(),
        ) {
            Box {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Assignment,
                    contentDescription = "Requests",
                )
                PendingRequestsBadge(count = pendingRequestsCount)
            }
        }
    }
}

@Composable
private fun BoxScope.PendingRequestsBadge(count: Int?) {
    if (count == null || count <= 0) return

    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp)
                .size(if (count >= 10) 16.dp else 14.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    lineHeight = 12.sp,
                ),
            color = MaterialTheme.colorScheme.onError,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
