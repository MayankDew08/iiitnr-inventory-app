package com.iiitnr.inventoryapp.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.iiitnr.inventoryapp.data.auth.GoogleDesktopSignInHelper
import com.iiitnr.inventoryapp.di.initKoin
import com.iiitnr.inventoryapp.shared.App
import com.iiitnr.inventoryapp.ui.platform.exportComponentsCsvDesktop
import com.iiitnr.inventoryapp.ui.theme.AppTheme
import com.iiitnr.inventoryapp.ui.theme.rememberThemeMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

fun main() {
    initKoin()

    application {
        val properties = try {
            val classLoader = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
            val stream = classLoader.getResourceAsStream("google-desktop-config.properties")
            stream?.use { java.util.Properties().apply { load(it) } } ?: java.util.Properties()
        } catch (_: Exception) {
            java.util.Properties()
        }

        val desktopClientId = properties.getProperty("google.desktop.client.id")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GOOGLE_DESKTOP_CLIENT_ID").orEmpty()
        val desktopClientSecret = properties.getProperty("google.desktop.client.secret")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GOOGLE_DESKTOP_CLIENT_SECRET")
        val redirectUri = properties.getProperty("google.desktop.redirect.uri")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GOOGLE_DESKTOP_REDIRECT_URI") ?: "http://127.0.0.1:5173/callback"

        val httpClient = HttpClient(CIO)
        val googleHelper = GoogleDesktopSignInHelper(
            clientId = desktopClientId,
            clientSecret = desktopClientSecret,
            redirectUri = redirectUri,
            httpClient = httpClient,
        )
        val scope = CoroutineScope(Dispatchers.IO)

        @Suppress("DEPRECATION")
        val windowIcon = painterResource("icon.png")

        val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))

        Window(
            onCloseRequest = ::exitApplication,
            title = "IIITNR Inventory App",
            icon = windowIcon,
            state = windowState,
        ) {
            LaunchedEffect(window) {
                window.minimumSize = java.awt.Dimension(800, 600)
            }

            AppTheme(themeMode = rememberThemeMode()) {
                App(
                    onGoogleSignInClick = { callback ->
                        scope.launch {
                            val idToken = googleHelper.signIn()
                            callback(idToken)
                        }
                    },
                    onExportComponentsCsv = { csvContent ->
                        exportComponentsCsvDesktop(
                            filename = "components.csv",
                            content = csvContent,
                        )
                    },
                    onExportRequestsCsv = { csvContent ->
                        exportComponentsCsvDesktop(
                            filename = "requests.csv",
                            content = csvContent,
                        )
                    },
                    onImportComponentsCsv = { completion ->
                        val chooser =
                            JFileChooser().apply {
                                dialogTitle = "Import Components CSV"
                                fileFilter = FileNameExtensionFilter("CSV files (*.csv)", "csv")
                            }
                        val result = chooser.showOpenDialog(null)
                        if (result == JFileChooser.APPROVE_OPTION) {
                            val file = chooser.selectedFile
                            try {
                                completion(file.readText(Charsets.UTF_8))
                            } catch (_: Exception) {
                                completion(null)
                            }
                        } else {
                            completion(null)
                        }
                    },
                )
            }
        }
    }
}
