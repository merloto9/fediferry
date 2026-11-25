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
package app.fediferry.phone

import app.fediferry.api.FollowerCountDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The phone project's follower count per channel and day, for Analyze:
 * Mastodon reports only today's. A small JSON file, not a table: a few
 * hundred numbers a year, read whole, and nothing else joins on them.
 */
class FollowerCounts(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** channel id → day → followers. */
    private suspend fun load(): MutableMap<String, MutableMap<String, Int>> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext mutableMapOf()
        runCatching { json.decodeFromString<Map<String, Map<String, Int>>>(file.readText()) }
            .getOrDefault(emptyMap())
            .mapValues { it.value.toMutableMap() }.toMutableMap()
    }

    private suspend fun save(all: Map<String, Map<String, Int>>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val next = File(file.parentFile, file.name + ".new")
        next.writeText(json.encodeToString(all))
        // Replaced in one step, so a crash mid-write never leaves half a file.
        next.renameTo(file)
    }

    suspend fun has(channelId: String, day: String): Boolean = mutex.withLock { load()[channelId]?.containsKey(day) == true }

    suspend fun record(channelId: String, day: String, followers: Int) = mutex.withLock {
        val all = load()
        all.getOrPut(channelId) { mutableMapOf() }[day] = followers
        save(all)
    }

    /** Oldest first, from [fromDay] on. */
    suspend fun since(channelId: String, fromDay: String): List<FollowerCountDto> = mutex.withLock {
        load()[channelId].orEmpty().filterKeys { it >= fromDay }.toSortedMap().map { FollowerCountDto(it.key, it.value) }
    }

    suspend fun forget(channelId: String) = mutex.withLock {
        val all = load()
        if (all.remove(channelId) != null) save(all)
    }
}
