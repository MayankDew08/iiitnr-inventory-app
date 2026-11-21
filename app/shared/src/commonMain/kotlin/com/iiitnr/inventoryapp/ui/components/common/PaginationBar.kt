package com.iiitnr.inventoryapp.ui.components.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/**
 * Pinned list footer. The divider and the [Surface] stay edge-to-edge (matching
 * the top header), while only the page controls are capped to
 * [maxContentWidth] and centred above the lists they belong to.
 */
@Composable
fun PaginationBar(
    currentOffset: Int,
    pageSize: Int,
    totalCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = Dp.Unspecified,
) {
    val currentPage = (currentOffset / pageSize) + 1
    val totalPages = ((totalCount + pageSize - 1) / pageSize).coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier =
                        Modifier
                            .then(
                                if (maxContentWidth.isSpecified) {
                                    Modifier.widthIn(max = maxContentWidth)
                                } else {
                                    Modifier
                                },
                            ).fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onPrevious,
                        enabled = currentOffset > 0,
                        modifier = Modifier.handCursor(),
                    ) {
                        Icon(Icons.Default.ChevronLeft, "Previous")
                    }
                    Text(
                        text = "Page $currentPage of $totalPages",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    IconButton(
                        onClick = onNext,
                        enabled = currentOffset + pageSize < totalCount,
                        modifier = Modifier.handCursor(),
                    ) {
                        Icon(Icons.Default.ChevronRight, "Next")
                    }
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text(
                        text = "$totalCount total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
