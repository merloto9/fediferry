/*
 * FediFerry — share a meme screenshot straight to Mastodon.
 * Copyright (C) 2026 Jasper Ramthun
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.fediferry

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fediferry.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A saved meme is the whole point of the app, so an update that drops the
 * database would be the worst possible bug. This builds a real v1 database with
 * a draft in it and checks the draft is still there afterwards.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val database = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate1To2KeepsExistingDrafts() {
        helper.createDatabase(database, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO items
                  (id, mediaPath, mediaHash, mimeType, sourceUrl, bodyText, altText,
                   altTextFailed, contentWarning, visibility, templateId, accountId,
                   status, failureReason, createdAt, postedAt, statusUrl, scheduledAt)
                VALUES
                  ('keep-me', '/data/media/a.jpg', 'abc', 'image/jpeg',
                   'https://instagram.com/p/abc', '#meme', NULL,
                   0, NULL, 'PUBLIC', 'default', NULL,
                   'DRAFT', NULL, 1700000000000, NULL, NULL, NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            database,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        )

        db.query("SELECT id, bodyText, mediaPath, originalMediaPath FROM items").use { c ->
            assertTrue("the draft was lost in the migration", c.moveToFirst())
            assertEquals("keep-me", c.getString(0))
            assertEquals("#meme", c.getString(1))
            assertEquals("/data/media/a.jpg", c.getString(2))
            // The new column exists and defaults to null on a pre-existing row.
            assertNull(c.getString(3))
            assertEquals(1, c.count)
        }
    }

    @Test
    fun migrate2To3AddsCleanupTablesAndKeepsDrafts() {
        helper.createDatabase(database, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO items
                  (id, mediaPath, originalMediaPath, mediaHash, mimeType, sourceUrl,
                   bodyText, altText, altTextFailed, contentWarning, visibility,
                   templateId, accountId, status, failureReason, createdAt, postedAt,
                   statusUrl, scheduledAt)
                VALUES
                  ('still-here', '/data/media/b.jpg', NULL, 'bcd', 'image/jpeg', NULL,
                   '#meme', NULL, 0, NULL, 'PUBLIC',
                   'default', NULL, 'DRAFT', NULL, 1700000000000, NULL,
                   NULL, NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(database, 3, true, AppDatabase.MIGRATION_2_3)

        db.query("SELECT id FROM items").use { c ->
            assertTrue("the draft was lost", c.moveToFirst())
            assertEquals("still-here", c.getString(0))
        }
        // The new tables exist and are usable, not merely declared.
        db.execSQL("INSERT INTO cleanup_profiles (id, name, isDefault) VALUES ('p1','Instagram',1)")
        db.execSQL(
            """
            INSERT INTO cleanup_rules
              (id, profileId, `left`, `top`, `right`, `bottom`, treatment, enabled, sortOrder)
            VALUES ('r1','p1',0.8,0.02,0.97,0.08,'FILL',1,0)
            """.trimIndent(),
        )
        db.query("SELECT treatment FROM cleanup_rules WHERE profileId = 'p1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("FILL", c.getString(0))
        }
    }

    @Test
    fun migratingAllTheWayFrom1Works() {
        helper.createDatabase(database, 1).close()
        val db = helper.runMigrationsAndValidate(
            database,
            3,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
        )
        db.query("SELECT COUNT(*) FROM cleanup_profiles").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }
}
