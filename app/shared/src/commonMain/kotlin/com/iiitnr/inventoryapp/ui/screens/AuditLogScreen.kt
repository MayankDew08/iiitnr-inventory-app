package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iiitnr.inventoryapp.data.models.AuditLogEntry
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.EmptyState
import com.iiitnr.inventoryapp.ui.components.common.ErrorContent
import com.iiitnr.inventoryapp.ui.components.common.LoadingIndicator
import com.iiitnr.inventoryapp.ui.components.common.NarrowContentWidth
import com.iiitnr.inventoryapp.ui.components.common.PaginationBar
import com.iiitnr.inventoryapp.ui.components.common.ResponsiveContent
import com.iiitnr.inventoryapp.ui.components.common.ScreenWidthClass
import com.iiitnr.inventoryapp.ui.components.common.WideContentWidth
import com.iiitnr.inventoryapp.ui.components.common.auditActionColor
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import com.iiitnr.inventoryapp.ui.theme.LocalIsDarkTheme
import org.koin.compose.viewmodel.koinViewModel

private val actionLabels =
    mapOf(
        "CREATE" to "Create",
        "UPDATE" to "Update",
        "DELETE" to "Delete",
        "LOGIN" to "Login",
        "LOGOUT" to "Logout",
        "REQUEST_STATUS_CHANGE" to "Status Change",
        "INVENTORY_ADJUST" to "Inventory Adj.",
    )

private val allActions =
    listOf(
        "CREATE",
        "UPDATE",
        "DELETE",
        "LOGIN",
        "LOGOUT",
        "REQUEST_STATUS_CHANGE",
        "INVENTORY_ADJUST",
    )

private val auditActionOptions: List<String?> = listOf(null) + allActions

@Composable
fun AuditLogScreen(
    onNavigateBack: () -> Unit,
    viewModel: AuditLogViewModel = koinViewModel(),
) {
    ResponsiveContent(modifier = Modifier.fillMaxSize()) { widthClass ->
        AuditLogContent(
            onNavigateBack = onNavigateBack,
            viewModel = viewModel,
            isExpanded = widthClass == ScreenWidthClass.Expanded,
        )
    }
}

@Composable
private fun AuditLogContent(
    onNavigateBack: () -> Unit,
    viewModel: AuditLogViewModel,
    isExpanded: Boolean,
) {
    var selectedEntry by remember { mutableStateOf<AuditLogEntry?>(null) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Audit Log",
                onNavigateBack = onNavigateBack,
                maxContentWidth = if (isExpanded) WideContentWidth else NarrowContentWidth,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            AuditFilterBar(
                selectedAction = viewModel.selectedAction,
                onActionSelected = { viewModel.onActionSelected(it) },
                maxContentWidth = if (isExpanded) WideContentWidth else NarrowContentWidth,
            )

            when {
                viewModel.isLoading -> {
                    LoadingIndicator()
                }

                viewModel.errorMessage != null -> {
                    ErrorContent(
                        errorMessage = viewModel.errorMessage.orEmpty(),
                        onRetry = { viewModel.loadLogs() },
                    )
                }

                viewModel.logs.isEmpty() -> {
                    EmptyState(message = "No audit logs found")
                }

                isExpanded -> {
                    AuditLogMasterDetail(
                        logs = viewModel.logs,
                        selectedEntry = selectedEntry,
                        onEntryClick = { selectedEntry = it },
                        modifier = Modifier.weight(1f),
                    )
                    AuditLogPager(viewModel = viewModel, maxContentWidth = WideContentWidth)
                }

                else -> {
                    AuditLogList(
                        logs = viewModel.logs,
                        onEntryClick = { selectedEntry = it },
                        modifier = Modifier.weight(1f),
                    )
                    AuditLogPager(viewModel = viewModel, maxContentWidth = NarrowContentWidth)
                }
            }
        }
    }

    if (!isExpanded) {
        selectedEntry?.let { entry ->
            AuditDetailDialog(entry = entry, onDismiss = { selectedEntry = null })
        }
    }
}

/**
 * Two-pane layout for wide windows: the log list on the left, the selected
 * entry's full detail on the right instead of a modal dialog.
 */
@Composable
private fun AuditLogMasterDetail(
    logs: List<AuditLogEntry>,
    selectedEntry: AuditLogEntry?,
    onEntryClick: (AuditLogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep a valid selection whenever the page or filter changes.
    LaunchedEffect(logs) {
        if (selectedEntry == null || logs.none { it.id == selectedEntry.id }) {
            logs.firstOrNull()?.let(onEntryClick)
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier.widthIn(max = WideContentWidth).fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(modifier = Modifier.width(520.dp).fillMaxHeight()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = 12.dp,
                            top = 12.dp,
                            end = 12.dp,
                            bottom = 12.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(logs, key = { it.id }) { entry ->
                        AuditLogCard(
                            entry = entry,
                            selected = entry.id == selectedEntry?.id,
                            onClick = { onEntryClick(entry) },
                        )
                    }
                }
            }

            AuditDetailPane(
                entry = selectedEntry,
                modifier =
                    Modifier
                        .weight(1f)
                        .wrapContentHeight(Alignment.Top)
                        .padding(top = 16.dp, end = 16.dp),
            )
        }
    }
}

