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
package app.fediferry.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/** Bringing a data file from before the library was taken out up to date. */
class MigrationTest {

    private val dir: File = Files.createTempDirectory("fediferry-migration").toFile()
    private val file = File(dir, "fediferry.db")

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `library items no post used become drafts, and what went out is gone`() {
        File("src/main/sqldelight/databases/6.db").copyTo(file)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { st ->
                listOf(
                    "INSERT INTO project(id, name, created_at) VALUES ('p', 'Memes', 1)",
                    "INSERT INTO channel(project_id, id, type, name, instance, remote_account_id, acct, display_name, is_default, created_at, updated_at) " +
                        "VALUES ('p', 'c1', 'MASTODON', 'Memes', 'social.example', '77', 'memes', 'Memes', 1, 1, 1)",
                    "INSERT INTO media_asset(project_id, id, sha256, mime, bytes, created_at) VALUES ('p', 'a1', 'sha1', 'image/png', 10, 1)",
                    "INSERT INTO media_asset(project_id, id, sha256, mime, bytes, created_at) VALUES ('p', 'a2', 'sha2', 'image/png', 10, 1)",
                    "INSERT INTO media_asset(project_id, id, sha256, mime, bytes, created_at) VALUES ('p', 'a3', 'sha3', 'image/png', 10, 1)",
                    // Collected and never used: becomes a draft.
                    "INSERT INTO library_item(project_id, id, kind, asset_id, text, source_url, alt_suggestion, device_id, captured_at, created_at, updated_at) " +
                        "VALUES ('p', 'orphan', 'IMAGE', 'a1', 'So true', 'https://pin.it/x', 'A cat', 'phone', 5, 5, 6)",
                    // On a draft already: stays that draft.
                    "INSERT INTO library_item(project_id, id, kind, asset_id, captured_at, created_at, updated_at) VALUES ('p', 'used', 'IMAGE', 'a2', 1, 1, 1)",
                    // Published: goes, with its post.
                    "INSERT INTO library_item(project_id, id, kind, asset_id, captured_at, created_at, updated_at) VALUES ('p', 'out', 'IMAGE', 'a3', 1, 1, 1)",
                    // Deleted from the library: goes.
                    "INSERT INTO library_item(project_id, id, kind, source_url, captured_at, created_at, updated_at, deleted_at) VALUES ('p', 'gone', 'LINK', 'https://x', 1, 1, 1, 2)",
                    "INSERT INTO post(project_id, id, stage, body, created_at, updated_at) VALUES ('p', 'draft', 'DRAFT', 'Hello', 1, 1)",
                    "INSERT INTO post_media(project_id, post_id, position, asset_id, library_item_id) VALUES ('p', 'draft', 0, 'a2', 'used')",
                    "INSERT INTO post(project_id, id, stage, body, created_at, updated_at) VALUES ('p', 'published', 'PUBLISHED', 'Bye', 1, 1)",
                    "INSERT INTO post_media(project_id, post_id, position, asset_id, library_item_id) VALUES ('p', 'published', 0, 'a3', 'out')",
                    "INSERT INTO post_label(project_id, post_id, name) VALUES ('p', 'published', 'funny')",
                    "INSERT INTO publication(project_id, post_id, channel_id, publish_after, state, idempotency_key, created_at, updated_at) " +
                        "VALUES ('p', 'published', 'c1', 1, 'PUBLISHED', 'k', 1, 1)",
                    "INSERT INTO post(project_id, id, stage, body, created_at, updated_at, deleted_at) VALUES ('p', 'deleted', 'DRAFT', 'x', 1, 1, 2)",
                    "PRAGMA user_version = 6",
                ).forEach(st::execute)
            }
        }

        Storage.open(file).use { storage ->
            val db = storage.db
            val posts = db.postQueries.byStages("p", listOf("DRAFT", "READY", "SCHEDULED", "PUBLISHING", "FAILED", "PUBLISHED")).executeAsList()
            assertEquals(setOf("draft", "orphan"), posts.map { it.id }.toSet())

            val orphan = posts.single { it.id == "orphan" }
            assertEquals("So true\n\nhttps://pin.it/x", orphan.body)
            assertEquals("https://pin.it/x", orphan.source_url)
            assertEquals("c1", orphan.channel_id)
            assertEquals("phone", orphan.created_by)
            val media = db.postMediaQueries.forPost("p", "orphan").executeAsList().single()
            assertEquals("a1", media.asset_id)
            assertEquals("A cat", media.alt_text)
            assertEquals("a2", db.postMediaQueries.forPost("p", "draft").executeAsList().single().asset_id)

            assertTrue(db.postMediaQueries.forPost("p", "published").executeAsList().isEmpty())
            assertNull(db.publicationQueries.byPost("p", "published").executeAsOneOrNull())
            assertTrue(db.postLabelQueries.counts("p").executeAsList().isEmpty())

            // The published post's file is no post's now; the sweep takes it on the next round.
            val media2 = MediaStore(storage, dir, clock = { 10 })
            assertEquals(1, media2.sweep("p", graceMs = 0))
            assertNull(media2.byId("p", "a3"))
        }
    }
}
