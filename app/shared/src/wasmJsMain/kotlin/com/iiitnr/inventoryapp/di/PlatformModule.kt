package com.iiitnr.inventoryapp.di

import com.iiitnr.inventoryapp.data.storage.ThemeSettings
import com.iiitnr.inventoryapp.data.storage.createTokenManager
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module =
    module {
        single { createTokenManager() }
        // The web build has no SQLDelight worker wired up yet, so the theme
        // preference is in-memory there (it still applies for the session).
        single { ThemeSettings() }
        // Add components cache if needed for wasm
    }
