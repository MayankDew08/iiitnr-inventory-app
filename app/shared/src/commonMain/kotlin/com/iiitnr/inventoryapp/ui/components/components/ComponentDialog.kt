package com.iiitnr.inventoryapp.ui.components.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.Component
import com.iiitnr.inventoryapp.data.models.ComponentCategory
import com.iiitnr.inventoryapp.data.models.ComponentLocation
import com.iiitnr.inventoryapp.data.models.ComponentRequest
import com.iiitnr.inventoryapp.ui.components.common.handCursor

@Composable
fun ComponentDialog(
    component: Component?,
    onDismiss: () -> Unit,
    onSave: (ComponentRequest) -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveImage: () -> Unit,
    isUploading: Boolean = false,
) {
    var name by remember { mutableStateOf(component?.name.orEmpty()) }
    var description by remember { mutableStateOf(component?.description.orEmpty()) }
    var imageUrl by remember { mutableStateOf(component?.imageUrl.orEmpty()) }
    var totalQuantity by remember { mutableStateOf(component?.totalQuantity?.toString() ?: "0") }
    var availableQuantity by remember {
        mutableStateOf(component?.availableQuantity?.toString().orEmpty())
    }
    var category by remember { mutableStateOf(component?.category.orEmpty()) }
    var location by remember { mutableStateOf(component?.location?.replace('_', ' ').orEmpty()) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(component?.imageUrl) {
        imageUrl = component?.imageUrl.orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (component != null) "Edit Component" else "Add Component") },
        text = {
            ComponentDialogFields(
                name = name,
                description = description,
                imageUrl = imageUrl,
                totalQuantity = totalQuantity,
                availableQuantity = availableQuantity,
                category = category,
                location = location,
                onNameChange = { name = it },
                onDescriptionChange = { description = it },
                onImageUrlChange = { imageUrl = it },
                onTotalQuantityChange = { input ->
                    if (input.all(Char::isDigit)) {
                        totalQuantity = input
                        val total = input.toIntOrNull()
                        val available = availableQuantity.toIntOrNull()
                        if (total != null && available != null && available > total) {
                            availableQuantity = total.toString()
                        }
                    }
                },
                onAvailableQuantityChange = { input ->
                    if (input.all(Char::isDigit)) {
                        availableQuantity = input
                        val total = totalQuantity.toIntOrNull()
                        val available = input.toIntOrNull()
                        if (total != null && available != null && available > total) {
                            totalQuantity = available.toString()
                        }
                    }
                },
                onCategoryChange = { category = it },
                onLocationChange = { location = it },
                showImageActions = component != null,
                onPickImage = onPickImage,
                onTakePhoto = onTakePhoto,
                onRemoveImage = {
                    imageUrl = ""
                    onRemoveImage()
                },
                isUploading = isUploading,
                modifier =
                    Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    isSaving = true
                    onSave(
                        ComponentRequest(
                            name = name.trim(),
                            description = description.trim().takeIf { it.isNotBlank() },
                            imageUrl = imageUrl.trim().takeIf { it.isNotBlank() },
                            totalQuantity = totalQuantity.toIntOrNull() ?: 0,
                            availableQuantity = availableQuantity.toIntOrNull() ?: totalQuantity.toIntOrNull(),
                            category = category.trim().takeIf { it.isNotBlank() },
                            location = location.trim().takeIf { it.isNotBlank() },
                        ),
                    )
                    isSaving = false
                },
                enabled = !isSaving && !isUploading && name.isNotBlank(),
            ) {
                if (isSaving || isUploading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving && !isUploading) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ComponentDialogFields(
    name: String,
    description: String,
    imageUrl: String,
    totalQuantity: String,
    availableQuantity: String,
    category: String,
    location: String,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onImageUrlChange: (String) -> Unit,
    onTotalQuantityChange: (String) -> Unit,
    onAvailableQuantityChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onLocationChange: (String) -> Unit,
    showImageActions: Boolean,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveImage: () -> Unit,
    isUploading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val categoryOptions = ComponentCategory.labels
        val locationOptions = ComponentLocation.labels

        var isCategoryExpanded by remember { mutableStateOf(false) }
        var isLocationExpanded by remember { mutableStateOf(false) }

        var quantityToAdd by remember { mutableStateOf("") }

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Name *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )

        OutlinedTextField(
            value = imageUrl,
            onValueChange = onImageUrlChange,
            label = { Text("Image URL (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        if (showImageActions) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onPickImage,
                    modifier = Modifier.handCursor(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    enabled = !isUploading,
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Gallery", maxLines = 1)
                }
                TextButton(
                    onClick = onTakePhoto,
                    modifier = Modifier.handCursor(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    enabled = !isUploading,
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Camera", maxLines = 1)
                }
                if (imageUrl.isNotBlank()) {
                    IconButton(
                        onClick = onRemoveImage,
                        enabled = !isUploading,
                        modifier = Modifier.handCursor(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove Image",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = availableQuantity,
                onValueChange = onAvailableQuantityChange,
                label = { Text("AVL") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            OutlinedTextField(
                value = totalQuantity,
                onValueChange = onTotalQuantityChange,
                label = { Text("Total") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            OutlinedTextField(
                value = quantityToAdd,
                onValueChange = { input ->
                    if (input.all(Char::isDigit)) {
                        quantityToAdd = input
                    }
                },
                label = { Text("Add") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            FilledIconButton(
                onClick = {
                    val currentAvailable = availableQuantity.toIntOrNull() ?: 0
                    val currentTotal = totalQuantity.toIntOrNull() ?: 0
                    val quantityToAddInt = quantityToAdd.toIntOrNull() ?: 0

                    val newAvailable = (currentAvailable + quantityToAddInt).toString()
                    val newTotal = (currentTotal + quantityToAddInt).toString()

                    quantityToAdd = ""
                    onAvailableQuantityChange(newAvailable)
                    onTotalQuantityChange(newTotal)
                },
                enabled = quantityToAdd.isNotBlank(),
                modifier = Modifier.align(Alignment.CenterVertically).handCursor(),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Quantity",
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryDropdownField(
                modifier = Modifier.weight(1f),
                value = category,
                options = categoryOptions,
                expanded = isCategoryExpanded,
                onExpandedChange = { isCategoryExpanded = it },
                onSelect = onCategoryChange,
            )

            LocationDropdownField(
                modifier = Modifier.weight(1f),
                value = location,
                options = locationOptions,
                expanded = isLocationExpanded,
                onExpandedChange = { isLocationExpanded = it },
                onSelect = onLocationChange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdownField(
    modifier: Modifier = Modifier,
    value: String,
    options: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    ExposedDropdownMenuBox(
        modifier = modifier,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text("Category") },
            modifier =
                Modifier.fillMaxWidth().menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = true,
                ),
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.exposedDropdownSize(matchAnchorWidth = true),
        ) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    onSelect(option)
                    onExpandedChange(false)
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationDropdownField(
    modifier: Modifier = Modifier,
    value: String,
    options: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    ExposedDropdownMenuBox(
        modifier = modifier,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text("Location") },
            modifier =
                Modifier.fillMaxWidth().menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = true,
                ),
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.exposedDropdownSize(matchAnchorWidth = true),
        ) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    onSelect(option)
                    onExpandedChange(false)
                })
            }
        }
    }
}
