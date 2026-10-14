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
import app.fediferry.upload.PendingShares
import app.fediferry.upload.UploadWorker
import app.fediferry.api.PostStages
import app.fediferry.phone.PhoneBackend
import java.io.File

/**
 * Copies the posts this phone kept on its own into the connected project as
 * drafts: their pictures and links. Templates, hashtags and the other
 * settings go up through [ProjectSync] by themselves.
 *
 * Each post is copied under an id made from its own, so copying again, or
 * after an interrupted upload, never makes a second draft.
 */
class LocalImporter(private val context: Context, private val db: AppDatabase, private val pending: PendingShares) {

    private fun marker(projectId: String) = File(context.filesDir, "sync/$projectId-imported.txt")

    private fun imported(projectId: String): Set<String> =
        marker(projectId).takeIf { it.exists() }?.readLines()?.toSet().orEmpty()

    /** What the phone's own project holds that is not on its way out: drafts, review, failures. */
    private suspend fun posts() = db.phone().posts(listOf(PostStages.DRAFT, PostStages.READY, PostStages.FAILED))

    /** Posts on this phone that are not in the project yet. */
    suspend fun waiting(projectId: String): Int {
        val done = imported(projectId)
        return posts().count { "import-${it.id}" !in done && !pending.has("import-${it.id}") }
    }

    /** Queues them for upload; returns how many were queued. */
    suspend fun importAll(projectId: String): Int {
        val done = imported(projectId).toMutableSet()
        var queued = 0
        for (post in posts()) {
            val id = "import-${post.id}"
            if (id in done || pending.has(id)) continue
            val picture = runCatching { PhoneBackend.json.decodeFromString(PhoneBackend.MEDIA, post.mediaJson) }.getOrNull()
                ?.firstOrNull()?.let { db.phone().asset(it.assetId) }
            val file = picture?.path?.let(::File)?.takeIf { it.exists() }
            if (file == null && post.sourceUrl == null) continue
            pending.addFile(id, post.sourceUrl, file, picture?.mime, post.createdAt)
            done += id
            queued++
        }
        marker(projectId).apply { parentFile?.mkdirs() }.writeText(done.joinToString("\n"))
        if (queued > 0) UploadWorker.enqueue(context)
        return queued
    }
}
