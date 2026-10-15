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
import app.fediferry.api.PlanModes
import app.fediferry.api.PlanRequest
import app.fediferry.api.PostImport
import app.fediferry.api.PostMediaImport
import app.fediferry.api.PostStages
import app.fediferry.api.ReviewFolderInput
import app.fediferry.client.ServerClient
import app.fediferry.client.ServerException
import app.fediferry.data.db.AppDatabase
import app.fediferry.data.model.Hashtags
import app.fediferry.log.DebugLog
import app.fediferry.phone.PhoneBackend
import app.fediferry.phone.PhonePost
import app.fediferry.phone.PhonePublication
import app.fediferry.upload.PendingShares
import app.fediferry.upload.UploadWorker
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Copies the posts this phone kept on its own into the connected project, as
 * they are: a draft stays a draft, a post in review stays in review with its
 * folder and labels, and a planned one is planned again on the server for
 * the same time. Text, hashtags, alt text and channel come along. Templates,
 * hashtags and the other settings go up through [ProjectSync].
 *
 * Each post is copied under an id made from its own, so copying again, or
 * after an interrupted upload, never makes a second post. The phone keeps its
 * copies, but for planned ones: those are taken out of the phone's queue
 * first, so nothing goes out twice.
 */
class LocalImporter(
    private val context: Context,
    private val db: AppDatabase,
    private val pending: PendingShares,
    private val phone: PhoneBackend,
) {

    private fun marker(projectId: String) = File(context.filesDir, "sync/$projectId-imported.txt")

    private fun imported(projectId: String): Set<String> =
        marker(projectId).takeIf { it.exists() }?.readLines()?.toSet().orEmpty()

    /** What the phone's own project holds that it has not sent: drafts, review, failures and the queue. */
    private suspend fun posts() = db.phone().posts(STAGES)

    /** Posts on this phone that are not in the project yet. */
    suspend fun waiting(projectId: String): Int {
        val done = imported(projectId)
        return posts().count { importId(it.id) !in done && !pending.has(importId(it.id)) }
    }

    /**
     * Copies every post not copied yet; returns how many arrived. [channels]
     * names, for each of the phone's channels, the project's channel it
     * became. A post that fails is left for the next try.
     */
    suspend fun importAll(client: ServerClient, projectId: String, channels: Map<String, String> = emptyMap()): Int {
        takeOutOfQueue(projectId)
        val planned = planned(projectId)
        val done = imported(projectId).toMutableSet()
        val folders = folderMap(client)
        var copied = 0
        try {
            for (post in posts()) {
                val id = importId(post.id)
                if (id in done || pending.has(id)) continue
                val ok = try {
                    copy(client, post, id, channels[post.channelId] ?: post.channelId, folders, planned[post.id])
                } catch (e: ServerException) {
                    // An older server without imports: the picture and link go up as a draft, as they used to.
                    if (e.status == 404 && e.args["what"] == null) {
                        fallback(post, id)
                        true
                    } else {
                        DebugLog.w(LOG, "Copying a post failed: ${e.code}")
                        if (e.unreachable) throw e
                        false
                    }
                }
                if (ok) {
                    done += id
                    copied++
                    marker(projectId).apply { parentFile?.mkdirs() }.writeText(done.joinToString("\n"))
                }
            }
        } finally {
            if (pending.all.value.isNotEmpty()) UploadWorker.enqueue(context)
        }
        return copied
    }

    /**
     * Takes the phone's planned posts out of its queue, keeping when each was
     * to go, so they can be planned for then on the server. Done before the
     * accounts move, so none fails for want of its account meanwhile. One
     * being sent already is left to go from here.
     */
    suspend fun takeOutOfQueue(projectId: String) {
        val planned = planned(projectId).toMutableMap()
        for (post in db.phone().posts(listOf(PostStages.SCHEDULED))) {
            val publication = runCatching { PhoneBackend.json.decodeFromString(PhonePublication.serializer(), post.publicationJson.orEmpty()) }.getOrNull()
            if (runCatching { phone.unplan(post.id) }.isFailure) continue
            (publication?.scheduledAt ?: publication?.slotAt ?: publication?.publishAfter)?.let { planned[post.id] = it }
        }
        plannedFile(projectId).apply { parentFile?.mkdirs() }.writeText(PhoneBackend.json.encodeToString(PLANNED, planned))
    }

    private fun plannedFile(projectId: String) = File(context.filesDir, "sync/$projectId-planned.json")

    private fun planned(projectId: String): Map<String, Long> =
        plannedFile(projectId).takeIf { it.exists() }
            ?.let { runCatching { PhoneBackend.json.decodeFromString(PLANNED, it.readText()) }.getOrNull() }
            .orEmpty()

    private suspend fun copy(client: ServerClient, post: PhonePost, id: String, channelId: String?, folders: Map<String, String>, plannedAt: Long?): Boolean {
        val media = runCatching { PhoneBackend.json.decodeFromString(PhoneBackend.MEDIA, post.mediaJson) }.getOrDefault(emptyList())
            .sortedBy { it.position }
            .mapNotNull { m ->
                val asset = db.phone().asset(m.assetId) ?: return@mapNotNull null
                val file = File(asset.path).takeIf { it.exists() } ?: return@mapNotNull null
                val remote = client.mediaBySha(asset.sha256) ?: client.uploadMedia(file.readBytes(), asset.mime)
                PostMediaImport(remote.id, m.altText, m.altFailed)
            }
        val draft = post.stage == PostStages.DRAFT
        val imported = client.importPost(
            id,
            PostImport(
                stage = if (draft) PostStages.DRAFT else PostStages.READY,
                channelId = channelId,
                body = post.body,
                hashtags = Hashtags.parse(post.hashtags),
                addSourceHashtags = post.addSourceHashtags,
                contentWarning = post.contentWarning,
                visibility = post.visibility,
                sourceUrl = post.sourceUrl,
                origin = post.origin,
                sourceFields = runCatching { PhoneBackend.json.decodeFromString(FIELDS, post.sourceFieldsJson) }.getOrDefault(emptyMap()),
                linkMayIdentify = post.linkMayIdentify,
                media = media,
                finalText = post.finalText.takeIf { !draft },
                readyAt = post.readyAt,
                reviewFolderId = post.reviewFolderId?.let(folders::get),
                labels = runCatching { PhoneBackend.json.decodeFromString(LABELS, post.labelsJson) }.getOrDefault(emptyList()),
                createdAt = post.createdAt,
            ),
        )
        // Planned on the phone: planned on the server for the same time. Should
        // that not work (the time has passed, no channel), it waits in review.
        val at = plannedAt
        if (at != null && imported.stage == PostStages.READY && imported.channelId != null) {
            val soon = System.currentTimeMillis() + PLAN_LEAD_MS
            runCatching { client.plan(imported.id, PlanRequest(PlanModes.AT, at = maxOf(at, soon))) }
                .onFailure { DebugLog.w(LOG, "Planning a copied post failed: ${(it as? ServerException)?.code ?: it.javaClass.simpleName}") }
        }
        return true
    }

    /** The phone's review folders as the project's, by name; the project gets those it lacks. */
    private suspend fun folderMap(client: ServerClient): Map<String, String> {
        val mine = db.phone().reviewFolders()
        if (mine.isEmpty()) return emptyMap()
        val theirs = runCatching { client.reviewFolders() }.getOrElse { return emptyMap() }.associateBy { it.name.trim().lowercase() }
        return mine.mapNotNull { folder ->
            val existing = theirs[folder.name.trim().lowercase()]
            val target = existing ?: runCatching { client.createReviewFolder(ReviewFolderInput(folder.name, folder.sortOrder)) }.getOrNull()
            target?.let { folder.id to it.id }
        }.toMap()
    }

    private suspend fun fallback(post: PhonePost, id: String) {
        val picture = runCatching { PhoneBackend.json.decodeFromString(PhoneBackend.MEDIA, post.mediaJson) }.getOrNull()
            ?.firstOrNull()?.let { db.phone().asset(it.assetId) }
        val file = picture?.path?.let(::File)?.takeIf { it.exists() }
        if (file == null && post.sourceUrl == null) return
        pending.addFile(id, post.sourceUrl, file, picture?.mime, post.createdAt)
    }

    private companion object {
        const val LOG = "import"
        val STAGES = listOf(PostStages.DRAFT, PostStages.READY, PostStages.FAILED, PostStages.SCHEDULED)
        val LABELS = ListSerializer(String.serializer())
        val FIELDS = MapSerializer(String.serializer(), String.serializer())
        val PLANNED = MapSerializer(String.serializer(), Long.serializer())

        /** A planned time that has passed by the time it arrives goes this long after. */
        const val PLAN_LEAD_MS = 2 * 60 * 1000L

        fun importId(postId: String) = "import-$postId"
    }
}
