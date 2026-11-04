package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.iiitnr.inventoryapp.data.models.UserRole
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.NotificationBell
import com.iiitnr.inventoryapp.ui.screens.NotificationsViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToRequests: () -> Unit = {},
    onNavigateToAuditLog: () -> Unit = {},
    onNavigateToUserManagement: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val userData = viewModel.user
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage

    val notificationsViewModel: NotificationsViewModel = koinViewModel()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Profile",
                onNavigateBack = onNavigateBack,
                actions = {
                    NotificationBell(
                        unreadCount = notificationsViewModel.unreadCount,
                        onClick = onNavigateToNotifications,
                    )
                    TextButton(
                        onClick = {
                            viewModel.logout(onLogout)
                        },
                    ) {
                        Text("Logout")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator()
                }

                errorMessage != null -> {
                    Text(
                        text = errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                userData != null -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ProfilePicture(userData.imageUrl)
                            Spacer(modifier = Modifier.height(8.dp))

                            InfoRow("Email", userData.email)
                            InfoRow("Name", userData.name ?: "Not provided")
                            InfoRow("Role", userData.role.name)
                            userData.batch?.let { InfoRow("Batch", it) }
                            userData.branch?.let { InfoRow("Branch", it) }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onNavigateToRequests,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Requests")
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onNavigateToNotifications,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Notifications")
                    }

                    if (userData.role == UserRole.ADMIN) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onNavigateToAuditLog,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Audit Log")
                        }
                    }

                    if (userData.role == UserRole.ADMIN) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onNavigateToUserManagement,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Manage Users")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfilePicture(imageUrl: String?) {
    if (imageUrl != null) {
        val sanitizedUrl = imageUrl.replace("http://", "https://")
        AsyncImage(
            model = sanitizedUrl,
            contentDescription = "Profile picture",
            modifier =
                Modifier
                    .size(96.dp)
                    .clip(CircleShape),
        )
    } else {
        Surface(
            modifier =
                Modifier
                    .size(96.dp)
                    .clip(CircleShape),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
        )
    }
}
