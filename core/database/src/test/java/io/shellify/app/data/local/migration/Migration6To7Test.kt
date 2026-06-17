package io.shellify.app.data.local.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for MIGRATION_6_7 verifying the SQL statements and migration version numbers.
 *
 * Full end-to-end Room migration testing is covered by DatabaseMigrationTest in androidTest.
 */
class Migration6To7Test {

    @Test
    fun `migration starts at version 6`() {
        assertEquals(6, MIGRATION_6_7.startVersion)
    }

    @Test
    fun `migration ends at version 7`() {
        assertEquals(7, MIGRATION_6_7.endVersion)
    }

    @Test
    fun `migration adds shared_space column to categories`() {
        val recordedStatements = mutableListOf<String>()
        val stubDb = StubSupportSQLiteDatabase(recordedStatements)

        MIGRATION_6_7.migrate(stubDb)

        val alter = recordedStatements.firstOrNull { it.contains("categories", ignoreCase = true) }
        assertTrue("Migration must alter the categories table", alter != null)
        assertTrue(
            "Column must be a non-null INTEGER defaulting to 0",
            alter!!.contains("ADD COLUMN sharedSpace INTEGER NOT NULL DEFAULT 0", ignoreCase = true),
        )
    }

    @Test
    fun `migration executes exactly one SQL statement`() {
        val recordedStatements = mutableListOf<String>()
        val stubDb = StubSupportSQLiteDatabase(recordedStatements)

        MIGRATION_6_7.migrate(stubDb)

        assertEquals("Migration must execute exactly 1 SQL statement", 1, recordedStatements.size)
    }
}
