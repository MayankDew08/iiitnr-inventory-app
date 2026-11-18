package com.iiitnr.inventoryapp.data.storage

import com.iiitnr.inventoryapp.db.AppDatabase
import com.iiitnr.inventoryapp.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Reads and writes the user's [ThemeMode] preference.
 *
 * The preference lives in the shared SQLDelight database (`AppSetting`), so the
 * same row is used on Android, Desktop and iOS. Targets that do not wire up
 * SQLDelight (the web build) fall back to an in-memory value: [ThemeSettings]
 * stays fully functional, it just does not survive a reload.
 */
class ThemeSettings(
    database: AppDatabase? = null,
) {
    private val queries = database?.appDatabaseQueries

    private var persisted = readPersisted()

    private val _themeMode = MutableStateFlow(persisted)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    suspend fun setThemeMode(mode: ThemeMode) {
        persisted = mode
        _themeMode.value = mode

        val settingsQueries = queries ?: return
        withContext(Dispatchers.Default) {
            runCatching { settingsQueries.upsertSetting(THEME_MODE_KEY, mode.name) }
        }
    }

    private fun readPersisted(): ThemeMode {
        val settingsQueries = queries
        val stored =
            settingsQueries?.let { settings ->
                runCatching { settings.getSetting(THEME_MODE_KEY).executeAsOneOrNull() }.getOrNull()
            }
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    }

    private companion object {
        const val THEME_MODE_KEY = "theme_mode"
    }
}
