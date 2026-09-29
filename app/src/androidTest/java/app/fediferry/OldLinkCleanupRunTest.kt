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
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.link.CleanedLink
import app.fediferry.link.LinkResolver
import app.fediferry.link.ResolvedPost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** In memory, with a stand-in for Pinterest, so nothing leaves the device. */
@RunWith(AndroidJUnit4::class)
class OldLinkCleanupRunTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    private val tracked = "https://pin.it/tracked"
    private val unconfirmed = "https://pin.it/unconfirmed"
    private val clean = "https://www.pinterest.com/pin/1/"
    private val trackedToo = "https://pin.it/tracked2"
    private val cleanToo = "https://www.pinterest.com/pin/2/"

    private val pinterest = object : LinkResolver {
        override val source = ContentSource.PINTEREST
        override val cleansLinks = true
        override fun handles(url: String) = "pin" in url && "9gag" !in url
        override suspend fun resolve(url: String): Result<ResolvedPost> = Result.failure(UnsupportedOperationException())
        override suspend fun cleanLink(url: String): CleanedLink = when (url) {
            tracked -> CleanedLink.Clean(clean)
            trackedToo -> CleanedLink.Clean(cleanToo)
            unconfirmed -> CleanedLink.MayIdentify("test")
            else -> CleanedLink.Unchanged
        }
    }

    private val repo = ItemRepository(
        db.items(), db.templates(), db.accounts(), MediaVault(context),
        resolvers = listOf(pinterest),
    )

    @After fun close() = db.close()

    @Test
    fun aLinkAddedToTheTextIsCleanedToo() = runBlocking {
        // Shared as a clean link, then a tracked one pasted into the text by hand.
        db.items().upsert(
            Item(id = "added", templateId = "t", sourceUrl = clean, bodyText = "via $clean\nalso $trackedToo."),
        )
        // No shared link at all, and a source that cleans nothing.
        db.items().upsert(
            Item(id = "typed", templateId = "t", sourceUrl = "https://9gag.com/gag/x", bodyText = "see $unconfirmed"),
        )

        val result = repo.cleanOldLinks()

        assertEquals("via $clean\nalso $cleanToo.", db.items().byId("added")!!.bodyText)
        assertEquals("see $unconfirmed", db.items().byId("typed")!!.bodyText)
        assertEquals(1, result.cleaned)
        assertEquals(1, result.kept)
        assertEquals(1, result.alreadyClean)
    }

    @Test
    fun cleansInboxLinksAndLeavesTheRestAlone() = runBlocking {
        db.items().upsert(Item(id = "edited", templateId = "t", sourceUrl = tracked, bodyText = "mine: $tracked"))
        db.items().upsert(Item(id = "unsure", templateId = "t", sourceUrl = unconfirmed))
        db.items().upsert(Item(id = "done", templateId = "t", sourceUrl = clean))
        db.items().upsert(Item(id = "sent", templateId = "t", sourceUrl = tracked, status = Status.POSTED))
        db.items().upsert(Item(id = "other", templateId = "t", sourceUrl = "https://9gag.com/gag/x"))

        val result = repo.cleanOldLinks()

        assertEquals(1, result.cleaned)
        assertEquals(1, result.kept)
        assertEquals(1, result.alreadyClean)
        db.items().byId("edited")!!.let {
            assertEquals(clean, it.sourceUrl)
            assertEquals("mine: $clean", it.bodyText)
            assertFalse(it.linkMayIdentify)
        }
        assertTrue(db.items().byId("unsure")!!.linkMayIdentify)
        assertEquals(tracked, db.items().byId("sent")!!.sourceUrl)
    }
}
