package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iiitnr.inventoryapp.data.models.UpdateUserRequest
import com.iiitnr.inventoryapp.data.models.User
import com.iiitnr.inventoryapp.data.models.UserRole
import com.iiitnr.inventoryapp.ui.components.common.AppTopBar
import com.iiitnr.inventoryapp.ui.components.common.PaginationBar
import com.iiitnr.inventoryapp.ui.components.common.SearchBar
import com.iiitnr.inventoryapp.ui.components.common.handCursor
import com.iiitnr.inventoryapp.ui.components.common.userRoleColor
import com.iiitnr.inventoryapp.ui.theme.LocalIsDarkTheme
import org.koin.compose.viewmodel.koinViewModel

private val allRoles: List<UserRole> = UserRole.ALL

private val roleFilterOptions: List<UserRole?> =
    listOf(
        null,
        UserRole.PENDING,
        UserRole.STUDENT,
        UserRole.FACULTY,
        UserRole.LA,
        UserRole.ADMIN,
    )

private fun roleFilterLabel(role: UserRole?): String =
    when (role) {
        null -> "All"
        UserRole.PENDING -> "Pending"
        UserRole.STUDENT -> "Student"
        UserRole.FACULTY -> "Faculty"
        UserRole.LA -> "LA"
        UserRole.ADMIN -> "Admin"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: UserManagementViewModel = koinViewModel(),
) {
    val users = viewModel.filteredUsers
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage
    val totalCount = viewModel.totalCount
    val currentOffset = viewModel.currentOffset
    val searchQuery = viewModel.searchQuery
    val pageSize = viewModel.pageSize

    var editingUser by remember { mutableStateOf<User?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "User Management",
                onNavigateBack = onNavigateBack,
                maxContentWidth = 1000.dp,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            UserManagementHeader(
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.onSearchQueryChange(it) },
                roleFilter = viewModel.roleFilter,
                onRoleFilterChange = { viewModel.onRoleFilterChange(it) },
            )

            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                errorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                errorMessage.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.loadUsers() }) { Text("Retry") }
                        }
                    }
                }

                users.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No users found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        LazyColumn(
                            modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize(),
                            contentPadding =
                                PaddingValues(
                                    start = 12.dp,
                                    top = 12.dp,
                                    end = 12.dp,
                                    bottom = 12.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(users, key = { it.id }) { user ->
                                UserCard(
                                    user = user,
                                    onClick = { editingUser = user },
                                )
                            }
                        }
                    }

                    PaginationBar(
                        currentOffset = currentOffset,
                        pageSize = pageSize,
                        totalCount = totalCount,
                        onPrevious = { viewModel.onPreviousPage() },
                        onNext = { viewModel.onNextPage() },
                        maxContentWidth = 1000.dp,
                    )
                }
            }
        }
    }

    editingUser?.let { user ->
        EditUserDialog(
            user = user,
            onSave = { name, role, batch, branch ->
                viewModel.updateUser(
                    userId = user.id,
                    request =
                        UpdateUserRequest(
                            name = name.ifBlank { null },
                            role = role,
                            batch = batch.ifBlank { null },
                            branch = branch.ifBlank { null },
                        ),
                )
                editingUser = null
            },
            onDismiss = { editingUser = null },
        )
    }
}

/**
 * Search + role pills live in a centred, width-capped header above a divider so
 * cards scrolling underneath never clip right under the search field.
 */
@Composable
private fun UserManagementHeader(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    roleFilter: UserRole?,
    onRoleFilterChange: (UserRole?) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 1000.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SearchBar(
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                placeholder = "Search users...",
                modifier = Modifier.fillMaxWidth(),
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(roleFilterOptions) { role ->
                    val isSelected = roleFilter == role
                    val selectedColor = role?.let { userRoleColor(it) } ?: MaterialTheme.colorScheme.primary
                    TextButton(
                        onClick = { onRoleFilterChange(role) },
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(
                            text = roleFilterLabel(role),
                            color =
                                if (isSelected) {
                                    selectedColor
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun UserCard(
    user: User,
    onClick: () -> Unit,
) {
    val roleColor = userRoleColor(user.role)
    val isDark = LocalIsDarkTheme.current
    // Blend the role hue onto the card surface: a raw alpha tint over a light
    // surface reads muddy, so light mode gets a much smaller fraction and no shadow.
    val cardBackground =
        lerp(
            start = MaterialTheme.colorScheme.surfaceContainerLow,
            stop = roleColor,
            fraction = if (isDark) 0.14f else 0.07f,
        )

    Card(
        modifier = Modifier.fillMaxWidth().handCursor().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 1.dp else 0.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, roleColor.copy(alpha = 0.35f)),
        colors =
            CardDefaults.cardColors(
                containerColor = cardBackground,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = user.name ?: "No name",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = roleColor.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = user.role.name,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = roleColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = user.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (user.batch != null || user.branch != null) {
                Spacer(Modifier.height(4.dp))
                Row {
                    if (user.batch != null) {
                        Text(
                            text = user.batch,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (user.batch != null && user.branch != null) {
                        Text(
                            text = " • ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (user.branch != null) {
                        Text(
                            text = user.branch,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditUserDialog(
    user: User,
    onSave: (name: String, role: UserRole, batch: String, branch: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(user.name ?: "") }
    var role by remember { mutableStateOf(user.role) }
    var batch by remember { mutableStateOf(user.batch ?: "") }
    var branch by remember { mutableStateOf(user.branch ?: "") }
    var roleDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit User") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(
                    expanded = roleDropdownExpanded,
                    onExpandedChange = { roleDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = role.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Role") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roleDropdownExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = roleDropdownExpanded,
                        onDismissRequest = { roleDropdownExpanded = false },
                    ) {
                        allRoles.forEach { roleOption ->
                            DropdownMenuItem(
                                text = { Text(roleOption.name) },
                                onClick = {
                                    role = roleOption
                                    roleDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = batch,
                    onValueChange = { batch = it },
                    label = { Text("Batch") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    label = { Text("Branch") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, role, batch, branch) },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
