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

import app.fediferry.mastodon.MediaAttachment
import app.fediferry.mastodon.ScheduledParams
import app.fediferry.mastodon.ScheduledPosts
import app.fediferry.mastodon.ScheduledStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledPostsTest {

    @Test
    fun readsMastodonsVisibilityWords() {
        assertEquals("PUBLIC", ScheduledPosts.visibilityOf("public"))
        assertEquals("UNLISTED", ScheduledPosts.visibilityOf("unlisted"))
        assertEquals("PRIVATE", ScheduledPosts.visibilityOf("private"))
        assertEquals("DIRECT", ScheduledPosts.visibilityOf("direct"))
        assertNull(ScheduledPosts.visibilityOf("local"))
        assertNull(ScheduledPosts.visibilityOf(null))
    }

    @Test
    fun knowsTheMediaTypeFromTheFile() {
        fun type(url: String, kind: String = "image") = ScheduledPosts.mimeTypeOf(MediaAttachment("1", url = url, type = kind))
        assertEquals("image/png", type("https://f/x/original/1.PNG?v=2"))
        assertEquals("image/webp", type("https://f/1.webp"))
        assertEquals("image/jpeg", type("https://f/1.jpeg"))
        // An animated GIF is stored by Mastodon as a video.
        assertEquals("video/mp4", type("https://f/1.mp4", kind = "gifv"))
    }

    @Test
    fun aHeldPostsIdNamesItsChannelAndIsNeverAPostsOwn() {
        val id = ScheduledPosts.RemoteId.encode("mastodon.social/memes", "1099")
        assertEquals("mastodon.social/memes" to "1099", ScheduledPosts.RemoteId.parse(id))
        assertNull(ScheduledPosts.RemoteId.parse("3f2b8c1e-0b7a-4c55-9a51-7d1c2c9d0e11"))
    }

    @Test
    fun aHeldPostShowsAsTheInstancesAtItsTime() {
        val dto = ScheduledPosts.dto(
            "c1",
            ScheduledStatus("9", "2026-10-20T08:00:00.000Z", ScheduledParams("Hello #meme", "cw", "unlisted"), listOf(MediaAttachment("m", url = "https://f/1.png", description = "A cat"))),
        )
        assertTrue(dto.publication!!.heldByInstance)
        assertEquals(java.time.Instant.parse("2026-10-20T08:00:00Z").toEpochMilli(), dto.publication!!.publishAfter)
        assertEquals("UNLISTED", dto.visibility)
        assertEquals("A cat", dto.media.single().altText)
    }
}
