package com.iiitnr.inventoryapp.ui.components.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun SearchBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    placeholder: String = "Search...",
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingIcon = {
            if (searchQuery.isNotBlank()) {
                IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.handCursor()) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search",
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
    )
}