/** Centred, width-capped log list so cards line up with the filter bar above. */
@Composable
private fun AuditLogList(
    logs: List<AuditLogEntry>,
    onEntryClick: (AuditLogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = NarrowContentWidth).fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = 12.dp,
                    top = 12.dp,
                    end = 12.dp,
                    bottom = 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(logs, key = { it.id }) { entry ->
                AuditLogCard(
                    entry = entry,
                    onClick = { onEntryClick(entry) },
                )
            }
        }
    }
}

@Composable
private fun AuditLogPager(
    viewModel: AuditLogViewModel,
    maxContentWidth: Dp,
) {
    PaginationBar(
        currentOffset = viewModel.currentOffset,
        pageSize = viewModel.pageSize,
        totalCount = viewModel.totalCount,
        onPrevious = { viewModel.onPreviousPage() },
        onNext = { viewModel.onNextPage() },
        maxContentWidth = maxContentWidth,
    )
}

/**
 * Borderless filter pills, centred on wide windows and horizontally scrollable
 * on narrow ones - the same treatment as the Components and Requests filters.
 */
@Composable
private fun AuditFilterBar(
    selectedAction: String?,
    onActionSelected: (String?) -> Unit,
    maxContentWidth: Dp,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LazyRow(
            modifier = Modifier.widthIn(max = maxContentWidth).fillMaxWidth().padding(vertical = 2.dp),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(auditActionOptions) { action ->
                val isSelected = selectedAction == action
                val selectedColor = action?.let { auditActionColor(it) } ?: MaterialTheme.colorScheme.primary
                TextButton(
                    onClick = {
                        onActionSelected(if (isSelected) null else action)
                    },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(
                        text = action?.let { actionLabels[it] ?: it } ?: "All",
                        color =
                            if (isSelected) {
                                selectedColor
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AuditLogCard(
    entry: AuditLogEntry,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val actionColor = auditActionColor(entry.action) ?: MaterialTheme.colorScheme.primary
    val isDark = LocalIsDarkTheme.current
    // Blend the action hue onto the card surface: a raw alpha tint over a light
    // surface reads muddy, so light mode gets a much smaller fraction and no shadow.
    val cardBackground =
        lerp(
            start = MaterialTheme.colorScheme.surfaceContainerLow,
            stop = actionColor,
            fraction = if (isDark) 0.14f else 0.07f,
        )

    Card(
        modifier = Modifier.fillMaxWidth().handCursor().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 1.dp else 0.dp),
        shape = RoundedCornerShape(12.dp),
        // Selection reuses the action hue so a purple/green/red audit card keeps
        // its own color instead of getting a clashing primary border.
        border =
            if (selected) {
                BorderStroke(2.dp, actionColor)
            } else {
                BorderStroke(1.dp, actionColor.copy(alpha = 0.35f))
            },
        colors =
            CardDefaults.cardColors(
                containerColor = cardBackground,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            AuditLogCardHeader(entry = entry, actionColor = actionColor)
            Spacer(Modifier.height(8.dp))
            AuditEntryDetails(entry = entry)
        }
    }
}

@Composable
private fun AuditLogCardHeader(
    entry: AuditLogEntry,
    actionColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuditActionBadge(action = entry.action, actionColor = actionColor, fontSize = 12.sp)
        Text(
            text = formatAuditTimestamp(entry.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AuditActionBadge(
    action: String,
    actionColor: Color,
    fontSize: TextUnit,
) {
    val badgeAlpha = if (LocalIsDarkTheme.current) 0.24f else 0.14f
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = actionColor.copy(alpha = badgeAlpha),
    ) {
        Text(
            text = actionLabels[action] ?: action,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = actionColor,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Actor, email and target entity of an entry. */
@Composable
private fun AuditEntryDetails(entry: AuditLogEntry) {
    Text(
        text = entry.userName ?: entry.userEmail ?: "System",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (entry.userName != null && entry.userEmail != null) {
        Text(
            text = entry.userEmail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (entry.entityType != null) {
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                text = entry.entityType,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (entry.entityId != null) {
                Text(
                    text = " #${entry.entityId.take(8)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatAuditTimestamp(iso: String): String =
    try {
        val cleaned = iso.replace("T", " ").take(19)
        cleaned
    } catch (_: Exception) {
        iso
    }
