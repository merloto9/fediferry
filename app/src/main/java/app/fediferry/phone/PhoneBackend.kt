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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import app.fediferry.api.AltSuggestion
import app.fediferry.api.AuthorizeResponse
import app.fediferry.api.ChannelAccount
import app.fediferry.api.ChannelDto
import app.fediferry.api.ChannelInput
import app.fediferry.api.ChannelPatch
import app.fediferry.api.DeriveRequest
import app.fediferry.api.LabelDto
import app.fediferry.api.LockDto
import app.fediferry.api.MediaAssetDto
import app.fediferry.api.PlanModes
import app.fediferry.api.PlanRequest
import app.fediferry.api.Platform
import app.fediferry.api.PlatformLimits
import app.fediferry.api.PostDto
import app.fediferry.api.PostMediaDto
import app.fediferry.api.PostPatch
import app.fediferry.api.PostSettings
import app.fediferry.api.PostStages
import app.fediferry.api.PublicationDto
import app.fediferry.api.PublicationStates
import app.fediferry.api.PublishedMediaDto
import app.fediferry.api.PublishedPage
import app.fediferry.api.PublishedStatusDto
import app.fediferry.api.ReviewFolderDto
import app.fediferry.api.ReviewFolderInput
import app.fediferry.api.ReviewPatch
import app.fediferry.api.ScheduleDto
import app.fediferry.api.ScheduleInput
import app.fediferry.api.SlotDto
import app.fediferry.backend.PostBackend
import app.fediferry.backend.Reachability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.fediferry.channel.PostValidator
import app.fediferry.client.ServerException
import app.fediferry.data.model.Account
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.Item
import app.fediferry.data.model.Visibility
import app.fediferry.di.ServiceLocator
import app.fediferry.media.cleanup.TreatmentKind
import app.fediferry.media.cleanup.Region
import app.fediferry.media.cleanup.CleanupRule
import app.fediferry.mastodon.MastodonException
import androidx.room.withTransaction
import app.fediferry.log.DebugLog
import app.fediferry.mastodon.MastodonAccounts
import app.fediferry.mastodon.MastodonText
import app.fediferry.mastodon.ScheduledStatus
import app.fediferry.platform.PlatformDetector
import app.fediferry.platform.PlatformException
import app.fediferry.schedule.Slot
import app.fediferry.schedule.SlotPlanner
import app.fediferry.template.TemplateEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's own project: everything a FediFerry server does for its
 * projects, done on the phone. Posts, review folders and schedules live in
 * the app's database; the phone's Mastodon accounts are the channels.
 *
 * A post planned for now goes out from the phone after the undo window. A
 * post planned for later is handed to Mastodon at once, with its time — the
 * phone need not be on then — and from that moment Mastodon holds it: it
 * shows in the queue as Mastodon has it, and leaves the phone's store, the
 * way a published post does.
 *
 * Failures speak the server's codes ([ServerException]), so the screens word
 * them the same whichever backend they came from.
 */
class PhoneBackend(private val context: Context) : PostBackend {

    private val db get() = ServiceLocator.database(context)
    private val dao get() = db.phone()
    private val mastodon get() = ServiceLocator.mastodon(context)
    private val accounts by lazy { MastodonAccounts(ServiceLocator.http()) }
    private val detector by lazy { PlatformDetector(ServiceLocator.http()) }
    private val tokens get() = ServiceLocator.tokens(context)

    /** Where each picture's file is, for [mediaUrl]: filled as posts are read. */
    private val paths = ConcurrentHashMap<String, String>()

    override val deviceId: String = "this-phone"
    override val key: String = "phone"

    /** The phone's own database is always there; what Mastodon is asked for says itself when it fails. */
    override val reachable: StateFlow<Reachability> = MutableStateFlow(Reachability()).asStateFlow()

    override fun changes(): Flow<Set<String>> =
        db.invalidationTracker.createFlow(*TABLES.keys.toTypedArray(), emitInitialState = false)
            .map { tables -> tables.mapNotNull { TABLES[it] }.toSet() }

    // --- posts ----------------------------------------------------------------------

    override suspend fun posts(vararg stages: String): List<PostDto> {
        val local = dao.posts(stages.toList()).map { dto(it) }
        val remote = if (PostStages.SCHEDULED in stages) mastodonQueue() else emptyList()
        return local + remote
    }

    override suspend fun post(id: String): PostDto {
        RemoteId.parse(id)?.let { (channelId, statusId) -> return remotePost(channelId, statusId) }
        return dto(row(id))
    }

    override suspend fun deletePost(id: String) {
        val post = row(id)
        if (post.stage !in DELETABLE) throw ServerException(409, "post.not_deletable", mapOf("stage" to post.stage))
        purge(post)
    }

    // --- editing --------------------------------------------------------------------------

    /** One phone, one person: the draft is always this phone's to edit while it is a draft. */
    override suspend fun lockPost(id: String, force: Boolean): LockDto {
        val post = row(id)
        if (post.stage != PostStages.DRAFT) throw ServerException(409, "post.not_draft", mapOf("stage" to post.stage))
        return LockDto(deviceId, "", System.currentTimeMillis() + LOCK_MS)
    }

    override suspend fun unlockPost(id: String) = Unit

