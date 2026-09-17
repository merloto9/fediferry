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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fediferry.data.ItemRepository
import app.fediferry.data.db.AppDatabase
import app.fediferry.data.db.ItemDao
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.data.model.Template
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The two-step Instagram share: the permalink arrives first, the screenshot
 * second. These cover which draft each half is allowed to join.
 */
@RunWith(AndroidJUnit4::class)
class PairingTest {

    private lateinit var db: AppDatabase
    private lateinit var items: ItemDao

    private val now get() = System.currentTimeMillis()
    private val window get() = now - ItemRepository.PAIRING_WINDOW_MS

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        items = db.items()
    }

    @After
    fun tearDown() = db.close()

    private fun item(
        media: String? = null,
        link: String? = null,
        status: Status = Status.DRAFT,
        createdAt: Long = System.currentTimeMillis(),
    ) = Item(
        id = UUID.randomUUID().toString(),
        mediaPath = media,
        mediaHash = media?.let { "hash-$it" },
        sourceUrl = link,
        bodyText = "#meme",
        templateId = Template.DEFAULT_ID,
        status = status,
        createdAt = createdAt,
    )

    @Test
    fun aLinkOnlyDraftIsOfferedToAnArrivingScreenshot() = runBlocking {
        val link = item(link = "https://instagram.com/p/abc")
        items.upsert(link)
        assertEquals(link.id, items.latestAwaitingMedia(window)?.id)
    }

    @Test
    fun aScreenshotOnlyDraftIsOfferedToAnArrivingLink() = runBlocking {
        val shot = item(media = "/data/media/a.jpg")
        items.upsert(shot)
        assertEquals(shot.id, items.latestAwaitingLink(window)?.id)
    }

    @Test
    fun aCompleteDraftIsNeverOfferedToEitherHalf() = runBlocking {
        items.upsert(item(media = "/data/media/a.jpg", link = "https://instagram.com/p/abc"))
        assertNull(items.latestAwaitingMedia(window))
        assertNull(items.latestAwaitingLink(window))
    }

    @Test
    fun aDraftOlderThanTheWindowIsLeftAlone() = runBlocking {
        items.upsert(
            item(
                link = "https://instagram.com/p/old",
                createdAt = now - ItemRepository.PAIRING_WINDOW_MS - 60_000,
            ),
        )
        assertNull(items.latestAwaitingMedia(window))
    }

    @Test
    fun anItemAlreadyOnItsWayOutIsNotPairedInto() = runBlocking {
        // Queued, posting and posted items are committed; joining a screenshot
        // onto one would change a post that is already being sent.
        for (status in listOf(Status.QUEUED, Status.POSTING, Status.POSTED, Status.FAILED)) {
            items.upsert(item(link = "https://instagram.com/p/$status", status = status))
        }
        assertNull(items.latestAwaitingMedia(window))
    }

    @Test
    fun theNewestCandidateWins() = runBlocking {
        items.upsert(item(link = "https://instagram.com/p/older", createdAt = now - 60_000))
        val newer = item(link = "https://instagram.com/p/newer", createdAt = now)
        items.upsert(newer)
        assertEquals(newer.id, items.latestAwaitingMedia(window)?.id)
    }

    // --- re-sharing something already dealt with ---------------------------

    @Test
    fun aDraftWithTheSameBytesIsFoldedInto() = runBlocking {
        val draft = item(media = "/data/media/a.jpg")
        items.upsert(draft)
        assertEquals(draft.id, items.draftByMediaHash("hash-/data/media/a.jpg")?.id)
    }

    @Test
    fun anItemThatAlreadyWentOutIsNotReopened() = runBlocking {
        // Re-sharing a meme that was already posted must start a new draft, not
        // reopen the post that went out. Matching on anything but DRAFT made
        // sharing a 9GAG repost silently open the old, already-sent item.
        for (status in listOf(Status.POSTED, Status.QUEUED, Status.POSTING, Status.FAILED)) {
            items.upsert(item(media = "/data/media/$status.jpg", status = status))
            assertNull(
                "a $status item should not be folded into",
                items.draftByMediaHash("hash-/data/media/$status.jpg"),
            )
        }
    }

    @Test
    fun aSentItemStillCountsAsReferencingItsMedia() = runBlocking {
        // Deleting a draft must not delete media a posted item still points at.
        val posted = item(media = "/data/media/shared.jpg", status = Status.POSTED)
        items.upsert(posted)
        assertEquals(posted.id, items.anyByMediaHash("hash-/data/media/shared.jpg")?.id)
    }
}
