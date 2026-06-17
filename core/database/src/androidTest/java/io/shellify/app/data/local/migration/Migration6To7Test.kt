package io.shellify.app.data.local.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.shellify.app.data.local.AppDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for MIGRATION_6_7.
 *
 * Verifies that upgrading an existing v6 database to v7 adds the `sharedSpace` column to
 * `categories` with DEFAULT 0, and that existing rows survive the upgrade unchanged.
 */
@RunWith(AndroidJUnit4::class)
class Migration6To7Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(TEST_PASSPHRASE.copyOf()),
    )

    @Before
    fun loadSqlCipherLibrary() {
        System.loadLibrary("sqlcipher")
    }

    @Test
    fun migrate6To7_addsSharedSpaceColumnToCategories() {
        // Step 1: create a v6 database with one existing category row.
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO categories (id, name, sortIndex, icon, color) VALUES (1, 'Google', 0, 'folder', '#6D28D9')"
            )
        }

        // Step 2: run MIGRATION_6_7 and validate the schema matches 7.json.
        helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7).use { db ->

            // Step 3: verify the new column exists and defaults to 0 for the migrated row.
            db.query("SELECT sharedSpace FROM categories WHERE id = 1").use { cursor ->
                assertTrue("categories row not found after migration 6→7", cursor.moveToFirst())
                assertEquals("sharedSpace must default to 0", 0, cursor.getInt(0))
            }

            // Step 4: insert a category with sharedSpace = 1 and verify round-trip.
            db.execSQL(
                "INSERT INTO categories (id, name, sortIndex, icon, color, sharedSpace) " +
                    "VALUES (2, 'Shared', 1, 'work', '#4338CA', 1)"
            )
            db.query("SELECT sharedSpace FROM categories WHERE id = 2").use { cursor ->
                assertTrue("new category row not found after insert", cursor.moveToFirst())
                assertEquals("sharedSpace should be 1", 1, cursor.getInt(0))
            }
        }
    }

    companion object {
        private const val TEST_DB = "migration-6-7-test.db"

        // Fixed passphrase for instrumented tests only — never used in production.
        private val TEST_PASSPHRASE = "shellify-test-passphrase".toByteArray()
    }
}
