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

import app.fediferry.api.MediaAssetDto
import app.fediferry.api.PostDto
import app.fediferry.api.PostMediaDto
import app.fediferry.api.PostMediaPatch
import app.fediferry.drafts.DraftEditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftDiffTest {

    private val asset = MediaAssetDto(id = "a", sha256 = "s", mime = "image/png", bytes = 1)
    private val post = PostDto(
        id = "p", stage = "DRAFT", version = 3, body = "{tags}\n\nvia x", hashtags = listOf("#meme"),
        contentWarning = "spiders", media = listOf(PostMediaDto(0, asset, altText = "A cat")),
        createdAt = 0, updatedAt = 0,
    )

    @Test
    fun `nothing changed sends nothing`() = assertNull(DraftEditorViewModel.diff(post, post.copy()))

    @Test
    fun `only what changed is sent`() {
        val patch = DraftEditorViewModel.diff(post, post.copy(body = "hi", hashtags = listOf("#meme", "#cat")))!!
        assertEquals("hi", patch.body)
        assertEquals(listOf("#meme", "#cat"), patch.hashtags)
        assertNull(patch.visibility)
        assertNull(patch.contentWarning)
        assertNull(patch.media)
    }

    @Test
    fun `a cleared content warning is sent as empty`() =
        assertEquals("", DraftEditorViewModel.diff(post, post.copy(contentWarning = null))!!.contentWarning)

    @Test
    fun `an alt text edit names its picture`() = assertEquals(
        listOf(PostMediaPatch(0, altText = "A frog")),
        DraftEditorViewModel.diff(post, post.copy(media = listOf(PostMediaDto(0, asset, altText = "A frog"))))!!.media,
    )
}
