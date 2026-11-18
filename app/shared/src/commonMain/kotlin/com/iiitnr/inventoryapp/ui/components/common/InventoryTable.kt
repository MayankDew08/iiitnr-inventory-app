package com.iiitnr.inventoryapp.ui.components.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class TableColumn<T>(
    val header: String,
    val weight: Float = 1f,
    val alignment: Alignment = Alignment.CenterStart,
    /**
     * Space reserved after a trailing-aligned header, so it can sit above a value
     * column that has a trailing action button.
     */
    val headerTrailingSpace: Dp = 0.dp,
    val content: @Composable (T) -> Unit,
)

@Composable
fun <T> InventoryTable(
    items: List<T>,
    columns: List<TableColumn<T>>,
    modifier: Modifier = Modifier,
    useCard: Boolean = true,
) {
    if (useCard) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ),
            shape = RoundedCornerShape(8.dp),
        ) {
            TableContent(items, columns)
        }
    } else {
        TableContent(items, columns, modifier)
    }
}

@Composable
private fun <T> TableContent(
    items: List<T>,
    columns: List<TableColumn<T>>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEach { column ->
                Box(
                    modifier = Modifier.weight(column.weight),
                    contentAlignment = column.alignment,
                ) {
                    Text(
                        text = column.header.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = column.headerTrailingSpace),
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                columns.forEach { column ->
                    Box(
                        modifier = Modifier.weight(column.weight),
                        contentAlignment = column.alignment,
                    ) {
                        column.content(item)
                    }
                }
            }
            if (index < items.size - 1) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            }
        }
    }
}
