package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.iiitnr.inventoryapp.data.models.User
import com.iiitnr.inventoryapp.data.models.UserRole
import com.iiitnr.inventoryapp.data.storage.ThemeSettings
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.NarrowContentWidth
import com.iiitnr.inventoryapp.ui.components.common.NotificationBell
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import com.iiitnr.inventoryapp.ui.components.common.userRoleColor
import com.iiitnr.inventoryapp.ui.theme.LocalIsDarkTheme
import com.iiitnr.inventoryapp.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToRequests: () -> Unit = {},
    onNavigateToAuditLog: () -> Unit = {},
    onNavigateToUserManagement: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    viewModel: ProfileViewModel = koinViewModel(),
    themeSettings: ThemeSettings = koinInject(),
) {
    val userData = viewModel.user
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage

    val notificationsViewModel: NotificationsViewModel = koinViewModel()

    val themeMode by themeSettings.themeMode.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Column {
                AppTopBar(
                    title = "Profile",
                    onNavigateBack = onNavigateBack,
                    maxContentWidth = NarrowContentWidth,
                    actions = {
                        ThemeModeButton(
                            themeMode = themeMode,
                            onThemeModeChange = { mode -> scope.launch { themeSettings.setThemeMode(mode) } },
                        )
                        NotificationBell(
                            unreadCount = notificationsViewModel.unreadCount,
                            onClick = onNavigateToNotifications,
                        )
                        TextButton(
                            onClick = {
                                viewModel.logout(onLogout)
                            },
                            modifier = Modifier.handCursor(),
                        ) {
                            Text("Logout")
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
    ) { paddingValues ->
        ProfileBody(
            paddingValues = paddingValues,
            isLoading = isLoading,
            errorMessage = errorMessage,
            userData = userData,
            themeMode = themeMode,
            onThemeModeChange = { mode -> scope.launch { themeSettings.setThemeMode(mode) } },
            onNavigateToRequests = onNavigateToRequests,
            onNavigateToNotifications = onNavigateToNotifications,
            onNavigateToAuditLog = onNavigateToAuditLog,
            onNavigateToUserManagement = onNavigateToUserManagement,
        )
    }
}

@Composable
private fun ProfileBody(
    paddingValues: PaddingValues,
    isLoading: Boolean,
    errorMessage: String?,
    userData: User?,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigateToRequests: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToAuditLog: () -> Unit,
    onNavigateToUserManagement: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                errorMessage != null -> {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                userData != null -> {
                    UserInfoCard(userData)
                    AppearanceCard(themeMode = themeMode, onThemeModeChange = onThemeModeChange)
                    NavigationCard(
                        userRole = userData.role,
                        onNavigateToRequests = onNavigateToRequests,
                        onNavigateToNotifications = onNavigateToNotifications,
                        onNavigateToAuditLog = onNavigateToAuditLog,
                        onNavigateToUserManagement = onNavigateToUserManagement,
                    )
                }
            }
        }
    }
}

/** Cycles SYSTEM -> LIGHT -> DARK -> SYSTEM from the top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeButton(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val nextMode =
        when (themeMode) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
    val icon =
        when (themeMode) {
            ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
            ThemeMode.LIGHT -> Icons.Outlined.LightMode
            ThemeMode.DARK -> Icons.Outlined.DarkMode
        }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip { Text("Theme: ${themeMode.label}") }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = { onThemeModeChange(nextMode) },
            modifier = Modifier.handCursor(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Theme: ${themeMode.label}. Tap to switch to ${nextMode.label}",
            )
        }
    }
}

@Composable
private fun UserInfoCard(user: User) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfilePicture(user.imageUrl, size = 72.dp)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = user.name ?: "Not provided",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    RoleBadge(user.role)
                }
                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UserAffiliationLine(branch = user.branch, batch = user.batch)
            }
        }
    }
}

/** Branch and batch on one compact line, e.g. "CSE • 2024-28". */
@Composable
private fun UserAffiliationLine(
    branch: String?,
    batch: String?,
) {
    if (branch == null && batch == null) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.School,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = listOfNotNull(branch, batch).joinToString(" • "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RoleBadge(role: UserRole) {
    val color = userRoleColor(role)
    val badgeAlpha = if (LocalIsDarkTheme.current) 0.24f else 0.14f
    Surface(
        color = color.copy(alpha = badgeAlpha),
        contentColor = color,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = role.label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** `LA` stays an acronym instead of being title-cased to "La". */
private val UserRole.label: String
    get() =
        when (this) {
            UserRole.LA -> "LA"
            else -> name.lowercase().replaceFirstChar { it.uppercase() }
        }

/** Auto | Light | Dark, bound to the same [ThemeSettings] as the top-bar button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceCard(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            // Hand-rolled instead of SingleChoiceSegmentedButtonRow: the Material
            // component draws triangular corner artefacts and an overflowing hover
            // circle under Skiko/JVM.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        if (index > 0) {
                            VerticalDivider(
                                modifier = Modifier.fillMaxHeight(),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        ThemeModeCell(
                            mode = mode,
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeModeCell(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                ).handCursor()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = mode.label,
            style = MaterialTheme.typography.labelLarge,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/** Grouped navigation rows; ADMIN-only entries stay gated. */
@Composable
private fun NavigationCard(
    userRole: UserRole,
    onNavigateToRequests: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToAuditLog: () -> Unit,
    onNavigateToUserManagement: () -> Unit,
) {
    val rows =
        buildList {
            add(NavRow("Requests", Icons.Outlined.Receipt, onNavigateToRequests))
            add(NavRow("Notifications", Icons.Outlined.Notifications, onNavigateToNotifications))
            if (userRole == UserRole.ADMIN) {
                add(NavRow("Audit Log", Icons.Outlined.History, onNavigateToAuditLog))
                add(NavRow("Manage Users", Icons.Outlined.People, onNavigateToUserManagement))
            }
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEachIndexed { index, row ->
                ListItem(
                    headlineContent = { Text(row.label) },
                    leadingContent = {
                        Icon(
                            imageVector = row.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.handCursor().clickable(onClick = row.onClick),
                )
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

private class NavRow(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
fun ProfilePicture(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    if (imageUrl != null) {
        val sanitizedUrl = imageUrl.replace("http://", "https://")
        SubcomposeAsyncImage(
            model = sanitizedUrl,
            contentDescription = "Profile picture",
            modifier =
                modifier
                    .size(size)
                    .clip(CircleShape),
            contentScale = ContentScale.Crop,
            loading = { ProfilePicturePlaceholder(size = size) },
            error = { ProfilePicturePlaceholder(size = size) },
        )
    } else {
        ProfilePicturePlaceholder(size = size, modifier = modifier)
    }
}

/**
 * Stand-in for a missing URL and for a failed load, so a broken profile picture
 * never renders the platform broken-image glyph.
 */
@Composable
private fun ProfilePicturePlaceholder(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(size / 2),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
