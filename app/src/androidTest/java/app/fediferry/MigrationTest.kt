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

    /**
     * 4 → 5 adds the placeholder layer. A draft and a template written before
     * it must come through untouched, and {caption} must arrive as a
     * user-defined placeholder carrying its old mappings — minus Pinterest's.
     */
    @Test
    fun migrate4To5SeedsCaptionAndKeepsDraftsAndTemplates() {
        helper.createDatabase(database, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO items
                  (id, mediaPath, originalMediaPath, mediaHash, mimeType, sourceUrl,
                   bodyText, altText, altTextFailed, contentWarning, visibility,
                   templateId, accountId, status, failureReason, createdAt, postedAt,
                   statusUrl, scheduledAt)
                VALUES
                  ('draft', '/data/media/c.jpg', NULL, 'cde', 'image/jpeg',
                   'https://9gag.com/gag/abc', 'A joke #meme', NULL, 0, NULL, 'PUBLIC',
                   'default', NULL, 'DRAFT', NULL, 1700000000000, NULL, NULL, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO templates
                  (id, name, body, tags, visibility, contentWarning, altTextMode,
                   staticAltText, accountId, isDefault, sortOrder)
                VALUES ('default', 'Meme', '{caption} {tags}', '#meme', 'PUBLIC',
                        NULL, 'NONE', NULL, NULL, 1, 0)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(database, 5, true, AppDatabase.MIGRATION_4_5)

        db.query("SELECT bodyText, origin, sourceFields FROM items WHERE id = 'draft'").use { c ->
            assertTrue("the draft was lost", c.moveToFirst())
            assertEquals("A joke #meme", c.getString(0))
            assertNull(c.getString(1))
            assertEquals("{}", c.getString(2))
        }
        db.query("SELECT body, excludedSources FROM templates WHERE id = 'default'").use { c ->
            assertTrue("the template was lost", c.moveToFirst())
            assertEquals("{caption} {tags}", c.getString(0))
            assertEquals("", c.getString(1))
        }
        db.query("SELECT name, mappings FROM placeholder_keys").use { c ->
            assertTrue("{caption} was not seeded", c.moveToFirst())
            assertEquals("caption", c.getString(0))
            val mappings = c.getString(1)
            assertTrue(mappings, "\"NINEGAG\":\"{title}\"" in mappings)
            assertTrue(mappings, "\"REDDIT\":\"{title}\"" in mappings)
            assertTrue(mappings, "\"YOUTUBE\":\"{text}\"" in mappings)
            assertTrue("Pinterest must no longer fill {caption}: $mappings", "PINTEREST" !in mappings)
            assertEquals(1, c.count)
        }
    }

    @Test
    fun migratingAllTheWayFrom1To5Works() {
        helper.createDatabase(database, 1).close()
        helper.runMigrationsAndValidate(
            database,
            5,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
        ).query("SELECT COUNT(*) FROM placeholder_keys").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    /**
     * 5 → 6 adds the hashtag list and the reserved {tags}. Every hashtag a
     * template used must land in the list, the template must keep its picks,
     * and an existing draft keeps a null selection — its text has its tags.
     */
    @Test
    fun migrate5To6CollectsTemplateHashtagsAndSeedsTags() {
        helper.createDatabase(database, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO templates
                  (id, name, body, tags, visibility, contentWarning, altTextMode,
                   staticAltText, accountId, isDefault, sortOrder, excludedSources)
                VALUES ('default', 'Meme', '{tags}', '#meme #cats', 'PUBLIC', NULL, 'NONE',
                        NULL, NULL, 1, 0, ''),
                       ('politics', 'Politics', '{tags}', '#politics #Meme', 'PUBLIC', NULL, 'NONE',
                        NULL, NULL, 0, 1, '')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO items
                  (id, mediaPath, originalMediaPath, mediaHash, mimeType, sourceUrl,
                   bodyText, altText, altTextFailed, contentWarning, visibility,
                   templateId, accountId, status, failureReason, createdAt, postedAt,
                   statusUrl, scheduledAt, origin, sourceFields)
                VALUES ('draft', NULL, NULL, NULL, NULL, NULL, '#meme #cats', NULL, 0, NULL,
                        'PUBLIC', 'default', NULL, 'DRAFT', NULL, 1700000000000, NULL, NULL,
                        NULL, NULL, '{}')
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(database, 6, true, AppDatabase.MIGRATION_5_6)

        db.query("SELECT tag FROM hashtags ORDER BY sortOrder").use { c ->
            val tags = mutableListOf<String>()
            while (c.moveToNext()) tags += c.getString(0)
            // #Meme is #meme again; each tag is listed once.
            assertEquals(listOf("#meme", "#cats", "#politics"), tags)
        }
        db.query("SELECT tags FROM templates WHERE id = 'politics'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("#politics #Meme", c.getString(0))
        }
        db.query("SELECT bodyText, hashtags, addSourceHashtags FROM items WHERE id = 'draft'").use { c ->
            assertTrue("the draft was lost", c.moveToFirst())
            assertEquals("#meme #cats", c.getString(0))
            assertNull(c.getString(1))
            assertEquals(1, c.getInt(2))
        }
        db.query("SELECT addSourceHashtags FROM templates").use { c ->
            while (c.moveToNext()) assertEquals("templates keep taking source hashtags", 1, c.getInt(0))
        }
        db.query("SELECT name, sortOrder FROM placeholder_keys WHERE id = 'tags'").use { c ->
            assertTrue("{tags} was not seeded", c.moveToFirst())
            assertEquals("tags", c.getString(0))
        }
    }

    @Test
    fun migratingAllTheWayFrom1To6Works() {
        helper.createDatabase(database, 1).close()
        helper.runMigrationsAndValidate(
            database,
            6,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
        ).query("SELECT COUNT(*) FROM placeholder_keys").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
    }
}
