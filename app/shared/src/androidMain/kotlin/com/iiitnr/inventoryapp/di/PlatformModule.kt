package com.iiitnr.inventoryapp.di

import com.iiitnr.inventoryapp.data.cache.DatabaseModule
import com.iiitnr.inventoryapp.data.cache.DriverFactory
import com.iiitnr.inventoryapp.data.cache.createComponentsCache
import com.iiitnr.inventoryapp.data.cache.createDraftCache
import com.iiitnr.inventoryapp.data.storage.ThemeSettings
import com.iiitnr.inventoryapp.data.storage.createTokenManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module =
    module {
        single { createTokenManager(androidContext()) }
        single { createComponentsCache(androidContext()) }
        single { createDraftCache(androidContext()) }
        single { ThemeSettings(DatabaseModule.getDatabase(DriverFactory(androidContext()))) }
    }
