package com.iiitnr.inventoryapp.data.cache

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.iiitnr.inventoryapp.db.AppDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop (JDBC) driver has to migrate the local database itself, because
 * unlike the Android and native drivers it has no schema callback.
 */
class DriverMigrationTest {
    private fun tempDriver(): SqlDriver {
        val dir = Files.createTempDirectory("iiitnr-driver-test").toFile()
        dir.deleteOnExit()
        val file = File(dir, "test.db")
        file.deleteOnExit()
        return JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { cursor ->
                    val version = if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
                    QueryResult.Value(version)
                },
                parameters = 0,
            ).value

    private fun tableNames(driver: SqlDriver): List<String> {
        val names = mutableListOf<String>()
        driver
            .executeQuery(
                identifier = null,
                sql = "SELECT name FROM sqlite_master WHERE type = 'table'",
                mapper = { cursor ->
                    while (cursor.next().value) {
                        names.add(cursor.getString(0) ?: "")
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            ).value
        return names
    }

    @Test
    fun createsTheSchemaOnAFreshDatabase() {
        val driver = tempDriver()
        try {
            migrateSchema(driver)

            val tables = tableNames(driver)
            assertTrue("Component" in tables)
            assertTrue("RequestDraft" in tables)
            assertTrue("RequestDraftItem" in tables)
            assertTrue("AppSetting" in tables)
            assertEquals(AppDatabase.Schema.version, userVersion(driver))
        } finally {
            driver.close()
        }
    }

    @Test
    fun migratesADatabaseThatPredatesTheDraftTables() {
        val driver = tempDriver()
        try {
            // A version 1 database: the component catalog, but no draft tables.
            driver.execute(
                null,
                """
                CREATE TABLE Component (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT,
                    imageUrl TEXT,
                    totalQuantity INTEGER NOT NULL,
                    availableQuantity INTEGER NOT NULL,
                    category TEXT,
                    location TEXT,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL
                )
                """.trimIndent(),
                0,
            )
            driver.execute(null, "CREATE TABLE SyncMetadata (key TEXT PRIMARY KEY, lastModified TEXT)", 0)
            // Existing inventory must survive the migration.
            driver.execute(
                null,
                """
                INSERT INTO Component(id, name, totalQuantity, availableQuantity, createdAt, updatedAt)
                VALUES ('comp-1', 'Arduino Uno', 10, 7, '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')
                """.trimIndent(),
                0,
            )
            driver.execute(null, "PRAGMA user_version = 1", 0)

            migrateSchema(driver)

            val tables = tableNames(driver)
            assertTrue("Component" in tables)
            assertTrue("RequestDraft" in tables)
            assertTrue("RequestDraftItem" in tables)
            assertTrue("AppSetting" in tables)
            assertEquals(AppDatabase.Schema.version, userVersion(driver))

            val componentNames = mutableListOf<String>()
            driver
                .executeQuery(
                    identifier = null,
                    sql = "SELECT name FROM Component WHERE id = 'comp-1'",
                    mapper = { cursor ->
                        while (cursor.next().value) {
                            componentNames.add(cursor.getString(0) ?: "")
                        }
                        QueryResult.Value(Unit)
                    },
                    parameters = 0,
                ).value
            assertEquals(listOf("Arduino Uno"), componentNames)
        } finally {
            driver.close()
        }
    }

    @Test
    fun migratesADatabaseThatHasNoVersionButAlreadyHasTables() {
        val driver = tempDriver()
        try {
            // Created by an older build, which never wrote a schema version.
            driver.execute(null, "CREATE TABLE Component (id TEXT PRIMARY KEY, name TEXT NOT NULL)", 0)

            migrateSchema(driver)

            val tables = tableNames(driver)
            assertTrue("RequestDraft" in tables)
            assertTrue("RequestDraftItem" in tables)
            assertTrue("AppSetting" in tables)
            assertEquals(AppDatabase.Schema.version, userVersion(driver))
        } finally {
            driver.close()
        }
    }
}
