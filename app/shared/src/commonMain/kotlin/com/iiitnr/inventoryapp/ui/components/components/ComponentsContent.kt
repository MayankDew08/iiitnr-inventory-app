package com.iiitnr.inventoryapp.ui.components.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.iiitnr.inventoryapp.data.models.Component
import com.iiitnr.inventoryapp.ui.components.common.EmptyState
import com.iiitnr.inventoryapp.ui.components.common.ErrorContent
import com.iiitnr.inventoryapp.ui.components.common.LoadingIndicator
import com.iiitnr.inventoryapp.ui.components.common.ScreenWidthClass

@Composable
fun ComponentsContent(
    isLoading: Boolean,
    errorMessage: String?,
    components: List<Component>,
    allComponents: List<Component>,
    searchQuery: String,
    isReadOnly: Boolean,
    cartQuantities: Map<String, Int>,
    onRetry: () -> Unit,
    onEdit: (Component) -> Unit,
    onDelete: (Component) -> Unit,
    onAddToCart: (Component) -> Unit,
    onUpdateCartQuantity: (Component, Int) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: ScreenWidthClass = ScreenWidthClass.Compact,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> LoadingIndicator()
            errorMessage != null -> ErrorContent(errorMessage, onRetry)
            components.isEmpty() && allComponents.isEmpty() ->
                EmptyState(
                    message = "No components found",
                    subtitle = if (!isReadOnly) "Tap the + button to add a component" else null,
                )

            components.isEmpty() && searchQuery.isNotBlank() ->
                EmptyState(
                    message = "No components match your search",
                )

            else ->
                ComponentsList(
                    components = components,
                    isReadOnly = isReadOnly,
                    cartQuantities = cartQuantities,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onAddToCart = onAddToCart,
                    onUpdateCartQuantity = onUpdateCartQuantity,
                    widthClass = widthClass,
                )
        }
    }
}