    override suspend fun patchPost(id: String, version: Long, patch: PostPatch): PostDto {
        val post = row(id)
        if (post.stage != PostStages.DRAFT) throw ServerException(409, "post.not_draft", mapOf("stage" to post.stage))
        if (version != post.version) throw ServerException(409, "post.version_conflict", mapOf("current" to "${post.version}"))
        val keys = db.placeholderKeys().all()
        val channelId = when (val c = patch.channelId) {
            null -> post.channelId
            "" -> null
            else -> c.also { dao.channel(it) ?: throw ServerException(404, "not_found") }
        }
        var body = patch.body ?: post.body
        var hashtags = patch.hashtags?.let(Hashtags::format) ?: post.hashtags
        var contentWarning = patch.contentWarning?.ifBlank { null } ?: post.contentWarning.takeIf { patch.contentWarning == null }
        var visibility = patch.visibility ?: post.visibility
        val addSource = patch.addSourceHashtags ?: post.addSourceHashtags
        val inputs = inputs(post)
        val moved = channelId != post.channelId && patch.body == null && post.body == TemplateEngine.render(settingsOf(post.channelId), keys, inputs)
        if (patch.rerender || moved) {
            val settings = settingsOf(channelId)
            body = TemplateEngine.render(settings, keys, inputs)
            hashtags = Hashtags.format(TemplateEngine.hashtagsFor(settings, keys, inputs, addSource))
            if (patch.visibility == null) visibility = settings.visibility
            if (patch.contentWarning == null) contentWarning = settings.contentWarning
        }
        var media = mediaOf(post)
        patch.media?.forEach { change ->
            media = when {
                change.remove -> media.filterNot { it.position == change.position }
                else -> media.map { m ->
                    if (m.position != change.position) return@map m
                    var next = m
                    change.assetId?.let { a -> dao.asset(a) ?: throw ServerException(404, "not_found"); next = next.copy(assetId = a) }
                    change.altText?.let { t -> next = next.copy(altText = t.ifBlank { null }, altFailed = false) }
                    next
                }
            }
        }
        media = media.sortedBy { it.position }.mapIndexed { i, m -> m.copy(position = i) }
        val saved = post.copy(
            channelId = channelId, body = body, hashtags = hashtags, addSourceHashtags = addSource,
            contentWarning = contentWarning, visibility = visibility, mediaJson = json.encodeToString(MEDIA, media),
            version = post.version + 1, updatedAt = System.currentTimeMillis(),
        )
        dao.upsertPost(saved)
        return dto(saved)
    }

