package com.iiitnr.inventoryapp.ui.components.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.iiitnr.inventoryapp.data.models.Component
import com.iiitnr.inventoryapp.ui.components.common.InfoChip
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import com.iiitnr.inventoryapp.ui.theme.inventoryColors

@Composable
fun ComponentCard(
    component: Component,
    isReadOnly: Boolean = false,
    cartQuantity: Int = 0,
    cartQuantities: Map<String, Int>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddToCart: (() -> Unit)? = null,
    onUpdateCartQuantity: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var enlargedImageUrl by remember { mutableStateOf<String?>(null) }

    if (enlargedImageUrl != null) {
        Dialog(
            onDismissRequest = { enlargedImageUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            AsyncImage(
                model = enlargedImageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().handCursor().clickable(onClick = { enlargedImageUrl = null }),
                contentScale = ContentScale.Fit,
            )
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                ComponentImage(
                    imageUrl = component.imageUrl,
                    category = component.category,
                    modifier = Modifier.size(64.dp),
                    onClick = component.imageUrl?.let { { enlargedImageUrl = it } },
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = component.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!component.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = component.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row {
                    if (!isReadOnly && cartQuantities.isEmpty()) {
                        IconButton(onClick = onEdit, modifier = Modifier.handCursor()) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.handCursor()) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (cartQuantity == 0) {
                        AddToRequestButton(
                            enabled = component.availableQuantity > 0,
                            onClick = { onAddToCart?.invoke() },
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onUpdateCartQuantity?.invoke(-1) },
                                modifier = Modifier.handCursor(),
                            ) {
                                Icon(
                                    Icons.Default.Remove,
                                    contentDescription = "Decrease quantity",
                                )
                            }
                            Text(
                                text = cartQuantity.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            IconButton(
                                onClick = { onUpdateCartQuantity?.invoke(1) },
                                enabled = cartQuantity < component.availableQuantity,
                                modifier = Modifier.handCursor(),
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Increase quantity",
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                val available = component.availableQuantity
                val total = component.totalQuantity
                val quantityRatio =
                    if (total > 0) {
                        (available.toFloat() / total).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                val colors = MaterialTheme.inventoryColors
                val quantityColor =
                    when {
                        available <= 0 -> colors.danger
                        available >= total -> colors.success
                        else ->
                            lerp(
                                start = colors.danger,
                                stop = colors.success,
                                fraction = quantityRatio,
                            )
                    }

                // Three fixed slots keep Quantity centred even when the
                // component has no category or location.
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    val category = component.category
                    if (!category.isNullOrBlank()) {
                        InfoChip(
                            label = "Category",
                            primary = category.replace('_', ' '),
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    InfoChip(
                        label = "Quantity",
                        primary = available.toString(),
                        secondary = total.toString(),
                        color = quantityColor,
                    )
                }

                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    val location = component.location
                    if (!location.isNullOrBlank()) {
                        InfoChip(
                            label = "Location",
                            primary = location.replace('_', ' '),
                        )
                    }
                }
            }
        }
    }
}

/** Add-to-request control with a hover hint. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToRequestButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text("Add to request") } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.handCursor(),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.PlaylistAdd,
                contentDescription = "Add to request",
                tint =
                    if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
            )
        }
    }
}

@Composable
internal fun ComponentImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    category: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    if (!imageUrl.isNullOrBlank()) {
        val sanitizedUrl = imageUrl.replace("http://", "https://")
        val imageModifier =
            if (onClick != null) {
                modifier.clip(shape).handCursor().clickable(onClick = onClick)
            } else {
                modifier.clip(shape)
            }
        SubcomposeAsyncImage(
            model = sanitizedUrl,
            contentDescription = "Component image",
            modifier = imageModifier,
            contentScale = ContentScale.Crop,
            loading = { ComponentImagePlaceholder(category = category) },
            error = { ComponentImagePlaceholder(category = category) },
        )
    } else {
        ComponentImagePlaceholder(
            category = category,
            modifier = modifier.clip(shape),
        )
    }
}

/**
 * Category-aware stand-in, used both for a missing URL and for a failed load so
 * a broken image never shows a broken-image glyph.
 */
@Composable
private fun ComponentImagePlaceholder(
    category: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = categoryIcon(category),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun categoryIcon(category: String?): ImageVector =
    when (category?.lowercase()) {
        "sensors" -> Icons.Default.Sensors
        "actuators" -> Icons.Default.Build
        "microcontrollers" -> Icons.Default.Memory
        "microprocessors" -> Icons.Default.SettingsInputComponent
        else -> Icons.Default.Inventory2
    }
