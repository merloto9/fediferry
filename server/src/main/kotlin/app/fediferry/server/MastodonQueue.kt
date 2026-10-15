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
package app.fediferry.server

import app.fediferry.api.PlanModes
import app.fediferry.api.PlanRequest
import app.fediferry.api.PostDto
import app.fediferry.mastodon.MastodonAccounts
import app.fediferry.mastodon.MastodonException
import app.fediferry.mastodon.ScheduledPosts
import app.fediferry.mastodon.ScheduledStatus
import app.fediferry.link.MediaFetcher
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Posts a channel's instance holds for later — scheduled there from the
 * phone, from the original FediFerry app, or from Mastodon itself. The server
 * publishes its own posts at their time and never hands one to the instance,
 * but these are still on their way, so the queue shows them, marked as the
 * instance's. They can be moved there, or taken back into review; once back,
 * the server publishes them like any other.
 */
class MastodonQueue(
    private val storage: Storage,
    private val channels: Channels,
    private val mastodon: MastodonAccounts,
    private val posts: Posts,
    private val media: MediaStore,
    private val fetcher: MediaFetcher,
    private val clock: () -> Long,
) {
    private val db get() = storage.db
    private val log = LoggerFactory.getLogger("mastodon-queue")

    /** The times each channel's instance held posts for when last read, per project. */
    private val held = ConcurrentHashMap<String, Map<String, Set<Instant>>>()

    /** Late-bound: publishing needs the held times, and this needs publishing to plan. */
    lateinit var publishing: Publishing

    /**
     * Every channel's posts held by its instance. A channel whose instance
     * cannot be read is left out; the rest still show.
     */
    suspend fun list(projectId: String): List<PostDto> = coroutineScope {
        val reads = db.channelQueries.all(projectId).executeAsList().filter { it.acct.isNotEmpty() }.map { channel ->
            async {
                val token = channels.token(projectId, channel.id) ?: return@async channel.id to emptyList<ScheduledStatus>()
                channel.id to runCatching { mastodon.scheduledStatuses(channel.instance, token) }
                    .onFailure { log.info("Reading what an instance holds failed: {}", it.javaClass.simpleName) }
                    .getOrDefault(emptyList())
            }
        }.awaitAll()
        held[projectId] = reads.associate { (channelId, statuses) -> channelId to statuses.mapNotNull { timeOf(it) }.toSet() }
        reads.flatMap { (channelId, statuses) -> statuses.map { ScheduledPosts.dto(channelId, it) } }
    }

    /** The times [channelId]'s instance held posts for when last read; those slots are taken. */
    fun heldTimes(projectId: String, channelId: String): Set<Instant> = held[projectId]?.get(channelId).orEmpty()

    suspend fun get(projectId: String, channelId: String, statusId: String): PostDto {
        val (instance, token) = account(projectId, channelId)
        val status = try {
            mastodon.scheduledStatus(instance, token, statusId)
        } catch (e: MastodonException) {
            throw if (e.code == 404) ApiException.notFound("post") else ApiException(HttpStatusCode.BadGateway, e.message ?: "mastodon.error")
        }
        return ScheduledPosts.dto(channelId, status)
    }

    /**
     * Moves a held post: to a time or the next free slot, by the instance's
     * own reschedule. "Now" cannot be done there; the post is taken back and
     * the server publishes it.
     */
    suspend fun plan(projectId: String, channelId: String, statusId: String, request: PlanRequest): PostDto {
        if (request.mode == PlanModes.NOW) {
            val back = takeBack(projectId, channelId, statusId)
            return publishing.plan(projectId, back.id, request)
        }
        val (instance, token) = account(projectId, channelId)
        val current = runCatching { timeOf(mastodon.scheduledStatus(instance, token, statusId)) }.getOrNull()
        val at = when (request.mode) {
            PlanModes.AT -> request.at ?: throw ApiException.badRequest("at")
            PlanModes.NEXT_SLOT -> publishing.nextSlot(projectId, channelId, request.scheduleId, freeing = current).second.toEpochMilli()
            else -> throw ApiException.badRequest("mode")
        }
        if (at - clock() < ScheduledPosts.MIN_LEAD_MS) throw ApiException(HttpStatusCode.UnprocessableEntity, "plan.too_soon")
        val moved = remote { mastodon.reschedule(instance, token, statusId, Instant.ofEpochMilli(at).toString()) }
        list(projectId)
        return ScheduledPosts.dto(channelId, moved)
    }

    /**
     * Takes a held post back into review here: its text and pictures. The
     * pictures are fetched first, and the post exists here only once the
     * instance has let go of it — otherwise it could go out twice.
     */
    suspend fun takeBack(projectId: String, channelId: String, statusId: String): PostDto {
        val (instance, token) = account(projectId, channelId)
        val status = try {
            mastodon.scheduledStatus(instance, token, statusId)
        } catch (e: MastodonException) {
            throw if (e.code == 404) ApiException.notFound("post") else ApiException(HttpStatusCode.BadGateway, e.message ?: "mastodon.error")
        }
        val pictures = status.media.map { attachment ->
            val url = attachment.url ?: attachment.previewUrl ?: throw ApiException(HttpStatusCode.BadGateway, "mastodon.media")
            val bytes = fetcher.fetch(url).getOrElse { throw ApiException(HttpStatusCode.BadGateway, "mastodon.media") }
            val asset = runCatching { media.store(projectId, bytes, ScheduledPosts.mimeTypeOf(attachment)) }
                .getOrElse { throw ApiException(HttpStatusCode.UnprocessableEntity, "media.store") }
            asset to attachment.description?.ifBlank { null }
        }
        try {
            mastodon.cancelScheduled(instance, token, statusId)
        } catch (e: MastodonException) {
            // Gone already: posted, or deleted there. Nothing to take back.
            throw if (e.code == 404) ApiException.notFound("post") else ApiException(HttpStatusCode.BadGateway, e.message ?: "mastodon.error")
        }
        val post = posts.createReady(
            projectId, channelId, status.params.text,
            status.params.spoilerText?.ifBlank { null },
            ScheduledPosts.visibilityOf(status.params.visibility) ?: "PUBLIC",
            pictures,
        )
        list(projectId)
        return post
    }

    private fun account(projectId: String, channelId: String): Pair<String, String> {
        val channel = db.channelQueries.byId(projectId, channelId).executeAsOneOrNull() ?: throw ApiException.notFound("channel")
        val token = channels.token(projectId, channelId) ?: throw ApiException(HttpStatusCode.Conflict, "channel.no_account")
        return channel.instance to token
    }

    private fun timeOf(status: ScheduledStatus): Instant? = runCatching { Instant.parse(status.scheduledAt) }.getOrNull()

    private suspend fun <T> remote(block: suspend () -> T): T = try {
        block()
    } catch (e: MastodonException) {
        throw ApiException(HttpStatusCode.BadGateway, e.message ?: "mastodon.error")
    }
}
