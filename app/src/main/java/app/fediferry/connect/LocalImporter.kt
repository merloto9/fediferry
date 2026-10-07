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
package app.fediferry.connect

import android.content.Context
import app.fediferry.data.db.AppDatabase
import app.fediferry.library.PendingShares
import app.fediferry.library.UploadWorker
import java.io.File

/**
 * Copies the posts this phone kept on its own into the connected project's
 * library: their pictures and links, and their stacks as folders. Templates,
 * hashtags and the other settings go up through [ProjectSync] by themselves.
 *
 * Each post is copied under an id made from its own, so copying again, or
 * after an interrupted upload, never makes a second item.
 */
class LocalImporter(private val context: Context, private val db: AppDatabase, private val pending: PendingShares) {

    private fun marker(projectId: String) = File(context.filesDir, "sync/$projectId-imported.txt")

    private fun imported(projectId: String): Set<String> =
        marker(projectId).takeIf { it.exists() }?.readLines()?.toSet().orEmpty()

    /** Posts on this phone that are not in the project's library yet. */
    suspend fun waiting(projectId: String): Int {
        val done = imported(projectId)
        return db.items().unposted().count { "import-${it.id}" !in done && !pending.has("import-${it.id}") }
    }

    /** Queues them for upload; returns how many were queued. */
    suspend fun importAll(projectId: String): Int {
        val done = imported(projectId).toMutableSet()
        val stacks = db.stacks().all().associate { it.id to it.name }
        var queued = 0
        for (item in db.items().unposted()) {
            val id = "import-${item.id}"
            if (id in done || pending.has(id)) continue
            val file = item.mediaPath?.let(::File)?.takeIf { it.exists() }
            if (file == null && item.sourceUrl == null) continue
            pending.addFile(id, item.sourceUrl, file, item.mimeType, item.createdAt, item.stackId?.let { stacks[it] })
            done += id
            queued++
        }
        marker(projectId).apply { parentFile?.mkdirs() }.writeText(done.joinToString("\n"))
        if (queued > 0) UploadWorker.enqueue(context)
        return queued
    }
}
