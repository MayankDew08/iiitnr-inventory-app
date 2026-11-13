package com.iiitnr.inventoryapp.di

import com.iiitnr.inventoryapp.data.api.ApiClient
import com.iiitnr.inventoryapp.ui.screens.AuditLogViewModel
import com.iiitnr.inventoryapp.ui.screens.AuthViewModel
import com.iiitnr.inventoryapp.ui.screens.ComponentsViewModel
import com.iiitnr.inventoryapp.ui.screens.FinesAdminStudentDetailViewModel
import com.iiitnr.inventoryapp.ui.screens.FinesAdminViewModel
import com.iiitnr.inventoryapp.ui.screens.FinesViewModel
import com.iiitnr.inventoryapp.ui.screens.NotificationsViewModel
import com.iiitnr.inventoryapp.ui.screens.ProfileViewModel
import com.iiitnr.inventoryapp.ui.screens.RequestsViewModel
import com.iiitnr.inventoryapp.ui.screens.UserManagementViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val apiModule =
    module {
        single { ApiClient.client }
        single { ApiClient.authApiService }
        single { ApiClient.componentApiService }
        single { ApiClient.requestApiService }
        single { ApiClient.notificationApiService }
        single { ApiClient.auditLogApiService }
        single { ApiClient.userApiService }
        single { ApiClient.versionApiService }
    }

val storageModule =
    module {
        // Platform specific storage provided in platformModule
    }

val viewModelModule =
    module {
        viewModel { RequestsViewModel(get(), get(), get()) }
        viewModel { FinesViewModel(get(), get()) }
        viewModel { FinesAdminViewModel(get(), get()) }
        viewModel { FinesAdminStudentDetailViewModel(get(), get()) }
        viewModel { ComponentsViewModel(get(), get(), get(), get(), getOrNull(), getOrNull()) }
        viewModel { AuthViewModel(get(), get()) }
        viewModel { ProfileViewModel(get(), get()) }
        viewModel { AuditLogViewModel(get(), get()) }
        viewModel { NotificationsViewModel(get(), get()) }
        viewModel { UserManagementViewModel(get(), get()) }
    }

expect val platformModule: Module

val appModule =
    module {
        includes(apiModule, storageModule, viewModelModule, platformModule)
    }
