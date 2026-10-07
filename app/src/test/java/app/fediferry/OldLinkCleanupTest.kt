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

import app.fediferry.data.OldLinkCleanup
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OldLinkCleanupTest {

    private val link = "https://pin.it/abc?invite_code=xyz&sender=me"

    @Test
    fun onlyPostsStillInTheUsersHandsAreCandidates() {
        val item = Item(id = "a", templateId = "t", sourceUrl = link)
        assertTrue(OldLinkCleanup.isCandidate(item.copy(status = Status.DRAFT)))
        assertTrue(OldLinkCleanup.isCandidate(item.copy(status = Status.FAILED)))
        assertFalse(OldLinkCleanup.isCandidate(item.copy(status = Status.QUEUED)))
        assertFalse(OldLinkCleanup.isCandidate(item.copy(status = Status.POSTING)))
        assertFalse(OldLinkCleanup.isCandidate(item.copy(status = Status.POSTED)))
        // A link typed into the text counts as much as the one it was shared with.
        assertTrue(OldLinkCleanup.isCandidate(item.copy(sourceUrl = null)))
    }

    @Test
    fun anEditedBodyGetsTheCleanLinkToo() {
        val body = "Look at this\n\nvia $link"
        assertEquals(
            "Look at this\n\nvia https://www.pinterest.com/pin/1/",
            OldLinkCleanup.rewriteBody(body, link, "https://www.pinterest.com/pin/1/"),
        )
    }

    @Test
    fun findsEveryLinkInTheTextWithoutTheSentenceAroundIt() {
        val text = "Saw this (https://pin.it/abc). Also https://www.reddit.com/r/memes/s/xyz, " +
            "and <https://example.com/a?b=c>!\nhttp://9gag.com/gag/q"
        assertEquals(
            listOf(
                "https://pin.it/abc",
                "https://www.reddit.com/r/memes/s/xyz",
                "https://example.com/a?b=c",
                "http://9gag.com/gag/q",
            ),
            OldLinkCleanup.linksIn(text),
        )
        assertEquals(emptyList<String>(), OldLinkCleanup.linksIn("no links, just https:// on its own"))
    }

    @Test
    fun swapsWholeLinksOnly() {
        val body = "one https://pin.it/abc, two https://pin.it/abcdef and https://pin.it/abc"
        assertEquals(
            "one https://clean/1, two https://pin.it/abcdef and https://clean/1",
            OldLinkCleanup.rewriteBody(body, "https://pin.it/abc", "https://clean/1"),
        )
    }
}
