package com.iiitnr.inventoryapp.shared

import androidx.compose.ui.window.ComposeUIViewController
import com.iiitnr.inventoryapp.di.initKoin
import com.iiitnr.inventoryapp.ui.theme.AppTheme
import com.iiitnr.inventoryapp.ui.theme.rememberThemeMode

private var koinInitialized = false

fun mainViewController() =
    ComposeUIViewController(
        configure = {
            enforceStrictPlistSanityCheck = false
        },
    ) {
        if (!koinInitialized) {
            initKoin()
            koinInitialized = true
        }
        AppTheme(themeMode = rememberThemeMode()) {
            App()
        }
    }
