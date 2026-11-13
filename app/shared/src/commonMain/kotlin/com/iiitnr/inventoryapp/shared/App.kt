package com.iiitnr.inventoryapp.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import com.iiitnr.inventoryapp.data.Version
import com.iiitnr.inventoryapp.data.api.ApiClient
import com.iiitnr.inventoryapp.data.api.AuthEvent
import com.iiitnr.inventoryapp.data.api.AuthEventManager
import com.iiitnr.inventoryapp.data.storage.TokenManager
import com.iiitnr.inventoryapp.ui.screens.AuditLogScreen
import com.iiitnr.inventoryapp.ui.screens.ComponentsScreen
import com.iiitnr.inventoryapp.ui.screens.FinesAdminScreen
import com.iiitnr.inventoryapp.ui.screens.FinesAdminStudentDetailScreen
import com.iiitnr.inventoryapp.ui.screens.FinesScreen
import com.iiitnr.inventoryapp.ui.screens.LoginScreen
import com.iiitnr.inventoryapp.ui.screens.NotificationsScreen
import com.iiitnr.inventoryapp.ui.screens.ProfileScreen
import com.iiitnr.inventoryapp.ui.screens.RegisterScreen
import com.iiitnr.inventoryapp.ui.screens.RequestsScreen
import com.iiitnr.inventoryapp.ui.screens.UserManagementScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun App(
    isWeb: Boolean = false,
    onUpdateAction: (() -> Unit)? = null,
    onGoogleSignInClick: ((String?) -> Unit) -> Unit = {},
    onExportComponentsCsv: ((String) -> Boolean)? = null,
    onExportRequestsCsv: ((String) -> Boolean)? = null,
    onImportComponentsCsv: (((String?) -> Unit) -> Unit)? = null,
    tokenManager: TokenManager = koinInject(),
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        val navController = rememberNavController()
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val uriHandler = LocalUriHandler.current
        var startDestination by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            scope.launch {
                val token = tokenManager.token.first()
                startDestination = if (token != null) "components" else "login"
            }
            scope.launch {
                try {
                    val serverVersion = ApiClient.versionApiService.fetchServerVersion().version
                    if (Version.isVersionNewer(Version.CURRENT_VERSION, serverVersion)) {
                        val result =
                            snackbarHostState.showSnackbar(
                                message = "Update available: $serverVersion",
                                actionLabel = if (isWeb) "Reload" else "Download",
                                duration = androidx.compose.material3.SnackbarDuration.Short,
                            )
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            onUpdateAction?.invoke() ?: uriHandler.openUri("https://inventory.iiitnr.ac.in/download")
                        }
                    }
                } catch (e: Throwable) {
                    // Silently fail on version check
                }
            }
            scope.launch {
                AuthEventManager.events.collect { event ->
                    if (event is AuthEvent.Unauthorized) {
                        tokenManager.clearToken()
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Session expired. Please login again.",
                                duration = androidx.compose.material3.SnackbarDuration.Short,
                            )
                        }
                        navController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            modifier = Modifier.fillMaxSize(),
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                startDestination?.let { destination ->
                    NavHost(
                        navController = navController,
                        startDestination = destination,
                    ) {
                        composable("login") {
                            LoginScreen(
                                onLoginSuccess = {
                                    navController.navigate("components") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                },
                                onNavigateToRegister = {
                                    navController.navigate("register")
                                },
                                onGoogleSignInClick = onGoogleSignInClick,
                            )
                        }
                        composable("register") {
                            RegisterScreen(onRegisterSuccess = {
                                navController.navigate("components") {
                                    popUpTo("register") { inclusive = true }
                                }
                            }, onNavigateToLogin = {
                                navController.navigate("login") {
                                    popUpTo("register") { inclusive = true }
                                }
                            })
                        }
                        composable("components") {
                            ComponentsScreen(
                                onNavigateToRequests = {
                                    navController.navigate("requests")
                                },
                                onNavigateToProfile = {
                                    navController.navigate("profile")
                                },
                                onNavigateToNotifications = {
                                    navController.navigate("notifications")
                                },
                                onNavigateToFines = {
                                    navController.navigate("fines")
                                },
                                onNavigateToFinesAdmin = {
                                    navController.navigate("fines_admin")
                                },
                                onExportCsv = onExportComponentsCsv,
                                onImportCsv = onImportComponentsCsv,
                            )
                        }
                        composable("fines") {
                            FinesScreen(
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                        composable("fines_admin") {
                            FinesAdminScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onStudentClick = { userId -> navController.navigate("fines_admin/$userId") },
                            )
                        }
                        composable(
                            route = "fines_admin/{userId}",
                            arguments = listOf(navArgument("userId") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val userId = backStackEntry.arguments?.read { getString("userId") }.orEmpty()
                            FinesAdminStudentDetailScreen(
                                userId = userId,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                        composable("profile") {
                            ProfileScreen(onLogout = {
                                navController.navigate("login") {
                                    popUpTo(0) { inclusive = true }
                                }
                            }, onNavigateBack = {
                                navController.popBackStack()
                            }, onNavigateToRequests = {
                                navController.navigate("requests")
                            }, onNavigateToAuditLog = {
                                navController.navigate("audit_log")
                            }, onNavigateToUserManagement = {
                                navController.navigate("user_management")
                            }, onNavigateToNotifications = {
                                navController.navigate("notifications")
                            })
                        }
                        composable("notifications") {
                            NotificationsScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onOpenRequest = {
                                    navController.navigate("requests")
                                },
                            )
                        }
                        composable("requests") {
                            RequestsScreen(onNavigateBack = {
                                navController.popBackStack()
                            }, onNavigateToNotifications = {
                                navController.navigate("notifications")
                            }, onExportCsv = onExportRequestsCsv)
                        }
                        composable("audit_log") {
                            AuditLogScreen(
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                        composable("user_management") {
                            UserManagementScreen(
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
