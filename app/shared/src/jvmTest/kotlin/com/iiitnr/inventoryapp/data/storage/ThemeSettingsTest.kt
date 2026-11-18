package com.iiitnr.inventoryapp.data.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.iiitnr.inventoryapp.db.AppDatabase
import com.iiitnr.inventoryapp.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeSettingsTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("iiitnr-theme-test").toFile()
        dir.deleteOnExit()
        val file = File(dir, "test.db")
        file.deleteOnExit()

        driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun defaultsToSystemWhenNothingIsStored() {
        assertEquals(ThemeMode.SYSTEM, ThemeSettings(database).themeMode.value)
    }

    @Test
    fun persistsTheSelectedModeAcrossInstances() =
        runBlocking {
            ThemeSettings(database).setThemeMode(ThemeMode.DARK)

            // A fresh instance (next app launch) reads the stored row back.
            assertEquals(ThemeMode.DARK, ThemeSettings(database).themeMode.value)
            assertEquals("DARK", database.appDatabaseQueries.getSetting("theme_mode").executeAsOneOrNull())
        }

    @Test
    fun fallsBackToSystemForAnUnknownStoredValue() =
        runBlocking {
            database.appDatabaseQueries.upsertSetting("theme_mode", "PURPLE")

            assertEquals(ThemeMode.SYSTEM, ThemeSettings(database).themeMode.value)
        }

    @Test
    fun worksWithoutADatabaseForTargetsWithoutSqlDelight() =
        runBlocking {
            val settings = ThemeSettings()
            settings.setThemeMode(ThemeMode.LIGHT)

            assertEquals(ThemeMode.LIGHT, settings.themeMode.value)
        }
}
