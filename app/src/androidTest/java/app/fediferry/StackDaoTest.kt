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
import app.fediferry.data.db.AppDatabase
import app.fediferry.data.model.InboxStack
import app.fediferry.data.model.Item
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** In memory, so the app's own data on the device is never touched. */
@RunWith(AndroidJUnit4::class)
class StackDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).build()

    @After fun close() = db.close()

    @Test
    fun movingPostsPutsThemOnTheStackAndBackInNew() = runBlocking {
        db.items().upsert(Item(id = "a", templateId = "t"))
        db.items().upsert(Item(id = "b", templateId = "t"))
        db.stacks().upsert(InboxStack("ready", "Ready to post"))

        db.stacks().move(listOf("a", "b"), "ready")
        assertEquals("ready", db.items().byId("a")!!.stackId)

        db.stacks().move(listOf("b"), null)
        assertNull(db.items().byId("b")!!.stackId)
    }

    @Test
    fun deletingAStackSendsItsPostsBackToNewInsteadOfLosingThem() = runBlocking {
        db.items().upsert(Item(id = "a", templateId = "t", stackId = "ready"))
        db.stacks().upsert(InboxStack("ready", "Ready to post"))

        db.stacks().delete("ready")

        assertEquals(0, db.stacks().count())
        assertNull("the post is still there, on no stack", db.items().byId("a")!!.stackId)
    }
}
