package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.AppNotification
import com.iiitnr.inventoryapp.data.models.NotificationType
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.EmptyState
import com.iiitnr.inventoryapp.ui.components.common.ErrorContent
import com.iiitnr.inventoryapp.ui.components.common.LoadingIndicator
import com.iiitnr.inventoryapp.ui.components.common.NarrowContentWidth
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import com.iiitnr.inventoryapp.ui.theme.LocalIsDarkTheme
import com.iiitnr.inventoryapp.utils.currentToday
import com.iiitnr.inventoryapp.utils.getRelativeDays
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun NotificationsScreen(
    onNavigateBack: () -> Unit,
    onOpenRequest: (String) -> Unit,
    viewModel: NotificationsViewModel = koinViewModel(),
) {
    val today = currentToday

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Notifications",
                onNavigateBack = onNavigateBack,
                maxContentWidth = NarrowContentWidth,
                actions = {
                    if (viewModel.unreadCount > 0) {
                        TextButton(onClick = { viewModel.markAllRead() }) {
                            Text("Mark all read")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            NotificationsBody(
                viewModel = viewModel,
                today = today,
                onOpenRequest = onOpenRequest,
            )
        }
    }
}

@Composable
private fun NotificationsBody(
    viewModel: NotificationsViewModel,
    today: LocalDate,
    onOpenRequest: (String) -> Unit,
) {
    when {
        viewModel.isLoading && viewModel.notifications.isEmpty() -> {
            LoadingIndicator(modifier = Modifier.fillMaxSize())
        }

        viewModel.errorMessage != null && viewModel.notifications.isEmpty() -> {
            ErrorContent(
                errorMessage = viewModel.errorMessage ?: "Something went wrong",
                onRetry = { viewModel.loadNotifications() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        viewModel.notifications.isEmpty() -> {
            EmptyState(
                message = "No notifications yet",
                subtitle = "You'll see updates here when your requests change status",
                modifier = Modifier.fillMaxSize(),
            )
        }

        else -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = NarrowContentWidth).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(viewModel.notifications, key = { it.id }) { notification ->
                        NotificationCard(
                            notification = notification,
                            today = today,
                            onClick = {
                                viewModel.markRead(notification.id)
                                // Unknown types are forward-compat stubs from a
                                // newer backend: mark read but do not navigate.
                                if (notification.type != NotificationType.UNKNOWN) {
                                    notification.requestId?.let(onOpenRequest)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    notification: AppNotification,
    today: LocalDate,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().handCursor(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = notificationCardColor(read = notification.read),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = if (LocalIsDarkTheme.current) 1.dp else 0.dp,
            ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        NotificationCardContent(notification = notification, today = today)
    }
}

/**
 * Unread cards get a light primary wash so they stand out from read cards, which
 * sit on the plain surface used by the other list cards in the app.
 */
@Composable
private fun notificationCardColor(read: Boolean): Color {
    if (read) return MaterialTheme.colorScheme.surfaceContainerLow

    val fraction = if (LocalIsDarkTheme.current) 0.16f else 0.08f
    return lerp(
        start = MaterialTheme.colorScheme.surfaceContainerLow,
        stop = MaterialTheme.colorScheme.primary,
        fraction = fraction,
    )
}

@Composable
private fun NotificationCardContent(
    notification: AppNotification,
    today: LocalDate,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(
                    if (notification.read) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = notification.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (notification.read) FontWeight.Normal else FontWeight.SemiBold,
            )
            notification.body?.let { body ->
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NotificationMeta(createdAt = notification.createdAt, today = today)
        }
    }
}

@Composable
private fun NotificationMeta(
    createdAt: String,
    today: LocalDate,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = getRelativeDays(createdAt, today),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
