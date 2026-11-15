package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iiitnr.inventoryapp.data.api.AuthApiService
import com.iiitnr.inventoryapp.data.api.ComponentApiService
import com.iiitnr.inventoryapp.data.api.RequestApiService
import com.iiitnr.inventoryapp.data.cache.ComponentsCache
import com.iiitnr.inventoryapp.data.cache.DraftCache
import com.iiitnr.inventoryapp.data.models.AppError
import com.iiitnr.inventoryapp.data.models.Component
import com.iiitnr.inventoryapp.data.models.ComponentRequest
import com.iiitnr.inventoryapp.data.models.CreateRequestPayload
import com.iiitnr.inventoryapp.data.models.RequestItemPayload
import com.iiitnr.inventoryapp.data.models.User
import com.iiitnr.inventoryapp.data.models.UserRole
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.utils.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class ComponentsViewModel(
    val tokenManager: TokenManager,
    private val authApiService: AuthApiService,
    private val componentApiService: ComponentApiService,
    private val requestApiService: RequestApiService,
    private val componentsCache: ComponentsCache? = null,
    private val draftCache: DraftCache? = null,
) : ViewModel() {
    var components by mutableStateOf<List<Component>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var userRole by mutableStateOf<UserRole?>(null)
        private set
    var cartQuantities by mutableStateOf<Map<String, Int>>(emptyMap())

    var searchQuery by mutableStateOf("")
    var categoryFilter by mutableStateOf<String?>(null)
    var locationFilter by mutableStateOf<String?>(null)

    var showCartDialog by mutableStateOf(false)
    var cartError by mutableStateOf<String?>(null)
    var isSubmittingRequest by mutableStateOf(false)
        private set
    var isUploadingImage by mutableStateOf(false)
        private set
    var isImportingCsv by mutableStateOf(false)
        private set

    var facultyOptions by mutableStateOf<List<User>>(emptyList())
        private set

    private var _projectTitle by mutableStateOf("")
    var projectTitle: String
        get() = _projectTitle
        set(value) {
            if (_projectTitle == value) return
            _projectTitle = value
            persistDraft()
        }

    private var _selectedFacultyId by mutableStateOf<String?>(null)
    var selectedFacultyId: String?
        get() = _selectedFacultyId
        set(value) {
            if (_selectedFacultyId == value) return
            _selectedFacultyId = value
            persistDraft()
        }

    var isLoadingFaculty by mutableStateOf(false)
        private set
    var pendingRequestsCount by mutableStateOf(0)
        private set

    var editingComponent by mutableStateOf<Component?>(null)
    var showDeleteDialog by mutableStateOf<Component?>(null)

    private val _snackbarMessages = MutableSharedFlow<String>()
    val snackbarMessages = _snackbarMessages.asSharedFlow()

    private var currentUserId: String? = null

    private var isRestoringDraft = false

    val filteredComponents: List<Component>
        get() {
            val query = searchQuery.trim().lowercase()
            return components.filter { component ->
                val matchesQuery =
                    query.isEmpty() ||
                        component.name.lowercase().contains(query) ||
                        component.description
                            ?.lowercase()
                            ?.contains(query) == true ||
                        component.category
                            ?.lowercase()
                            ?.contains(query) == true ||
                        component.location?.lowercase()?.contains(query) == true

                val matchesCategory = categoryFilter?.let { component.category == it } ?: true
                val matchesLocation = locationFilter?.let { component.location == it } ?: true

                matchesQuery && matchesCategory && matchesLocation
            }
        }

    init {
        loadInitialData()
        startPolling()
        observeCache()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            loadUserData()
            loadComponents(pollingMode = false)
            loadFaculty()
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                delay(8000.milliseconds)
                if (errorMessage == null && !isLoading && !isRefreshing) {
                    loadComponents(pollingMode = true)
                }
            }
        }
    }

    private fun observeCache() {
        viewModelScope.launch {
            componentsCache?.componentsFlow()?.collect { cached ->
                components = cached
                if (cached.isNotEmpty()) {
                    isLoading = false
                }
            }
        }
    }

    private fun loadUserData() {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response = authApiService.getMe(token)
                    userRole = response.user.role
                    currentUserId = response.user.id
                    restoreDraft()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    /**
     * Restores the request the user was putting together, clamping quantities
     * to the stock that is available now.
     */
    private suspend fun restoreDraft() {
        val userId = currentUserId ?: return
        val cache = draftCache ?: return

        isRestoringDraft = true
        try {
            val items = cache.getItems(userId)
            val quantities =
                items
                    .mapNotNull { item ->
                        val available = item.availableQuantity ?: return@mapNotNull null
                        val quantity = item.quantity.coerceIn(0, available)
                        if (quantity > 0) item.componentId to quantity else null
                    }.toMap()

            val draft = cache.getDraft(userId)

            // The user may already have started a new cart while the draft
            // was being read — never overwrite that.
            if (cartQuantities.isNotEmpty()) return

            if (quantities.isNotEmpty() || draft?.projectTitle != null || draft?.targetFacultyId != null) {
                cartQuantities = quantities
                _projectTitle = draft?.projectTitle.orEmpty()
                _selectedFacultyId = draft?.targetFacultyId

                // Store the reconciled draft, so the stale lines are dropped.
                cache.save(
                    userId = userId,
                    quantities = quantities,
                    projectTitle = _projectTitle,
                    targetFacultyId = _selectedFacultyId,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally {
            isRestoringDraft = false
        }
    }

    private fun persistDraft() {
        if (isRestoringDraft) return
        val userId = currentUserId ?: return
        val cache = draftCache ?: return

        viewModelScope.launch {
            try {
                cache.save(
                    userId = userId,
                    quantities = cartQuantities,
                    projectTitle = _projectTitle,
                    targetFacultyId = _selectedFacultyId,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun loadComponents(pollingMode: Boolean = false) {
        viewModelScope.launch {
            if (pollingMode && isRefreshing) return@launch

            if (pollingMode) {
                isRefreshing = true
            } else {
                if (components.isEmpty()) {
                    isLoading = true
                } else {
                    isRefreshing = true
                }
                errorMessage = null
            }

            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response = componentApiService.getComponents(token)
                    if (componentsCache != null) {
                        componentsCache.save(response.components, null)
                    } else {
                        components = response.components
                    }
                    errorMessage = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportComponentLoadFailure(e, pollingMode)
            } finally {
                if (pollingMode) {
                    isRefreshing = false
                } else {
                    isLoading = false
                    isRefreshing = false
                }
            }
        }
    }

    private suspend fun reportComponentLoadFailure(
        e: Throwable,
        pollingMode: Boolean,
    ) {
        val appError = e.toAppError()
        if (appError is AppError.Unauthorized) return

        if (!pollingMode) {
            if (components.isEmpty()) {
                errorMessage = appError.message
            } else {
                _snackbarMessages.emit("Network error: Using cached data")
            }
        }
    }

    private fun loadFaculty() {
        viewModelScope.launch {
            isLoadingFaculty = true
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response = requestApiService.getFaculty(token)
                    facultyOptions = response.faculty
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            } finally {
                isLoadingFaculty = false
            }
        }
    }

    fun updateCartQuantity(
        component: Component,
        delta: Int,
    ) {
        val currentQty = cartQuantities[component.id] ?: 0
        val newQty = (currentQty + delta).coerceIn(0, component.availableQuantity)

        cartQuantities =
            if (newQty > 0) {
                cartQuantities + (component.id to newQty)
            } else {
                cartQuantities - component.id
            }
        persistDraft()
    }

    fun removeFromCart(componentId: String) {
        cartQuantities = cartQuantities - componentId
        persistDraft()
    }

    fun clearCart() {
        cartQuantities = emptyMap()
        _projectTitle = ""
        _selectedFacultyId = null
        clearPersistedDraft()
    }

    private fun clearPersistedDraft() {
        val userId = currentUserId ?: return
        val cache = draftCache ?: return

        viewModelScope.launch {
            try {
                cache.clear(userId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun submitRequest() {
        val facultyId = selectedFacultyId
        val title = projectTitle.trim()

        if (facultyId == null) {
            cartError = "Please select a faculty"
            return
        }
        if (title.isBlank()) {
            cartError = "Please enter a project title"
            return
        }

        viewModelScope.launch {
            isSubmittingRequest = true
            cartError = null
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val items =
                        cartQuantities.map { (id, qty) ->
                            RequestItemPayload(componentId = id, quantity = qty)
                        }
                    requestApiService.createRequest(
                        token = token,
                        payload =
                            CreateRequestPayload(
                                items = items,
                                targetFacultyId = facultyId,
                                projectTitle = title,
                            ),
                    )
                    clearCart()
                    showCartDialog = false
                    _snackbarMessages.emit("Request submitted successfully")
                    loadComponents(pollingMode = true)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                cartError = "Failed to submit request: ${e.toAppError().message}"
            } finally {
                isSubmittingRequest = false
            }
        }
    }

    fun deleteComponent(component: Component) {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    componentApiService.deleteComponent(token, component.id)
                    loadComponents(pollingMode = true)
                    _snackbarMessages.emit("Component deleted successfully")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _snackbarMessages.emit("Failed to delete component: ${e.toAppError().message}")
            } finally {
                showDeleteDialog = null
            }
        }
    }

    fun saveComponent(
        request: ComponentRequest,
        onDismiss: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    if (editingComponent != null) {
                        componentApiService.updateComponent(
                            token,
                            editingComponent!!.id,
                            request,
                        )
                    } else {
                        componentApiService.createComponent(
                            token,
                            request,
                        )
                    }
                    onDismiss()
                    editingComponent = null
                    loadComponents()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _snackbarMessages.emit("Failed to save component: ${e.toAppError().message}")
            }
        }
    }

    fun importComponentsFromCsv(csvContent: String) {
        if (csvContent.isBlank()) {
            _snackbarMessages.tryEmit("Import cancelled: file is empty")
            return
        }
        viewModelScope.launch {
            isImportingCsv = true
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response =
                        componentApiService.importComponentsCsv(
                            token,
                            csvContent,
                        )
                    loadComponents(pollingMode = true)
                    _snackbarMessages.emit("Imported ${response.imported} component(s) successfully")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _snackbarMessages.emit("Failed to import components: ${e.toAppError().message}")
            } finally {
                isImportingCsv = false
            }
        }
    }

    fun exportComponentsCsv(onResult: (String?) -> Unit) {
        val role = userRole
        if (role !in setOf(UserRole.ADMIN, UserRole.LA, UserRole.FACULTY)) {
            onResult(null)
            return
        }
        if (components.isEmpty()) {
            onResult(null)
            return
        }

        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    onResult(componentApiService.exportComponentsCsv(token))
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onResult(null)
            }
        }
    }

    fun uploadImage(
        bytes: ByteArray,
        filename: String,
    ) {
        val componentId = editingComponent?.id ?: return
        viewModelScope.launch {
            isUploadingImage = true
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    val response =
                        componentApiService.uploadImage(
                            token,
                            componentId,
                            bytes,
                            filename,
                        )
                    editingComponent = response.component
                    loadComponents(pollingMode = true)
                    _snackbarMessages.emit("Image uploaded successfully")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _snackbarMessages.emit("Failed to upload image: ${e.toAppError().message}")
            } finally {
                isUploadingImage = false
            }
        }
    }

    fun removeImage() {
        val componentId = editingComponent?.id ?: return
        viewModelScope.launch {
            try {
                val token = tokenManager.token.first()
                if (token != null) {
                    componentApiService.deleteImage(token, componentId)
                    loadComponents()
                    _snackbarMessages.emit("Image removed successfully")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _snackbarMessages.emit("Failed to remove image: ${e.toAppError().message}")
            }
        }
    }
}
