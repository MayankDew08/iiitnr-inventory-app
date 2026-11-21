package com.iiitnr.inventoryapp.ui.components.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.Component
import com.iiitnr.inventoryapp.ui.components.common.ScreenWidthClass
import com.iiitnr.inventoryapp.ui.components.common.WideContentWidth
import com.iiitnr.inventoryapp.ui.platform.VerticalScrollbarOrEmpty

// Keeps the last row clear of the Add / Cart FAB.
private val ContentPadding =
    PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = 16.dp,
        bottom = 88.dp,
    )

@Composable
fun ComponentsList(
    components: List<Component>,
    isReadOnly: Boolean,
    cartQuantities: Map<String, Int>,
    onEdit: (Component) -> Unit,
    onDelete: (Component) -> Unit,
    onAddToCart: (Component) -> Unit,
    onUpdateCartQuantity: (Component, Int) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: ScreenWidthClass = ScreenWidthClass.Compact,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (widthClass == ScreenWidthClass.Compact) {
            ComponentsColumn(
                components = components,
                isReadOnly = isReadOnly,
                cartQuantities = cartQuantities,
                onEdit = onEdit,
                onDelete = onDelete,
                onAddToCart = onAddToCart,
                onUpdateCartQuantity = onUpdateCartQuantity,
            )
        } else {
            ComponentsGrid(
                components = components,
                isReadOnly = isReadOnly,
                cartQuantities = cartQuantities,
                onEdit = onEdit,
                onDelete = onDelete,
                onAddToCart = onAddToCart,
                onUpdateCartQuantity = onUpdateCartQuantity,
            )
        }
    }
}

@Composable
private fun ComponentsColumn(
    components: List<Component>,
    isReadOnly: Boolean,
    cartQuantities: Map<String, Int>,
    onEdit: (Component) -> Unit,
    onDelete: (Component) -> Unit,
    onAddToCart: (Component) -> Unit,
    onUpdateCartQuantity: (Component, Int) -> Unit,
) {
    val state = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = ContentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(components) { component ->
                ComponentCard(
                    component = component,
                    isReadOnly = isReadOnly,
                    cartQuantity = cartQuantities[component.id] ?: 0,
                    cartQuantities = cartQuantities,
                    onEdit = { onEdit(component) },
                    onDelete = { onDelete(component) },
                    onAddToCart = { onAddToCart(component) },
                    onUpdateCartQuantity = { delta -> onUpdateCartQuantity(component, delta) },
                )
            }
        }
        VerticalScrollbarOrEmpty(
            state = state,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun ComponentsGrid(
    components: List<Component>,
    isReadOnly: Boolean,
    cartQuantities: Map<String, Int>,
    onEdit: (Component) -> Unit,
    onDelete: (Component) -> Unit,
    onAddToCart: (Component) -> Unit,
    onUpdateCartQuantity: (Component, Int) -> Unit,
) {
    val state = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 340.dp),
        state = state,
        modifier = Modifier.widthIn(max = WideContentWidth).fillMaxHeight(),
        contentPadding = ContentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(components, key = { it.id }) { component ->
            ComponentCard(
                component = component,
                isReadOnly = isReadOnly,
                cartQuantity = cartQuantities[component.id] ?: 0,
                cartQuantities = cartQuantities,
                onEdit = { onEdit(component) },
                onDelete = { onDelete(component) },
                onAddToCart = { onAddToCart(component) },
                onUpdateCartQuantity = { delta -> onUpdateCartQuantity(component, delta) },
            )
        }
    }
}
