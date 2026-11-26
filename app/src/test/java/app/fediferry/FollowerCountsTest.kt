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

import app.fediferry.api.FollowerCountDto
import app.fediferry.phone.FollowerCounts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FollowerCountsTest {

    private val dir: File = Files.createTempDirectory("follower-counts").toFile()
    private val file = File(dir, "insights/followers.json")

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `counts are kept per channel and day, and survive a new instance`() = runBlocking {
        val counts = FollowerCounts(file)
        counts.record("a", "2026-11-25", 100)
        counts.record("a", "2026-11-26", 104)
        counts.record("a", "2026-11-26", 105)
        counts.record("b", "2026-11-26", 7)

        val again = FollowerCounts(file)
        assertEquals(listOf(FollowerCountDto("2026-11-26", 105)), again.since("a", "2026-11-26"))
        assertEquals(2, again.since("a", "2026-01-01").size)
        assertTrue(again.has("b", "2026-11-26"))
        assertFalse(again.has("b", "2026-11-25"))
    }

    @Test
    fun `a channel that goes takes its counts along`() = runBlocking {
        val counts = FollowerCounts(file)
        counts.record("a", "2026-11-26", 100)
        counts.record("b", "2026-11-26", 7)
        counts.forget("a")
        assertTrue(counts.since("a", "2000-01-01").isEmpty())
        assertEquals(1, counts.since("b", "2000-01-01").size)
    }

    @Test
    fun `an unreadable file is a fresh start, not a crash`() = runBlocking {
        file.parentFile!!.mkdirs()
        file.writeText("not json")
        val counts = FollowerCounts(file)
        assertTrue(counts.since("a", "2000-01-01").isEmpty())
        counts.record("a", "2026-11-26", 1)
        assertEquals(1, counts.since("a", "2000-01-01").size)
    }
}
