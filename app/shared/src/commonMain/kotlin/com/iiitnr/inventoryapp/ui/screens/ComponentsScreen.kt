package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iiitnr.inventoryapp.data.models.Component
import com.iiitnr.inventoryapp.data.models.ComponentCategory
import com.iiitnr.inventoryapp.data.models.ComponentLocation
import com.iiitnr.inventoryapp.data.models.ComponentRequest
import com.iiitnr.inventoryapp.data.models.User
import com.iiitnr.inventoryapp.data.models.UserRole
import com.iiitnr.inventoryapp.ui.components.common.SearchBar
import com.iiitnr.inventoryapp.ui.components.components.AddComponentFAB
import com.iiitnr.inventoryapp.ui.components.components.CartDialog
import com.iiitnr.inventoryapp.ui.components.components.CartFAB
import com.iiitnr.inventoryapp.ui.components.components.ComponentDialog
import com.iiitnr.inventoryapp.ui.components.components.ComponentsContent
import com.iiitnr.inventoryapp.ui.components.components.ComponentsTopBar
import com.iiitnr.inventoryapp.ui.platform.CameraCaptureContent
import com.iiitnr.inventoryapp.ui.platform.pickImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ComponentsScreen(
    onNavigateToRequests: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onExportCsv: ((String) -> Boolean)? = null,
    onImportCsv: (((String?) -> Unit) -> Unit)? = null,
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToFines: (() -> Unit)? = null,
    onNavigateToFinesAdmin: (() -> Unit)? = null,
    viewModel: ComponentsViewModel = koinViewModel(),
    notificationsViewModel: NotificationsViewModel = koinViewModel(),
) {
    var showDialog by remember { mutableStateOf(false) }
    var showCameraCapture by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isFaculty = viewModel.userRole == UserRole.FACULTY

    val canExportCsv =
        viewModel.userRole?.let { role ->
            role == UserRole.ADMIN || role == UserRole.LA || role == UserRole.FACULTY
        } ?: false

    val canImportCsv =
        viewModel.userRole?.let { role ->
            role == UserRole.ADMIN || role == UserRole.LA
        } ?: false

    fun exportComponentsCsv() {
        exportComponentsToCsv(
            components = viewModel.components,
            canExportCsv = canExportCsv,
            onExportCsv = onExportCsv,
            scope = scope,
            snackbarHostState = snackbarHostState,
        )
    }

    fun importComponentsCsv() {
        val pickCsvFile = onImportCsv ?: return
        pickCsvFile { content ->
            if (content != null && !viewModel.isImportingCsv) {
                viewModel.importComponentsFromCsv(content)
            }
        }
    }

    val isReadOnly =
        viewModel.userRole?.let { role ->
            role != UserRole.LA && role != UserRole.ADMIN
        } ?: true

    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            ComponentsTopBar(
                onNavigateToHome = onNavigateToProfile,
                onNavigateToRequests = onNavigateToRequests,
                pendingRequestsCount = if (isFaculty) viewModel.pendingRequestsCount else null,
                showExportCsv = canExportCsv && viewModel.components.isNotEmpty(),
                onExportCsv = { exportComponentsCsv() },
                showImportCsv = canImportCsv && onImportCsv != null,
                onImportCsv = { importComponentsCsv() },
                isImportingCsv = viewModel.isImportingCsv,
                unreadNotificationCount = notificationsViewModel.unreadCount,
                onNavigateToNotifications = onNavigateToNotifications,
                onNavigateToFines =
                    when (viewModel.userRole) {
                        UserRole.STUDENT -> onNavigateToFines
                        UserRole.LA, UserRole.ADMIN -> onNavigateToFinesAdmin
                        else -> null
                    },
            )
        },
        floatingActionButton = {
            ComponentsFabSection(
                hasCartItems = viewModel.cartQuantities.isNotEmpty(),
                cartItemCount = viewModel.cartQuantities.values.sum(),
                isReadOnly = isReadOnly,
                onShowCartDialog = { viewModel.showCartDialog = true },
                onAddComponent = {
                    viewModel.editingComponent = null
                    showDialog = true
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        ComponentsScreenBody(
            paddingValues = paddingValues,
            searchQuery = viewModel.searchQuery,
            onSearchQueryChange = { viewModel.searchQuery = it },
            categoryFilter = viewModel.categoryFilter,
            onCategoryFilterChange = { viewModel.categoryFilter = it },
            locationFilter = viewModel.locationFilter,
            onLocationFilterChange = { viewModel.locationFilter = it },
            isLoading = viewModel.isLoading,
            errorMessage = viewModel.errorMessage,
            components = viewModel.components,
            filteredComponents = viewModel.filteredComponents,
            isReadOnly = isReadOnly,
            cartQuantities = viewModel.cartQuantities,
            onRetry = { viewModel.loadComponents() },
            onEdit = { component ->
                viewModel.editingComponent = component
                showDialog = true
            },
            onDelete = { component ->
                viewModel.showDeleteDialog = component
            },
            onAddToCart = { component ->
                viewModel.updateCartQuantity(component, 1)
            },
            onUpdateCartQuantity = { component, delta ->
                viewModel.updateCartQuantity(component, delta)
            },
        )
    }

    ComponentsDialogs(
        showEditDialog = showDialog && !isReadOnly,
        editingComponent = viewModel.editingComponent,
        onDismissEdit = { showDialog = false },
        onSaveEdit = { request ->
            viewModel.saveComponent(request) { showDialog = false }
        },
        onPickImage = {
            scope.launch {
                val image = pickImage()
                if (image != null) {
                    val extension = image.filename.substringAfterLast('.', "")
                    viewModel.uploadImage(
                        image.bytes,
                        resolveUploadFilename(extension, viewModel.editingComponent?.id),
                    )
                }
            }
        },
        onTakePhoto = {
            showCameraCapture = true
        },
        onRemoveImage = {
            viewModel.removeImage()
        },
        showDeleteDialog = if (!isReadOnly) viewModel.showDeleteDialog else null,
        onDismissDelete = { viewModel.showDeleteDialog = null },
        onConfirmDelete = { component ->
            viewModel.deleteComponent(component)
        },
        showCartDialog = viewModel.showCartDialog,
        isUploadingImage = viewModel.isUploadingImage,
        components = viewModel.components,
        cartQuantities = viewModel.cartQuantities,
        cartError = viewModel.cartError,
        isSubmittingRequest = viewModel.isSubmittingRequest,
        facultyOptions = viewModel.facultyOptions,
        selectedFacultyId = viewModel.selectedFacultyId,
        isLoadingFaculty = viewModel.isLoadingFaculty,
        onSelectFaculty = { viewModel.selectedFacultyId = it },
        projectTitle = viewModel.projectTitle,
        onProjectTitleChange = { viewModel.projectTitle = it },
        onUpdateQuantity = { component, delta ->
            viewModel.updateCartQuantity(component, delta)
        },
        onRemoveItem = { component ->
            viewModel.removeFromCart(component.id)
        },
        onDismissCart = {
            viewModel.showCartDialog = false
            viewModel.cartError = null
        },
        onSubmitCart = { viewModel.submitRequest() },
    )

    if (showCameraCapture) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showCameraCapture = false },
            properties =
                androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                ),
        ) {
            CameraCaptureContent(
                onResult = { image ->
                    showCameraCapture = false
                    val extension = image.filename.substringAfterLast('.', "")
                    viewModel.uploadImage(
                        image.bytes,
                        resolveUploadFilename(extension, viewModel.editingComponent?.id),
                    )
                },
                onCancel = { showCameraCapture = false },
            )
        }
    }
}

