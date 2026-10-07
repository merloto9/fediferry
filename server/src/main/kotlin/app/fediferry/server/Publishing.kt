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
import app.fediferry.api.PostStages
import app.fediferry.api.PublicationDto
import app.fediferry.api.PublicationStates
import app.fediferry.api.ScheduleDto
import app.fediferry.api.ScheduleInput
import app.fediferry.api.SettingsKinds
import app.fediferry.api.SlotDto
import app.fediferry.channel.ChannelPublisher
import app.fediferry.channel.PublishJob
import app.fediferry.channel.PublishMedia
import app.fediferry.data.model.Hashtags
import app.fediferry.mastodon.MastodonException
import app.fediferry.schedule.Slot
import app.fediferry.schedule.SlotPlanner
import app.fediferry.server.db.Publication
import app.fediferry.server.db.Schedule
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Upload schedules and the way out. A ready post is planned — now, at a
 * time, or into the next free slot of its channel's schedules — and the
 * [run] loop publishes it once its time has come: with the same key on every
 * try, again later when the instance was busy, and never twice.
 */
class Publishing(
    private val storage: Storage,
    private val feed: ChangeFeed,
    private val posts: Posts,
    private val channels: Channels,
    private val media: MediaStore,
    private val settings: SettingsStore,
    private val publisher: ChannelPublisher,
    private val clock: () -> Long,
) {
    private val db get() = storage.db
    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger("publishing")
    private val sending = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // --- schedules ------------------------------------------------------------------

    fun schedules(projectId: String): List<ScheduleDto> = db.scheduleQueries.all(projectId).executeAsList().map { dto(projectId, it) }

    fun createSchedule(projectId: String, input: ScheduleInput): ScheduleDto {
        val channelId = input.channelId ?: throw ApiException.badRequest("channelId")
        channels.get(projectId, channelId)
        val zone = zoneOf(input.timezone ?: "UTC")
        val slots = validSlots(input.slots.orEmpty())
        val id = UUID.randomUUID().toString()
        val now = clock()
        feed.change(projectId, "schedule", id) {
            db.scheduleQueries.insert(
                projectId, id, channelId, input.name?.trim()?.ifEmpty { null } ?: "Schedule", zone.id,
                if (input.active != false) 1 else 0, slotsJson(slots), now, now,
            )
        }
        return dto(projectId, db.scheduleQueries.byId(projectId, id).executeAsOne())
    }

    fun updateSchedule(projectId: String, id: String, input: ScheduleInput): ScheduleDto {
        val row = db.scheduleQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("schedule")
        input.channelId?.let { channels.get(projectId, it) }
        feed.change(projectId, "schedule", id) {
            db.scheduleQueries.update(
                input.channelId ?: row.channel_id,
                input.name?.trim()?.ifEmpty { null } ?: row.name,
                input.timezone?.let { zoneOf(it).id } ?: row.timezone,
                input.active?.let { if (it) 1L else 0L } ?: row.active,
                input.slots?.let { slotsJson(validSlots(it)) } ?: row.slots_json,
                clock(), projectId, id,
            )
        }
        return dto(projectId, db.scheduleQueries.byId(projectId, id).executeAsOne())
    }

    /** Deletes a schedule. Posts already planned into its slots keep their times. */
    fun deleteSchedule(projectId: String, id: String) {
        db.scheduleQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("schedule")
        feed.change(projectId, "schedule", id, deleted = true) { db.scheduleQueries.delete(projectId, id) }
    }

    private fun dto(projectId: String, row: Schedule): ScheduleDto {
        val slots = slotsOf(row)
        val taken = taken(projectId, row.channel_id)
        val nextFree = if (row.active != 0L) {
            SlotPlanner.upcoming(zoneOf(row.timezone), slots.map(::toSlot), Instant.ofEpochMilli(clock()))
                .filter { it !in taken }.take(PREVIEW_SLOTS).map { it.toEpochMilli() }.toList()
        } else {
            emptyList()
        }
        return ScheduleDto(row.id, row.channel_id, row.name, row.timezone, row.active != 0L, slots, nextFree)
    }

    private fun taken(projectId: String, channelId: String): Set<Instant> =
        db.publicationQueries.takenSlots(projectId, channelId).executeAsList().map(Instant::ofEpochMilli).toHashSet()

    // --- planning ---------------------------------------------------------------------

    /**
     * Puts a ready post in the queue, moves a planned one to a new time, or
     * tries a failed one again. A post already being sent, or out, stays as it is.
     */
    fun plan(projectId: String, postId: String, request: PlanRequest): PostDto {
        val post = db.postQueries.byId(projectId, postId).executeAsOneOrNull() ?: throw ApiException.notFound("post")
        if (post.stage !in PLANNABLE) throw ApiException(HttpStatusCode.Conflict, "post.not_plannable", mapOf("stage" to post.stage))
        val channelId = post.channel_id ?: throw ApiException(HttpStatusCode.Conflict, "post.no_channel")
        val existing = db.publicationQueries.byPost(projectId, postId).executeAsOneOrNull()
        val now = clock()
        var scheduleId: String? = null
        var slotAt: Long? = null
        val publishAfter = when (request.mode) {
            PlanModes.NOW -> now + request.delaySeconds.coerceIn(0, MAX_UNDO_SECONDS) * 1000L
            PlanModes.AT -> {
                val at = request.at ?: throw ApiException.badRequest("at")
                if (at < now - PAST_GRACE_MS) throw ApiException(HttpStatusCode.UnprocessableEntity, "plan.in_past")
                at
            }
            PlanModes.NEXT_SLOT -> {
                val schedules = request.scheduleId
                    ?.let { listOfNotNull(db.scheduleQueries.byId(projectId, it).executeAsOneOrNull()) }
                    ?: db.scheduleQueries.forChannel(projectId, channelId).executeAsList()
                if (schedules.isEmpty()) throw ApiException(HttpStatusCode.Conflict, "plan.no_schedule")
                // Its own slot is free for it: moving within a schedule should not skip it.
                val taken = taken(projectId, channelId) - (existing?.slot_at?.let(Instant::ofEpochMilli)).let { setOfNotNull(it) }
                val best = schedules.mapNotNull { s ->
                    SlotPlanner.nextFree(zoneOf(s.timezone), slotsOf(s).map(::toSlot), Instant.ofEpochMilli(now), taken)?.let { s to it }
                }.minByOrNull { it.second } ?: throw ApiException(HttpStatusCode.Conflict, "plan.no_free_slot")
                scheduleId = best.first.id
                slotAt = best.second.toEpochMilli()
                slotAt
            }
            else -> throw ApiException.badRequest("mode")
        }
        if (existing?.state == PublicationStates.PUBLISHING) throw ApiException(HttpStatusCode.Conflict, "post.publishing")
        feed.change(projectId, "post", postId) {
            db.publicationQueries.upsert(
                projectId, postId, channelId, scheduleId, slotAt, publishAfter,
                // A retry keeps its key: the instance may have taken the last try after all.
                existing?.idempotency_key ?: UUID.randomUUID().toString(),
                existing?.created_at ?: now, now,
            )
            db.postQueries.setStage(PostStages.SCHEDULED, now, projectId, postId)
        }
        wake.trySend(Unit)
        return posts.get(projectId, postId)
    }

    /** Out of the queue and back to review, as long as it is not being sent. */
    fun unplan(projectId: String, postId: String): PostDto {
        val post = db.postQueries.byId(projectId, postId).executeAsOneOrNull() ?: throw ApiException.notFound("post")
        if (post.stage == PostStages.READY) return posts.get(projectId, postId)
        if (post.stage != PostStages.SCHEDULED && post.stage != PostStages.FAILED) {
            throw ApiException(HttpStatusCode.Conflict, "post.not_plannable", mapOf("stage" to post.stage))
        }
        feed.change(projectId, "post", postId) {
            db.publicationQueries.delete(projectId, postId)
            db.postQueries.setStage(PostStages.READY, clock(), projectId, postId)
        }
        return posts.get(projectId, postId)
    }

    // --- the way out ----------------------------------------------------------------------

    /**
     * Publishes due posts: at the moment the next one is due, at least every
     * [intervalMs], and at once when a post is planned — so "post in 5 s" means
     * five seconds, not the next round.
     */
    fun start(scope: CoroutineScope, intervalMs: Long = TICK_MS): Job = scope.launch {
        recover()
        while (isActive) {
            runCatching { tick() }.onFailure { log.warn("Publishing round failed: {}", it.javaClass.simpleName) }
            val next = db.publicationQueries.nextDue().executeAsOneOrNull()?.MIN
            val wait = next?.let { (it - clock()).coerceIn(WAKE_SETTLE_MS, intervalMs) } ?: intervalMs
            withTimeoutOrNull(wait) { wake.receive() }
            delay(WAKE_SETTLE_MS)
        }
    }

    /** After a crash: posts that were being sent go back in the queue, under their key. */
    fun recover() {
        db.publicationQueries.interrupted().executeAsList().forEach { p ->
            feed.change(p.project_id, "post", p.post_id) {
                db.publicationQueries.retryLater(clock(), "server.interrupted", clock(), p.project_id, p.post_id)
                db.postQueries.setStage(PostStages.SCHEDULED, clock(), p.project_id, p.post_id)
            }
        }
    }

    /** One round: everything due goes out, one post at a time. */
    suspend fun tick() = sending.withLock {
        for (due in db.publicationQueries.due(clock()).executeAsList()) {
            val claimed = db.publicationQueries.transactionWithResult {
                db.publicationQueries.claim(clock(), due.project_id, due.post_id)
                db.publicationQueries.byPost(due.project_id, due.post_id).executeAsOneOrNull()?.state == PublicationStates.PUBLISHING
            }
            if (!claimed) continue
            feed.change(due.project_id, "post", due.post_id) { db.postQueries.setStage(PostStages.PUBLISHING, clock(), due.project_id, due.post_id) }
            send(db.publicationQueries.byPost(due.project_id, due.post_id).executeAsOne())
        }
    }

    private suspend fun send(p: Publication) {
        val outcome = runCatching { publisher.publish(job(p)) }
        val now = clock()
        outcome.onSuccess { published ->
            feed.change(p.project_id, "post", p.post_id) {
                db.publicationQueries.published(published.remoteId, published.url, now, now, p.project_id, p.post_id)
                db.postQueries.setStage(PostStages.PUBLISHED, now, p.project_id, p.post_id)
            }
            countHashtags(p.project_id, p.post_id)
            log.info("Published a post")
        }.onFailure { e ->
            val code = (e as? MastodonException)?.message ?: (e as? ApiException)?.code ?: "publish.error"
            val retryable = (e as? MastodonException)?.retryable == true
            val attempts = p.attempts.toInt()
            feed.change(p.project_id, "post", p.post_id) {
                if (retryable && attempts < BACKOFF_MS.size) {
                    db.publicationQueries.retryLater(now + BACKOFF_MS[attempts - 1], code, now, p.project_id, p.post_id)
                    db.postQueries.setStage(PostStages.SCHEDULED, now, p.project_id, p.post_id)
                } else {
                    db.publicationQueries.failed(code, now, p.project_id, p.post_id)
                    db.postQueries.setStage(PostStages.FAILED, now, p.project_id, p.post_id)
                }
            }
            log.warn("Publishing failed: {} (try {}, {})", code, attempts, if (retryable) "will retry" else "final")
        }
    }

    private fun job(p: Publication): PublishJob {
        val post = posts.get(p.project_id, p.post_id)
        val channel = channels.get(p.project_id, p.channel_id)
        val token = channels.token(p.project_id, p.channel_id) ?: throw ApiException(HttpStatusCode.Conflict, "channel.no_token")
        return PublishJob(
            instance = channel.instance,
            token = token,
            text = post.finalText ?: throw ApiException(HttpStatusCode.Conflict, "post.no_final_text"),
            contentWarning = post.contentWarning,
            visibility = post.visibility.lowercase(),
            media = post.media.map { m -> PublishMedia(media.file(p.project_id, m.asset.sha256).readBytes(), m.asset.mime, m.altText) },
            idempotencyKey = p.idempotency_key,
        )
    }

    /** A sent post's hashtags count as used, so the phones offer the most used first. */
    private fun countHashtags(projectId: String, postId: String) {
        val tags = posts.get(projectId, postId).hashtags
        for (tag in tags) {
            val key = Hashtags.key(tag)
            val uses = settings.get(projectId, SettingsKinds.HASHTAG_USAGE, key)?.get("uses")?.jsonPrimitive?.intOrNull ?: 0
            settings.put(projectId, SettingsKinds.HASHTAG_USAGE, key, buildJsonObject { put("key", key); put("uses", uses + 1) }, null)
        }
    }

    // --- slots -------------------------------------------------------------------------------

    private fun slotsOf(row: Schedule): List<SlotDto> =
        runCatching { json.decodeFromString(ListSerializer(SlotDto.serializer()), row.slots_json) }.getOrDefault(emptyList())

    private fun slotsJson(slots: List<SlotDto>) = json.encodeToString(ListSerializer(SlotDto.serializer()), slots)

    private fun validSlots(slots: List<SlotDto>): List<SlotDto> = slots.map { s ->
        val time = runCatching { LocalTime.parse(s.time) }.getOrElse { throw ApiException.badRequest("slot.time") }
        if (s.days.any { it !in 1..7 }) throw ApiException.badRequest("slot.days")
        SlotDto(s.days.distinct().sorted(), "%02d:%02d".format(time.hour, time.minute))
    }

    private fun toSlot(s: SlotDto) = Slot(s.days.map(DayOfWeek::of).toSet(), LocalTime.parse(s.time))

    private fun zoneOf(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrElse { throw ApiException.badRequest("timezone") }

    companion object {
        val PLANNABLE = setOf(PostStages.READY, PostStages.SCHEDULED, PostStages.FAILED)
        const val TICK_MS = 15_000L
        const val WAKE_SETTLE_MS = 250L
        const val MAX_UNDO_SECONDS = 60
        const val PAST_GRACE_MS = 60_000L
        const val PREVIEW_SLOTS = 3
        /** Waits before the second, third … try; after the last, the post has failed. */
        val BACKOFF_MS = listOf(60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

        fun dto(p: Publication) = PublicationDto(
            state = p.state,
            channelId = p.channel_id,
            scheduleId = p.schedule_id,
            slotAt = p.slot_at,
            publishAfter = p.publish_after,
            attempts = p.attempts.toInt(),
            remoteUrl = p.remote_url,
            failureCode = p.failure_code,
            publishedAt = p.published_at,
        )
    }
}
