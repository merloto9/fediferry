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

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fediferry.data.ItemRepository
import app.fediferry.data.MediaVault
import app.fediferry.data.db.AppDatabase
import app.fediferry.data.model.Status
import app.fediferry.data.model.Visibility
import app.fediferry.link.MediaFetcher
import app.fediferry.mastodon.MediaAttachment
import app.fediferry.mastodon.ScheduledParams
import app.fediferry.mastodon.ScheduledStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** In memory, with the picture served locally, so nothing leaves the device. */
@RunWith(AndroidJUnit4::class)
class ScheduledDraftTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val picture = byteArrayOf(1, 2, 3, 4, 5)
    private var failDownload = false

    private val repo = ItemRepository(
        db.items(), db.templates(), db.accounts(), MediaVault(context),
        fetcher = MediaFetcher { if (failDownload) Result.failure(IllegalStateException("offline")) else Result.success(picture) },
    )

    private val scheduled = ScheduledStatus(
        id = "7",
        scheduledAt = "2026-10-09T12:00:00Z",
        params = ScheduledParams(
            text = "Look at this\n\n#meme #cat\n\nvia https://9gag.com/gag/x",
            spoilerText = "spiders",
            visibility = "unlisted",
        ),
        media = listOf(MediaAttachment(id = "1", url = "https://files.example/1.png", description = "A cat", type = "image")),
    )

    @After fun close() = db.close()

    @Test
    fun copiesEverythingTheScheduledPostCarries() = runBlocking {
        val draft = repo.draftFromScheduled("m.social/me", scheduled).getOrThrow()

        val stored = db.items().byId(draft.id)!!
        assertEquals(Status.DRAFT, stored.status)
        assertEquals("Look at this\n\n{tags}\n\nvia https://9gag.com/gag/x", stored.bodyText)
        assertEquals(listOf("#meme", "#cat"), stored.hashtagList)
        assertEquals("spiders", stored.contentWarning)
        assertEquals(Visibility.UNLISTED, stored.visibility)
        assertEquals("A cat", stored.altText)
        assertEquals("image/png", stored.mimeType)
        assertEquals("m.social/me", stored.accountId)
        assertNotNull(stored.mediaPath)
        assertTrue(repo.mediaBytes(stored)!!.contentEquals(picture))
    }

    @Test
    fun theSamePictureTwiceIsOneDraft() = runBlocking {
        val first = repo.draftFromScheduled("a", scheduled).getOrThrow()
        val second = repo.draftFromScheduled("a", scheduled).getOrThrow()
        assertEquals(first.id, second.id)
    }

    @Test
    fun aFailedDownloadMakesNoDraft() = runBlocking {
        failDownload = true
        assertTrue(repo.draftFromScheduled("a", scheduled).isFailure)
        assertEquals(0, db.items().observeInbox().first().size)
    }
}
