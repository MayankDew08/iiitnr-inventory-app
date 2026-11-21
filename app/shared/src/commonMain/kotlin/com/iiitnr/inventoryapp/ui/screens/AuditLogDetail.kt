package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iiitnr.inventoryapp.data.models.AuditLogEntry
import com.iiitnr.inventoryapp.ui.components.common.InventoryTable
import com.iiitnr.inventoryapp.ui.components.common.TableColumn
import com.iiitnr.inventoryapp.ui.components.common.auditActionColor
import com.iiitnr.inventoryapp.ui.theme.SemanticDanger
import com.iiitnr.inventoryapp.ui.theme.SemanticSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val jsonParser = Json { ignoreUnknownKeys = true }

/** Modal detail used on narrow windows. */
@Composable
internal fun AuditDetailDialog(
    entry: AuditLogEntry,
    onDismiss: () -> Unit,
) {
    val actionColor = auditActionColor(entry.action) ?: MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuditActionBadge(action = entry.action, actionColor = actionColor, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Audit Detail",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        text = { AuditDetailBody(entry = entry) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * Inline detail used by the two-pane (Expanded) Audit Log layout, so selecting
 * a row updates the pane instead of opening a modal.
 */
@Composable
internal fun AuditDetailPane(
    entry: AuditLogEntry?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (entry == null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Select a log entry to view details",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AuditDetailPaneContent(entry = entry)
        }
    }
}

@Composable
private fun AuditDetailPaneContent(entry: AuditLogEntry) {
    val actionColor = auditActionColor(entry.action) ?: MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuditActionBadge(action = entry.action, actionColor = actionColor, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Audit Detail",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))
        AuditDetailBody(entry = entry)
    }
}

@Composable
private fun AuditDetailBody(entry: AuditLogEntry) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DetailRow("Actor", entry.userName ?: entry.userEmail ?: "System")
        DetailRow("Email", entry.userEmail ?: "Not recorded")
        DetailRow("Timestamp", entry.createdAt)
        entry.entityType?.let { DetailRow("Entity Type", it) }
        entry.entityId?.let { DetailRow("Entity ID", it) }
        entry.ipAddress?.let { DetailRow("IP Address", it) }
        entry.userAgent?.let { DetailRow("User Agent", it) }

        if (entry.oldValues != null || entry.newValues != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "Changes",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            DiffViewer(
                oldJson = entry.oldValues,
                newJson = entry.newValues,
            )
        }

        entry.metadata?.let { metadata ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            MetadataBlock(metadata = metadata)
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Pretty-prints the metadata JSON as `key: value` rows when it parses. */
@Composable
private fun MetadataBlock(metadata: String) {
    Column {
        Text(
            text = "Metadata",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        val fields = parseJsonMap(metadata)
        if (fields.isEmpty()) {
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                fields.forEach { (key, value) ->
                    Row {
                        Text(
                            text = "$key: ",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffViewer(
    oldJson: String?,
    newJson: String?,
) {
    val oldMap = parseJsonMap(oldJson)
    val newMap = parseJsonMap(newJson)
    val allKeys = (oldMap.keys + newMap.keys).distinct().sorted()
    val changedKeys = allKeys.filter { oldMap[it] != newMap[it] }

    when {
        allKeys.isEmpty() -> DiffNote("No structured changes available")

        changedKeys.isEmpty() -> DiffNote("No values were changed")

        else -> {
            DiffTable(
                oldMap = oldMap,
                newMap = newMap,
                changedKeys = changedKeys,
            )
            DiffUnchangedCount(
                unchangedCount =
                    allKeys.count {
                        oldMap[it] == newMap[it] && oldMap.containsKey(it) && newMap.containsKey(it)
                    },
            )
        }
    }
}

@Composable
private fun DiffNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DiffTable(
    oldMap: Map<String, String>,
    newMap: Map<String, String>,
    changedKeys: List<String>,
) {
    InventoryTable(
        items = changedKeys,
        columns =
            listOf(
                TableColumn(
                    header = "Field",
                    weight = 0.35f,
                    content = { key -> DiffFieldName(key = key) },
                ),
                TableColumn(
                    header = "Changes",
                    weight = 0.65f,
                    content = { key -> DiffFieldChange(oldValue = oldMap[key], newValue = newMap[key]) },
                ),
            ),
    )
}

@Composable
private fun DiffFieldName(key: String) {
    Text(
        text = key,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun DiffFieldChange(
    oldValue: String?,
    newValue: String?,
) {
    Column {
        oldValue?.let {
            Text(
                text = "- $it",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = SemanticDanger,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
        newValue?.let {
            Text(
                text = "+ $it",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = SemanticSuccess,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DiffUnchangedCount(unchangedCount: Int) {
    if (unchangedCount <= 0) return

    Text(
        text = "$unchangedCount unchanged field(s)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

private fun parseJsonMap(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val obj = jsonParser.decodeFromString<JsonObject>(json)
        obj.mapValues { (_, v) -> v.jsonPrimitive.content }
    } catch (_: Exception) {
        emptyMap()
    }
}