    /** Crops or cleans a picture on the phone; the result is a new picture that names its original. */
    override suspend fun derive(assetId: String, request: DeriveRequest): MediaAssetDto {
        val source = dao.asset(assetId) ?: throw ServerException(404, "not_found")
        if (!source.mime.startsWith("image/") || source.mime == "image/gif") throw ServerException(422, "media.not_editable")
        var bitmap: Bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeFile(source.path) }
            ?: throw ServerException(422, "media.not_editable")
        request.crop?.let { c ->
            val x = (c.left.coerceIn(0f, 1f) * bitmap.width).toInt()
            val y = (c.top.coerceIn(0f, 1f) * bitmap.height).toInt()
            val w = ((c.right.coerceIn(0f, 1f) - c.left.coerceIn(0f, 1f)) * bitmap.width).toInt().coerceAtMost(bitmap.width - x)
            val h = ((c.bottom.coerceIn(0f, 1f) - c.top.coerceIn(0f, 1f)) * bitmap.height).toInt().coerceAtMost(bitmap.height - y)
            if (w < 8 || h < 8) throw ServerException(422, "media.not_editable")
            bitmap = Bitmap.createBitmap(bitmap, x, y, w, h)
        }
        val made = storeBitmap(bitmap, source.mime, parent = source)
        // Areas drawn for this picture, else the profile's.
        val rules = request.areas?.map { a ->
            CleanupRule(Region(a.left, a.top, a.right, a.bottom), runCatching { TreatmentKind.valueOf(a.treatment) }.getOrDefault(TreatmentKind.FILL))
        } ?: request.profileId?.let { id -> db.cleanup().enabledRules(id).map { it.toCleanupRule() } }.orEmpty()
        if (rules.isEmpty()) return assetDto(made)
        val outcome = app.fediferry.media.cleanup.BitmapCleaner.clean(
            made.path, rules,
            ServiceLocator.imageEditProvider(context, request.modelId), ServiceLocator.maskPolarity(context, request.modelId),
            request.instruction?.takeIf { it.isNotBlank() } ?: ServiceLocator.settings(context).current().imageInstruction,
        ) ?: throw ServerException(422, "media.not_editable")
        return assetDto(storeBitmap(outcome.bitmap, source.mime, parent = source)).copy(aiFailure = outcome.aiFailure)
    }

    override suspend fun suggestAlt(postId: String, position: Int, modelId: String?): AltSuggestion {
        val post = row(postId)
        val asset = mediaOf(post).firstOrNull { it.position == position }?.let { dao.asset(it.assetId) } ?: throw ServerException(404, "not_found")
        if (ServiceLocator.aiModels(context).pick(app.fediferry.data.model.AiKind.ALT_TEXT, modelId) == null) throw ServerException(409, "alt.no_model")
        val bytes = withContext(Dispatchers.IO) { File(asset.path).readBytes() }
        val text = ServiceLocator.visionProvider(context, modelId).describe(bytes, asset.mime)
            .getOrElse { throw ServerException(502, "alt.failed", mapOf("reason" to (it.message ?: it.javaClass.simpleName))) }
        return AltSuggestion(text)
    }

    // --- review ---------------------------------------------------------------------------

    override suspend fun markReady(id: String, version: Long): PostDto {
        val post = row(id)
        if (post.stage != PostStages.DRAFT) throw ServerException(409, "post.not_draft", mapOf("stage" to post.stage))
        if (version != post.version) throw ServerException(409, "post.version_conflict", mapOf("current" to "${post.version}"))
        val dto = dto(post)
        val channel = post.channelId?.let { channelDto(it) }
        val keys = db.placeholderKeys().all().map { it.name }
        val blocking = PostValidator.check(dto, channel, keys).filter { it.blocking }
        if (blocking.isNotEmpty()) throw ServerException(422, "post.not_ready", violations = blocking)
        val now = System.currentTimeMillis()
        val ready = post.copy(stage = PostStages.READY, finalText = PostValidator.finalText(dto), readyAt = now, version = post.version + 1, updatedAt = now)
        dao.upsertPost(ready)
        return dto(ready)
    }

    override suspend fun backToDraft(id: String): PostDto {
        val post = row(id)
        if (post.stage == PostStages.DRAFT) return dto(post)
        if (post.stage != PostStages.READY) throw ServerException(409, "post.not_ready_stage", mapOf("stage" to post.stage))
        val draft = post.copy(stage = PostStages.DRAFT, finalText = null, readyAt = null, reviewFolderId = null, version = post.version + 1, updatedAt = System.currentTimeMillis())
        dao.upsertPost(draft)
        return dto(draft)
    }

    override suspend fun sortForReview(id: String, patch: ReviewPatch): PostDto {
        val post = row(id)
        if (post.stage == PostStages.DRAFT) throw ServerException(409, "post.not_ready_stage", mapOf("stage" to post.stage))
        val folder = when (val f = patch.folderId) {
            null -> post.reviewFolderId
            "" -> null
            else -> f.also { if (dao.reviewFolders().none { r -> r.id == it }) throw ServerException(404, "not_found") }
        }
        val labels = patch.labels?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinctBy { it.lowercase() }?.map { it.take(MAX_LABEL) }
        val sorted = post.copy(
            reviewFolderId = folder,
            labelsJson = labels?.let { json.encodeToString(LABELS, it) } ?: post.labelsJson,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertPost(sorted)
        return dto(sorted)
    }

    override suspend fun reviewFolders(): List<ReviewFolderDto> = dao.reviewFolders().map { ReviewFolderDto(it.id, it.name, it.sortOrder) }

    override suspend fun createReviewFolder(input: ReviewFolderInput): ReviewFolderDto {
        val name = input.name.trim().ifEmpty { throw ServerException(400, "bad_request") }
        val folder = PhoneReviewFolder(UUID.randomUUID().toString(), name, input.sortOrder ?: dao.reviewFolders().size)
        dao.upsertReviewFolder(folder)
        return ReviewFolderDto(folder.id, folder.name, folder.sortOrder)
    }

    override suspend fun updateReviewFolder(id: String, input: ReviewFolderInput): ReviewFolderDto {
        val folder = dao.reviewFolders().firstOrNull { it.id == id } ?: throw ServerException(404, "not_found")
        val next = folder.copy(name = input.name.trim().ifEmpty { folder.name }, sortOrder = input.sortOrder ?: folder.sortOrder)
        dao.upsertReviewFolder(next)
        return ReviewFolderDto(next.id, next.name, next.sortOrder)
    }

    override suspend fun deleteReviewFolder(id: String) {
        dao.releaseReviewFolder(id)
        dao.deleteReviewFolder(id)
    }

    override suspend fun labels(): List<LabelDto> = dao.allPosts().flatMap { labelsOf(it) }
        .groupBy { it.lowercase() }.map { (_, names) -> LabelDto(names.first(), names.size) }.sortedBy { it.name.lowercase() }

    // --- publishing -------------------------------------------------------------------------

    /**
     * Puts a ready (or failed) post on its way. Now: from the phone, after
     * [PlanRequest.delaySeconds]. Later: handed to Mastodon with its time at
     * once — or, closer than Mastodon accepts, sent from the phone then.
     */
    override suspend fun plan(id: String, request: PlanRequest): PostDto {
        RemoteId.parse(id)?.let { (channelId, statusId) -> return planRemote(channelId, statusId, request) }
        val post = row(id)
        if (post.stage !in PLANNABLE) throw ServerException(409, "post.not_plannable", mapOf("stage" to post.stage))
        val channelId = post.channelId ?: throw ServerException(409, "post.no_channel")
        val now = System.currentTimeMillis()
        var scheduleId: String? = null
        var slotAt: Long? = null
        val at = when (request.mode) {
            PlanModes.NOW -> now + request.delaySeconds.coerceIn(0, MAX_UNDO_SECONDS) * 1000L
            PlanModes.AT -> (request.at ?: throw ServerException(400, "bad_request")).also {
                if (it < now - PAST_GRACE_MS) throw ServerException(422, "plan.in_past")
            }
            PlanModes.NEXT_SLOT -> {
                val (schedule, slot) = nextSlot(channelId, request.scheduleId)
                scheduleId = schedule.id
                slotAt = slot
                slot
            }
            else -> throw ServerException(400, "bad_request")
        }
        // Mastodon holds what is far enough ahead; anything sooner the phone sends itself.
        val toMastodon = request.mode != PlanModes.NOW && at - now >= PhonePublishWorker.MIN_MASTODON_LEAD_MS
        val publication = PhonePublication(publishAfter = if (toMastodon) now else at, scheduledAt = at.takeIf { toMastodon }, scheduleId = scheduleId, slotAt = slotAt)
        val planned = post.copy(stage = PostStages.SCHEDULED, publicationJson = json.encodeToString(PhonePublication.serializer(), publication), version = post.version + 1, updatedAt = now)
        dao.upsertPost(planned)
        PhonePublishWorker.enqueue(context, id, delayMillis = (publication.publishAfter - now).coerceAtLeast(0))
        return dto(planned)
    }

    /** Out of the queue and back to review: a post still waiting here, or one Mastodon holds. */
    override suspend fun unplan(id: String): PostDto {
        RemoteId.parse(id)?.let { (channelId, statusId) -> return reviewFromMastodon(channelId, statusId) }
        val post = row(id)
        if (post.stage == PostStages.READY) return dto(post)
        if (post.stage == PostStages.PUBLISHING) throw ServerException(409, "post.publishing")
        if (post.stage != PostStages.SCHEDULED && post.stage != PostStages.FAILED) {
            throw ServerException(409, "post.not_plannable", mapOf("stage" to post.stage))
        }
        // Only while the worker has not started sending; then it is too late.
        if (dao.takeBack(id) == 0) throw ServerException(409, "post.publishing")
        PhonePublishWorker.cancel(context, id)
        return dto(row(id))
    }

    override suspend fun schedules(): List<ScheduleDto> {
        val taken = takenSlots()
        return dao.schedules().map { scheduleDto(it, taken[it.channelId].orEmpty()) }
    }

    override suspend fun createSchedule(input: ScheduleInput): ScheduleDto {
        val channelId = input.channelId ?: throw ServerException(400, "bad_request")
        dao.channel(channelId) ?: throw ServerException(404, "not_found")
        val schedule = PhoneSchedule(
            UUID.randomUUID().toString(), channelId, input.name?.trim()?.ifEmpty { null } ?: "Schedule",
            zoneOf(input.timezone ?: ZoneId.systemDefault().id).id, input.active != false, json.encodeToString(SLOTS, validSlots(input.slots.orEmpty())),
        )
        dao.upsertSchedule(schedule)
        return scheduleDto(schedule, takenSlots()[channelId].orEmpty())
    }

    override suspend fun updateSchedule(id: String, input: ScheduleInput): ScheduleDto {
        val row = dao.schedules().firstOrNull { it.id == id } ?: throw ServerException(404, "not_found")
        val next = row.copy(
            channelId = input.channelId ?: row.channelId,
            name = input.name?.trim()?.ifEmpty { null } ?: row.name,
            timezone = input.timezone?.let { zoneOf(it).id } ?: row.timezone,
            active = input.active ?: row.active,
            slotsJson = input.slots?.let { json.encodeToString(SLOTS, validSlots(it)) } ?: row.slotsJson,
        )
        dao.upsertSchedule(next)
        return scheduleDto(next, takenSlots()[next.channelId].orEmpty())
    }

    override suspend fun deleteSchedule(id: String) = dao.deleteSchedule(id)

    override suspend fun published(limit: Int): PublishedPage {
        val size = limit.coerceIn(1, 40)
        val unreachable = mutableListOf<String>()
        val statuses = dao.channels().filter { it.accountId != null }.flatMap { channel ->
            val (account, token) = accountOf(channel) ?: return@flatMap emptyList<PublishedStatusDto>().also { unreachable += channel.id }
            runCatching {
                val remoteId = channel.remoteAccountId ?: mastodon.verifyCredentials(account.instance, token).id.also {
                    dao.upsertChannel(channel.copy(remoteAccountId = it))
                }
                accounts.statuses(account.instance, token, remoteId, size).mapNotNull { st ->
                    val at = runCatching { Instant.parse(st.createdAt).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
                    PublishedStatusDto(
                        channelId = channel.id, id = st.id, url = st.url, createdAt = at,
                        text = MastodonText.plain(st.content), contentWarning = st.spoilerText.ifBlank { null },
                        media = st.media.map { PublishedMediaDto(it.previewUrl, it.url, it.description) },
                    )
                }
            }.onFailure { DebugLog.w(LOG, "Reading a channel's posts failed: ${it.javaClass.simpleName}") }
                .getOrElse { unreachable += channel.id; emptyList() }
        }
        return PublishedPage(statuses.sortedByDescending { it.createdAt }.take(size), unreachable)
    }

    // --- channels -------------------------------------------------------------------------

    override suspend fun channels(): List<ChannelDto> = dao.channels().map { channelDto(it) }

    override suspend fun createChannel(input: ChannelInput): ChannelDto {
        val name = input.name.trim().ifEmpty { throw ServerException(400, "bad_request") }
        val all = dao.channels()
        val channel = PhoneChannel(
            UUID.randomUUID().toString(), name, isDefault = all.isEmpty(), sortOrder = all.size,
            postJson = json.encodeToString(PostSettings.serializer(), input.post ?: PostSettings()),
        )
        dao.upsertChannel(channel)
        return channelDto(channel)
    }

    override suspend fun patchChannel(id: String, patch: ChannelPatch): ChannelDto {
        val channel = dao.channel(id) ?: throw ServerException(404, "not_found")
        val next = channel.copy(
            name = patch.name?.trim()?.ifEmpty { null } ?: channel.name,
            postJson = patch.post?.let { json.encodeToString(PostSettings.serializer(), it) } ?: channel.postJson,
        )
        dao.upsertChannel(next)
        if (patch.isDefault == true) dao.setDefaultChannel(id)
        return channelDto(dao.channel(id) ?: next)
    }

    /** Removes a channel; its account signs out of the phone unless another channel still uses it. */
    override suspend fun deleteChannel(id: String) {
        val channel = dao.channel(id) ?: throw ServerException(404, "not_found")
        dao.releaseChannel(id)
        dao.deleteSchedulesOf(id)
        dao.deleteChannel(id)
        if (channel.isDefault) dao.channels().firstOrNull()?.let { dao.setDefaultChannel(it.id) }
        val accountId = channel.accountId ?: return
        if (dao.channels().none { it.accountId == accountId }) ServiceLocator.auth(context).disconnect(accountId)
    }

    override suspend fun refreshLimits(id: String): ChannelDto {
        val channel = dao.channel(id) ?: throw ServerException(404, "not_found")
        val account = channel.accountId?.let { db.accounts().byId(it) } ?: throw ServerException(409, "channel.no_account")
        val limits = runCatching { accounts.limits(account.instance) }
            .getOrElse { throw ServerException(502, it.message ?: "mastodon.error") }
        dao.upsertChannel(channel.copy(limitsJson = json.encodeToString(PlatformLimits.serializer(), limits)))
        return channelDto(dao.channel(id)!!)
    }

    /**
     * Detects what runs at [instance], then starts the phone's own sign-in;
     * [attachAccount] puts the account on the channel when the browser is back.
     */
    override suspend fun connectAccount(instance: String, channelId: String?): AuthorizeResponse {
        val detected = try {
            detector.detect(instance)
        } catch (e: PlatformException) {
            throw ServerException(502, e.message ?: "platform.undetected", mapOf("instance" to instance))
        }
        val platform = detected.platform ?: throw ServerException(422, "platform.unknown", mapOf("software" to detected.software))
        if (!platform.supported) throw ServerException(422, "platform.unsupported", mapOf("platform" to platform.label))
        return ServiceLocator.auth(context).authorize(instance, channelId)
    }

    /** The account a finished sign-in connected goes to [channelId], or to a new channel. */
    suspend fun attachAccount(channelId: String?, account: Account, remoteAccountId: String?): ChannelDto {
        val holder = dao.channels().firstOrNull { it.accountId == account.id }
        if (channelId != null && holder != null && holder.id != channelId) {
            throw ServerException(409, "channel.account_in_use", mapOf("channel" to holder.name))
        }
        val all = dao.channels()
        val target = channelId?.let { dao.channel(it) } ?: holder
            ?: PhoneChannel(account.id, account.displayName.ifBlank { account.acct }, isDefault = all.isEmpty(), sortOrder = all.size,
                postJson = json.encodeToString(PostSettings.serializer(), PostSettings()))
        val limits = runCatching { accounts.limits(account.instance) }.getOrDefault(Platform.MASTODON.defaultLimits)
        dao.upsertChannel(
            target.copy(
                accountId = account.id, platform = Platform.MASTODON.name, remoteAccountId = remoteAccountId,
                limitsJson = json.encodeToString(PlatformLimits.serializer(), limits),
            ),
        )
        // The account this channel had before is signed out, unless another channel still posts as it.
        target.accountId?.takeIf { it != account.id }?.let { previous ->
            if (dao.channels().none { it.accountId == previous }) ServiceLocator.auth(context).disconnect(previous)
        }
        return channelDto(dao.channel(target.id)!!)
    }

    // --- pictures -----------------------------------------------------------------------------

    override fun mediaUrl(assetId: String): String =
        if (assetId.startsWith("http")) assetId else paths[assetId]?.let { File(it).toUri().toString() }.orEmpty()

    override fun thumbnailUrl(assetId: String): String = mediaUrl(assetId)

    // --- taking shares in ---------------------------------------------------------------------

    /** How a share went into the phone's project. */
    data class Adopted(val postId: String, val paired: Boolean = false, val duplicate: Boolean = false)

    /**
     * Makes a post of an [item] the share pipeline produced — cleaned, fetched,
     * trimmed — and lets go of the item. A new share becomes a draft on the
     * default channel, written with its settings; it joins a recent draft that
     * waits for exactly its half (a link, or its screenshot), or is the draft
     * that has its picture already. With [ready] (the inbox of an older
     * version) the post goes to review as it was written, into [reviewFolderId].
     */
    suspend fun adopt(item: Item, ready: Boolean = false, reviewFolderId: String? = null, channelId: String? = null): Adopted {
        val original = item.originalMediaPath?.takeIf { it != item.mediaPath }?.let { assetFor(it, item.mimeType, parent = null) }
        val picture = item.mediaPath?.let { assetFor(it, item.mimeType, parent = original) }
        // The item goes only together with the post it became: a death in
        // between neither loses it nor leaves it to be adopted twice.
        return db.withTransaction {
            place(item, ready, reviewFolderId, channelId, picture).also { db.items().delete(item.id) }
        }
    }

    private suspend fun place(item: Item, ready: Boolean, reviewFolderId: String?, channelId: String?, picture: PhoneAsset?): Adopted {
        val now = System.currentTimeMillis()
        if (!ready) {
            // The same picture again: the draft that has it.
            picture?.let { p -> dao.allPosts().firstOrNull { post -> mediaOf(post).any { m -> sameAs(m.assetId, p) } } }
                ?.let { return Adopted(it.id, duplicate = true) }
            val since = now - PAIRING_WINDOW_MS
            val drafts = dao.posts(listOf(PostStages.DRAFT)).filter { it.createdAt >= since }.sortedByDescending { it.createdAt }
            if (picture != null && item.sourceUrl == null) {
                drafts.firstOrNull { it.sourceUrl != null && mediaOf(it).isEmpty() }?.let { waiting ->
                    dao.upsertPost(waiting.copy(mediaJson = json.encodeToString(MEDIA, listOf(PhoneMedia(0, picture.id, staticAlt(waiting.channelId)))), version = waiting.version + 1, updatedAt = now))
                    return Adopted(waiting.id, paired = true)
                }
            }
            if (picture == null && item.sourceUrl != null) {
                drafts.firstOrNull { it.sourceUrl == null && mediaOf(it).isNotEmpty() }?.let { waiting ->
                    return Adopted(attachLink(waiting, item).id, paired = true)
                }
            }
        }

        val channel = dao.channels().let { list -> list.firstOrNull { it.id == channelId } ?: list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
        val settings = channel?.let(::postOf) ?: PostSettings()
        val keys = db.placeholderKeys().all()
        val inputs = TemplateEngine.Inputs(link = item.sourceUrl, source = item.origin, fields = item.sourceFields)
        val textOnly = item.sourceUrl == null && picture == null
        val post = PhonePost(
            id = UUID.randomUUID().toString(),
            stage = if (ready) PostStages.READY else PostStages.DRAFT,
            channelId = channel?.id,
            // An older version's inbox keeps the text as written; a new share starts from the channel.
            body = if (ready || textOnly) item.bodyText else TemplateEngine.render(settings, keys, inputs),
            hashtags = if (ready) Hashtags.format(item.hashtagList) else Hashtags.format(TemplateEngine.hashtagsFor(settings, keys, inputs)),
            addSourceHashtags = if (ready) item.addSourceHashtags else settings.addSourceHashtags,
            contentWarning = if (ready) item.contentWarning else settings.contentWarning,
            visibility = if (ready) item.visibility.name else settings.visibility,
            sourceUrl = item.sourceUrl,
            origin = item.origin?.name,
            sourceFieldsJson = json.encodeToString(FIELDS, item.sourceFields),
            linkMayIdentify = item.linkMayIdentify,
            finalText = if (ready) TemplateEngine.finish(item.bodyText, item.hashtagList).trim() else null,
            readyAt = if (ready) now else null,
            reviewFolderId = reviewFolderId.takeIf { ready },
            mediaJson = json.encodeToString(
                MEDIA,
                listOfNotNull(picture?.let { PhoneMedia(0, it.id, item.altText ?: (if (ready) null else staticAlt(channel?.id)), item.altTextFailed) }),
            ),
            createdAt = item.createdAt,
            updatedAt = now,
        )
        dao.upsertPost(post)
        return Adopted(post.id)
    }

    /** A link joining a draft that had only its picture; its text is written again while still the channel's own. */
    private suspend fun attachLink(post: PhonePost, item: Item): PhonePost {
        val keys = db.placeholderKeys().all()
        val settings = settingsOf(post.channelId)
        val untouched = post.body == TemplateEngine.render(settings, keys, TemplateEngine.Inputs())
        val inputs = TemplateEngine.Inputs(link = item.sourceUrl, source = item.origin, fields = item.sourceFields)
        val joined = post.copy(
            sourceUrl = item.sourceUrl, origin = item.origin?.name, sourceFieldsJson = json.encodeToString(FIELDS, item.sourceFields),
            linkMayIdentify = item.linkMayIdentify,
            body = if (untouched) TemplateEngine.render(settings, keys, inputs) else post.body,
            hashtags = if (untouched) Hashtags.format(TemplateEngine.hashtagsFor(settings, keys, inputs, post.addSourceHashtags)) else post.hashtags,
            version = post.version + 1, updatedAt = System.currentTimeMillis(),
        )
        dao.upsertPost(joined)
        return joined
    }

    // --- the way out, for the worker ------------------------------------------------------------

    /** Everything [PhonePublishWorker] needs to send a post, or null when it is gone or not on its way. */
    internal suspend fun outgoing(id: String): Outgoing? {
        val post = dao.post(id)?.takeIf { it.stage == PostStages.SCHEDULED || it.stage == PostStages.PUBLISHING } ?: return null
        val publication = publicationOf(post) ?: return null
        val channel = post.channelId?.let { dao.channel(it) }
        val pictures = mediaOf(post).mapNotNull { m -> dao.asset(m.assetId)?.let { it to m } }
        return Outgoing(post, publication, channel?.let { accountOf(it) }, channel?.let(::postOf) ?: PostSettings(), pictures)
    }

    internal class Outgoing(
        val post: PhonePost,
        val publication: PhonePublication,
        val account: Pair<Account, String>?,
        val settings: PostSettings,
        val pictures: List<Pair<PhoneAsset, PhoneMedia>>,
    )

    /** Claims the post for sending; false when it was taken back (or is gone) in the meantime. */
    internal suspend fun markSending(id: String): Boolean {
        if (dao.moveStage(id, listOf(PostStages.SCHEDULED), PostStages.PUBLISHING) == 0) return false
        val post = dao.post(id) ?: return false
        val publication = publicationOf(post) ?: return true
        dao.upsertPost(
            post.copy(
                stage = PostStages.PUBLISHING,
                publicationJson = json.encodeToString(PhonePublication.serializer(), publication.copy(attempts = publication.attempts + 1)),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    /** Out — or held by Mastodon: the post leaves the phone, with every picture no other post uses. */
    internal suspend fun markSent(id: String) = dao.post(id)?.let { purge(it) }

    internal suspend fun markFailed(id: String, code: String, final: Boolean) {
        val post = dao.post(id) ?: return
        val publication = publicationOf(post) ?: return
        dao.upsertPost(
            post.copy(
                stage = if (final) PostStages.FAILED else PostStages.SCHEDULED,
                publicationJson = json.encodeToString(PhonePublication.serializer(), publication.copy(failureCode = code)),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** After the app was killed while sending: such posts go back in the queue. */
    suspend fun recover() {
        dao.posts(listOf(PostStages.PUBLISHING)).forEach { post ->
            // WorkManager may have started this process for that very worker; it is not stale then.
            if (PhonePublishWorker.isRunning(context, post.id)) return@forEach
            if (dao.moveStage(post.id, listOf(PostStages.PUBLISHING), PostStages.SCHEDULED) > 0) {
                PhonePublishWorker.enqueue(context, post.id, 0, keep = true)
            }
        }
    }

    // --- what Mastodon holds ---------------------------------------------------------------------

    private suspend fun mastodonQueue(): List<PostDto> = dao.channels().filter { it.accountId != null }.flatMap { channel ->
        val (account, token) = accountOf(channel) ?: return@flatMap emptyList()
        runCatching { mastodon.scheduledStatuses(account.instance, token) }
            .getOrElse { DebugLog.w(LOG, "Reading Mastodon's queue failed: ${it.javaClass.simpleName}"); emptyList() }
            .map { remoteDto(channel.id, it) }
    }

    private suspend fun remotePost(channelId: String, statusId: String): PostDto {
        val (account, token) = dao.channel(channelId)?.let { accountOf(it) } ?: throw ServerException(404, "not_found")
        val status = runCatching { mastodon.scheduledStatus(account.instance, token, statusId) }
            .getOrElse { throw ServerException(404, "not_found") }
        return remoteDto(channelId, status)
    }

    /** Mastodon holds it: moving it means Mastodon's own reschedule; "now" takes it back to send from here. */
    private suspend fun planRemote(channelId: String, statusId: String, request: PlanRequest): PostDto {
        if (request.mode == PlanModes.NOW) {
            val local = reviewFromMastodon(channelId, statusId)
            return plan(local.id, request)
        }
        val (account, token) = dao.channel(channelId)?.let { accountOf(it) } ?: throw ServerException(404, "not_found")
        val at = when (request.mode) {
            PlanModes.AT -> request.at ?: throw ServerException(400, "bad_request")
            PlanModes.NEXT_SLOT -> nextSlot(channelId, request.scheduleId).second
            else -> throw ServerException(400, "bad_request")
        }
        if (at - System.currentTimeMillis() < PhonePublishWorker.MIN_MASTODON_LEAD_MS) throw ServerException(422, "plan.too_soon")
        val moved = runCatching { mastodon.reschedule(account.instance, token, statusId, Instant.ofEpochMilli(at).toString()) }
            .getOrElse { throw ServerException(502, it.message ?: "mastodon.error") }
        return remoteDto(channelId, moved)
    }

    /** Takes a post back from Mastodon's queue into review here: text, pictures and all; then cancels it there. */
    private suspend fun reviewFromMastodon(channelId: String, statusId: String): PostDto {
        val (account, token) = dao.channel(channelId)?.let { accountOf(it) } ?: throw ServerException(404, "not_found")
        val status = runCatching { mastodon.scheduledStatus(account.instance, token, statusId) }.getOrElse { throw ServerException(404, "not_found") }
        // The pictures are fetched before Mastodon lets go of the post, so a failure there loses nothing.
        val vault = ServiceLocator.mediaVault(context)
        val media = status.media.mapIndexed { i, attachment ->
            val url = attachment.url ?: attachment.previewUrl ?: throw ServerException(502, "mastodon.media")
            val bytes = ServiceLocator.mediaFetcher().fetch(url).getOrElse { throw ServerException(502, "mastodon.media") }
            val stored = vault.store(bytes, app.fediferry.data.ScheduledDraft.mimeTypeOf(attachment)).getOrElse { throw ServerException(500, "media.store") }
            val asset = assetFor(stored.file.absolutePath, stored.mimeType, parent = null)
            PhoneMedia(i, asset.id, attachment.description)
        }
        val now = System.currentTimeMillis()
        val text = status.params.text
        val post = PhonePost(
            id = UUID.randomUUID().toString(), stage = PostStages.READY, channelId = channelId, body = text,
            contentWarning = status.params.spoilerText?.ifBlank { null },
            visibility = app.fediferry.data.ScheduledDraft.visibilityOf(status.params.visibility)?.name ?: Visibility.PUBLIC.name,
            finalText = text, readyAt = now, mediaJson = json.encodeToString(MEDIA, media),
        )
        // Only once Mastodon will no longer post it is there a copy here; otherwise it would go out twice.
        runCatching { mastodon.cancelScheduled(account.instance, token, statusId) }.onFailure {
            DebugLog.w(LOG, "Cancelling at Mastodon failed, so the post stays there: ${it.javaClass.simpleName}")
            // Gone from Mastodon's queue: posted already, or deleted there.
            if ((it as? MastodonException)?.code == 404) throw ServerException(404, "not_found")
            throw ServerException(502, it.message ?: "mastodon.error")
        }
        dao.upsertPost(post)
        return dto(post)
    }

    private fun remoteDto(channelId: String, status: ScheduledStatus): PostDto {
        val at = runCatching { Instant.parse(status.scheduledAt).toEpochMilli() }.getOrDefault(0L)
        return PostDto(
            id = RemoteId.encode(channelId, status.id),
            stage = PostStages.SCHEDULED,
            version = 1,
            channelId = channelId,
            body = status.params.text,
            contentWarning = status.params.spoilerText?.ifBlank { null },
            visibility = (status.params.visibility ?: "public").uppercase(),
            media = status.media.mapIndexed { i, m ->
                val url = m.previewUrl ?: m.url.orEmpty()
                PostMediaDto(i, MediaAssetDto(url, "", app.fediferry.data.ScheduledDraft.mimeTypeOf(m), bytes = 0), altText = m.description)
            },
            finalText = status.params.text,
            createdAt = at,
            updatedAt = at,
            publication = PublicationDto(state = PublicationStates.QUEUED, channelId = channelId, publishAfter = at),
        )
    }

    // --- reading ------------------------------------------------------------------------------------

    private suspend fun row(id: String): PhonePost = dao.post(id) ?: throw ServerException(404, "not_found")

    private suspend fun dto(post: PhonePost): PostDto = PostDto(
        id = post.id,
        stage = post.stage,
        version = post.version,
        channelId = post.channelId,
        body = post.body,
        hashtags = Hashtags.parse(post.hashtags),
        addSourceHashtags = post.addSourceHashtags,
        contentWarning = post.contentWarning,
        visibility = post.visibility,
        sourceUrl = post.sourceUrl,
        origin = post.origin,
        sourceFields = fieldsOf(post),
        linkMayIdentify = post.linkMayIdentify,
        media = mediaOf(post).mapNotNull { m -> dao.asset(m.assetId)?.let { PostMediaDto(m.position, assetDto(it), m.altText, m.altFailed) } },
        finalText = post.finalText,
        createdAt = post.createdAt,
        updatedAt = post.updatedAt,
        readyAt = post.readyAt,
        reviewFolderId = post.reviewFolderId,
        labels = labelsOf(post),
        publication = publicationOf(post)?.let { p ->
            PublicationDto(
                state = when (post.stage) {
                    PostStages.PUBLISHING -> PublicationStates.PUBLISHING
                    PostStages.FAILED -> PublicationStates.FAILED
                    else -> PublicationStates.QUEUED
                },
                channelId = post.channelId.orEmpty(),
                scheduleId = p.scheduleId,
                slotAt = p.slotAt,
                publishAfter = p.scheduledAt ?: p.publishAfter,
                attempts = p.attempts,
                failureCode = p.failureCode,
            )
        },
    )

    private fun assetDto(asset: PhoneAsset): MediaAssetDto {
        paths[asset.id] = asset.path
        return MediaAssetDto(asset.id, asset.sha256, asset.mime, asset.width, asset.height, asset.bytes, asset.parentId)
    }

    private suspend fun channelDto(id: String): ChannelDto? = dao.channel(id)?.let { channelDto(it) }

    private suspend fun channelDto(channel: PhoneChannel): ChannelDto {
        val account = channel.accountId?.let { db.accounts().byId(it) }
        return ChannelDto(
            id = channel.id,
            name = channel.name,
            isDefault = channel.isDefault,
            account = account?.let { ChannelAccount(channel.platform ?: Platform.MASTODON.name, it.instance, it.acct, it.displayName, it.avatarUrl) },
            limits = runCatching { json.decodeFromString(PlatformLimits.serializer(), channel.limitsJson) }.getOrDefault(PlatformLimits()),
            post = postOf(channel),
        )
    }

    private suspend fun accountOf(channel: PhoneChannel): Pair<Account, String>? {
        val account = channel.accountId?.let { db.accounts().byId(it) } ?: return null
        val token = tokens.get(account.id) ?: return null
        return account to token
    }

    private suspend fun settingsOf(channelId: String?): PostSettings = channelId?.let { dao.channel(it) }?.let(::postOf) ?: PostSettings()

    private suspend fun staticAlt(channelId: String?): String? =
        settingsOf(channelId).takeIf { it.altTextMode == "STATIC" }?.staticAltText

    private fun inputs(post: PhonePost) = TemplateEngine.Inputs(link = post.sourceUrl, source = ContentSource.fromName(post.origin), fields = fieldsOf(post))

    private fun fieldsOf(post: PhonePost): Map<String, String> = runCatching { json.decodeFromString(FIELDS, post.sourceFieldsJson) }.getOrDefault(emptyMap())
    private fun labelsOf(post: PhonePost): List<String> = runCatching { json.decodeFromString(LABELS, post.labelsJson) }.getOrDefault(emptyList())
    private fun mediaOf(post: PhonePost): List<PhoneMedia> = runCatching { json.decodeFromString(MEDIA, post.mediaJson) }.getOrDefault(emptyList())
    private fun publicationOf(post: PhonePost): PhonePublication? =
        post.publicationJson?.let { runCatching { json.decodeFromString(PhonePublication.serializer(), it) }.getOrNull() }

    // --- pictures on disk -----------------------------------------------------------------------------

    /** The asset for a file already in the vault: the one that has it, or a new one. */
    private suspend fun assetFor(path: String, mime: String?, parent: PhoneAsset?): PhoneAsset = withContext(Dispatchers.IO) {
        val file = File(path)
        val sha = ServiceLocator.mediaVault(context).sha256Of(file)
        dao.assetBySha(sha)?.let { return@withContext it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(path, it) }
        PhoneAsset(
            UUID.randomUUID().toString(), path, sha, mime ?: "image/jpeg",
            bounds.outWidth.takeIf { it > 0 }, bounds.outHeight.takeIf { it > 0 }, file.length(), parent?.id,
        ).also { dao.upsertAsset(it) }
    }

    private suspend fun storeBitmap(bitmap: Bitmap, mime: String, parent: PhoneAsset): PhoneAsset {
        val stored = ServiceLocator.mediaVault(context).store(bitmap, mime).getOrElse { throw ServerException(500, "media.store") }
        return assetFor(stored.file.absolutePath, stored.mimeType, parent)
    }

    private suspend fun sameAs(assetId: String, other: PhoneAsset): Boolean = assetId == other.id

    /** Deletes a post and every picture no other post uses: its own, and the originals they were edited from. */
    private suspend fun purge(post: PhonePost) {
        PhonePublishWorker.cancel(context, post.id)
        dao.deletePost(post.id)
        val used = HashSet<String>()
        dao.allPosts().forEach { other -> mediaOf(other).forEach { m -> collectLineage(m.assetId, used) } }
        val mine = HashSet<String>().also { set -> mediaOf(post).forEach { collectLineage(it.assetId, set) } }
        (mine - used).forEach { id ->
            val asset = dao.asset(id) ?: return@forEach
            dao.deleteAsset(id)
            // A share still on its way in can hold the same file (they are named by content).
            if (!db.items().anyUsingPath(asset.path)) withContext(Dispatchers.IO) { File(asset.path).delete() }
            paths.remove(id)
        }
    }

    private suspend fun collectLineage(assetId: String, into: MutableSet<String>) {
        var current: String? = assetId
        while (current != null && into.add(current)) current = dao.asset(current)?.parentId
    }

    // --- slots ----------------------------------------------------------------------------------------

    /** Slot times taken per channel: what Mastodon holds, and what waits here. */
    private suspend fun takenSlots(): Map<String, Set<Instant>> {
        val remote = mastodonQueue().mapNotNull { p -> p.publication?.let { p.channelId.orEmpty() to Instant.ofEpochMilli(it.publishAfter) } }
        val local = dao.posts(listOf(PostStages.SCHEDULED, PostStages.PUBLISHING)).mapNotNull { p ->
            publicationOf(p)?.slotAt?.let { p.channelId.orEmpty() to Instant.ofEpochMilli(it) }
        }
        return (remote + local).groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    private suspend fun nextSlot(channelId: String, scheduleId: String?): Pair<PhoneSchedule, Long> {
        val schedules = dao.schedules().filter { if (scheduleId != null) it.id == scheduleId else it.channelId == channelId && it.active }
        if (schedules.isEmpty()) throw ServerException(409, "plan.no_schedule")
        val taken = takenSlots()[channelId].orEmpty()
        // Mastodon takes nothing sooner than five minutes ahead.
        val from = Instant.ofEpochMilli(System.currentTimeMillis() + PhonePublishWorker.MIN_MASTODON_LEAD_MS)
        return schedules.mapNotNull { s -> SlotPlanner.nextFree(zoneOf(s.timezone), slotsOf(s).map(::toSlot), from, taken)?.let { s to it.toEpochMilli() } }
            .minByOrNull { it.second } ?: throw ServerException(409, "plan.no_free_slot")
    }

    private fun scheduleDto(row: PhoneSchedule, taken: Set<Instant>): ScheduleDto {
        val slots = slotsOf(row)
        val nextFree = if (row.active) {
            SlotPlanner.upcoming(zoneOf(row.timezone), slots.map(::toSlot), Instant.now()).filter { it !in taken }.take(3).map { it.toEpochMilli() }.toList()
        } else {
            emptyList()
        }
        return ScheduleDto(row.id, row.channelId, row.name, row.timezone, row.active, slots, nextFree)
    }

    private fun slotsOf(row: PhoneSchedule): List<SlotDto> = runCatching { json.decodeFromString(SLOTS, row.slotsJson) }.getOrDefault(emptyList())

    private fun validSlots(slots: List<SlotDto>): List<SlotDto> = slots.map { s ->
        val time = runCatching { LocalTime.parse(s.time) }.getOrElse { throw ServerException(400, "bad_request") }
        if (s.days.any { it !in 1..7 }) throw ServerException(400, "bad_request")
        SlotDto(s.days.distinct().sorted(), "%02d:%02d".format(time.hour, time.minute))
    }

    private fun toSlot(s: SlotDto) = Slot(s.days.map(DayOfWeek::of).toSet(), LocalTime.parse(s.time))

    private fun zoneOf(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrElse { throw ServerException(400, "bad_request") }

    /** A post Mastodon holds, as an id the screens can carry: its channel and Mastodon's own id. */
    private object RemoteId {
        private const val PREFIX = "mastodon-"
        fun encode(channelId: String, statusId: String): String =
            PREFIX + statusId + "-" + android.util.Base64.encodeToString(channelId.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)

        fun parse(id: String): Pair<String, String>? {
            if (!id.startsWith(PREFIX)) return null
            val rest = id.removePrefix(PREFIX)
            val statusId = rest.substringBefore('-')
            val channel = runCatching { String(android.util.Base64.decode(rest.substringAfter('-'), android.util.Base64.URL_SAFE)) }.getOrNull() ?: return null
            return channel to statusId
        }
    }

    companion object {
        private const val LOG = "phone"
        private const val LOCK_MS = 60 * 60 * 1000L
        private const val MAX_LABEL = 40
        private const val MAX_UNDO_SECONDS = 60
        private const val PAST_GRACE_MS = 60_000L

        /** A link and its screenshot shared within this long of each other become one draft. */
        const val PAIRING_WINDOW_MS = 10 * 60 * 1000L

        val DELETABLE = setOf(PostStages.DRAFT, PostStages.READY, PostStages.FAILED)
        val PLANNABLE = setOf(PostStages.READY, PostStages.SCHEDULED, PostStages.FAILED)

        /** The tables behind each kind of change the screens follow. */
        private val TABLES = mapOf(
            "phone_posts" to "post",
            "phone_channels" to "channel",
            "accounts" to "channel",
            "phone_schedules" to "schedule",
            "phone_review_folders" to "review_folder",
        )

        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        internal val MEDIA = ListSerializer(PhoneMedia.serializer())
        private val LABELS = ListSerializer(String.serializer())
        private val FIELDS = MapSerializer(String.serializer(), String.serializer())
        private val SLOTS = ListSerializer(SlotDto.serializer())

        fun postOf(channel: PhoneChannel): PostSettings =
            runCatching { json.decodeFromString(PostSettings.serializer(), channel.postJson) }.getOrDefault(PostSettings())
    }
}
