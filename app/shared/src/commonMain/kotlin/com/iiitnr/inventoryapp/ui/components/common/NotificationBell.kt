package com.iiitnr.inventoryapp.ui.components.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NotificationBell(
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier.handCursor()) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = "Notifications",
            )
            if (unreadCount > 0) {
                val count = if (unreadCount > 99) "99+" else unreadCount.toString()
                Badge(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp),
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) {
                    Text(
                        text = count,
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