private fun exportComponentsToCsv(
    components: List<Component>,
    canExportCsv: Boolean,
    onExportCsv: ((String) -> Boolean)?,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
) {
    if (!canExportCsv || onExportCsv == null || components.isEmpty()) return

    val csvContent = buildComponentsCsv(components)
    val success = onExportCsv.invoke(csvContent)

    scope.launch {
        snackbarHostState.showSnackbar(
            message =
                if (success) {
                    "Exported components.csv successfully"
                } else {
                    "Failed to export components.csv"
                },
        )
    }
}

private fun buildComponentsCsv(components: List<Component>): String {
    val csvHeader =
        "ID,Name,Description,Category,Location,Image URL,Created At,Updated At,Total Quantity,Available Quantity"
    val csvRows =
        components.map { c ->
            listOf(
                escapeCsvField(c.id),
                escapeCsvField(c.name),
                escapeCsvField(c.description),
                escapeCsvField(c.category?.replace("_", " ")),
                escapeCsvField(c.location?.replace("_", " ")),
                escapeCsvField(c.imageUrl),
                escapeCsvField(c.createdAt),
                escapeCsvField(c.updatedAt),
                c.totalQuantity.toString(),
                c.availableQuantity.toString(),
            ).joinToString(",")
        }
    return (listOf(csvHeader) + csvRows).joinToString("\n")
}

private fun escapeCsvField(value: String?): String {
    if (value.isNullOrEmpty()) return ""
    val escaped = value.replace("\"", "\"\"")
    return if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
        "\"$escaped\""
    } else {
        escaped
    }
}

private fun resolveUploadFilename(
    extension: String,
    componentId: String?,
): String =
    if (extension.isNotEmpty()) {
        "$componentId.$extension"
    } else {
        componentId ?: "unknown"
    }

