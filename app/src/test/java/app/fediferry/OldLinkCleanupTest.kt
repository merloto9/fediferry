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
import app.fediferry.ui.summary
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
        assertFalse(OldLinkCleanup.isCandidate(item.copy(sourceUrl = null)))
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
    fun theSummarySaysWhatHappened() {
        assertEquals(
            "2 links cleaned.\n1 link couldn't be confirmed and was kept as shared. " +
                "The editor shows a warning on it.\n1 link already clean.",
            summary(OldLinkCleanup.Result(cleaned = 2, kept = 1, alreadyClean = 1)),
        )
        assertEquals("Nothing needed changing.", summary(OldLinkCleanup.Result()))
    }
}
