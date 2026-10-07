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

import app.fediferry.data.ScheduledDraft
import app.fediferry.data.model.Visibility
import app.fediferry.mastodon.MediaAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledDraftMappingTest {

    @Test
    fun readsMastodonsVisibilityWords() {
        assertEquals(Visibility.PUBLIC, ScheduledDraft.visibilityOf("public"))
        assertEquals(Visibility.UNLISTED, ScheduledDraft.visibilityOf("unlisted"))
        assertEquals(Visibility.PRIVATE, ScheduledDraft.visibilityOf("private"))
        assertEquals(Visibility.DIRECT, ScheduledDraft.visibilityOf("direct"))
        assertNull(ScheduledDraft.visibilityOf("local"))
        assertNull(ScheduledDraft.visibilityOf(null))
    }

    @Test
    fun knowsTheMediaTypeFromTheFile() {
        fun type(url: String, kind: String = "image") = ScheduledDraft.mimeTypeOf(MediaAttachment("1", url = url, type = kind))
        assertEquals("image/png", type("https://f/x/original/1.PNG?v=2"))
        assertEquals("image/webp", type("https://f/1.webp"))
        assertEquals("image/jpeg", type("https://f/1.jpeg"))
        // An animated GIF is stored by Mastodon as a video.
        assertEquals("video/mp4", type("https://f/1.mp4", kind = "gifv"))
    }
}