@Composable
private fun ComponentsFabSection(
    hasCartItems: Boolean,
    cartItemCount: Int,
    isReadOnly: Boolean,
    onShowCartDialog: () -> Unit,
    onAddComponent: () -> Unit,
) {
    when {
        hasCartItems -> {
            CartFAB(
                itemCount = cartItemCount,
                onClick = onShowCartDialog,
            )
        }

        !isReadOnly -> {
            AddComponentFAB(onClick = onAddComponent)
        }
    }
}

@Composable
private fun ComponentsScreenBody(
    paddingValues: PaddingValues,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    categoryFilter: String?,
    onCategoryFilterChange: (String?) -> Unit,
    locationFilter: String?,
    onLocationFilterChange: (String?) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    components: List<Component>,
    filteredComponents: List<Component>,
    isReadOnly: Boolean,
    cartQuantities: Map<String, Int>,
    onRetry: () -> Unit,
    onEdit: (Component) -> Unit,
    onDelete: (Component) -> Unit,
    onAddToCart: (Component) -> Unit,
    onUpdateCartQuantity: (Component, Int) -> Unit,
) {
    Column(modifier = Modifier.padding(paddingValues)) {
        SearchBar(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            placeholder = "Search components...",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        ComponentFilterRow(
            options = listOf("All") + ComponentCategory.labels,
            selected = categoryFilter,
            onSelectedChange = onCategoryFilterChange,
        )

        ComponentFilterRow(
            options = listOf("All") + ComponentLocation.labels,
            selected = locationFilter,
            onSelectedChange = onLocationFilterChange,
        )

        ComponentsContent(
            isLoading = isLoading,
            errorMessage = errorMessage,
            components = filteredComponents,
            allComponents = components,
            searchQuery = searchQuery,
            isReadOnly = isReadOnly,
            cartQuantities = cartQuantities,
            onRetry = onRetry,
            onEdit = onEdit,
            onDelete = onDelete,
            onAddToCart = onAddToCart,
            onUpdateCartQuantity = onUpdateCartQuantity,
        )
    }
}

@Composable
private fun ComponentFilterRow(
    options: List<String>,
    selected: String?,
    onSelectedChange: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        items(options) { option ->
            val isSelected = (selected == null && option == "All") || selected.equals(option, ignoreCase = true)
            TextButton(onClick = { onSelectedChange(if (option == "All") null else option) }) {
                Text(
                    text = option,
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

@Composable
private fun ComponentsDialogs(
    showEditDialog: Boolean,
    editingComponent: Component?,
    onDismissEdit: () -> Unit,
    onSaveEdit: (ComponentRequest) -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveImage: () -> Unit,
    showDeleteDialog: Component?,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (Component) -> Unit,
    showCartDialog: Boolean,
    isUploadingImage: Boolean,
    components: List<Component>,
    cartQuantities: Map<String, Int>,
    cartError: String?,
    isSubmittingRequest: Boolean,
    facultyOptions: List<User>,
    selectedFacultyId: String?,
    isLoadingFaculty: Boolean,
    onSelectFaculty: (String?) -> Unit,
    projectTitle: String,
    onProjectTitleChange: (String) -> Unit,
    onUpdateQuantity: (Component, Int) -> Unit,
    onRemoveItem: (Component) -> Unit,
    onDismissCart: () -> Unit,
    onSubmitCart: () -> Unit,
) {
    if (showEditDialog) {
        ComponentDialog(
            component = editingComponent,
            onDismiss = onDismissEdit,
            onSave = { onSaveEdit(it) },
            onPickImage = onPickImage,
            onTakePhoto = onTakePhoto,
            onRemoveImage = onRemoveImage,
            isUploading = isUploadingImage,
        )
    }

    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Delete Component") },
            text = { Text("Are you sure you want to delete \"${showDeleteDialog.name}\"?") },
            confirmButton = {
                TextButton(onClick = { onConfirmDelete(showDeleteDialog) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text("Cancel") }
            },
        )
    }

    if (showCartDialog) {
        CartDialog(
            components = components,
            cartQuantities = cartQuantities,
            cartError = cartError,
            isSubmitting = isSubmittingRequest,
            facultyOptions = facultyOptions,
            selectedFacultyId = selectedFacultyId,
            isLoadingFaculty = isLoadingFaculty,
            onSelectFaculty = onSelectFaculty,
            projectTitle = projectTitle,
            onProjectTitleChange = onProjectTitleChange,
            onUpdateQuantity = onUpdateQuantity,
            onRemoveItem = onRemoveItem,
            onDismiss = onDismissCart,
            onSubmit = onSubmitCart,
        )
    }
}
