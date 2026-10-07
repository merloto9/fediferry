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

import app.fediferry.alt.VisionAltTextProvider
import app.fediferry.api.AiModelData
import app.fediferry.api.AltSuggestion
import app.fediferry.api.ChannelDefaults
import app.fediferry.api.CreatePost
import app.fediferry.api.LockDto
import app.fediferry.api.PlaceholderKeyData
import app.fediferry.api.PostDto
import app.fediferry.api.PostMediaDto
import app.fediferry.api.PostPatch
import app.fediferry.api.PostStages
import app.fediferry.api.LabelDto
import app.fediferry.api.ReviewFolderDto
import app.fediferry.api.ReviewFolderInput
import app.fediferry.api.ReviewPatch
import app.fediferry.channel.PostValidator
import app.fediferry.api.SettingsKinds
import app.fediferry.api.TemplateData
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Hashtags
import app.fediferry.server.db.Post
import app.fediferry.template.TemplateEngine
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * Posts and their drafts. A draft is edited by one device at a time — it
 * holds the post's lock — and every change names the version it was made on,
 * so an edit based on an old copy is refused rather than overwriting another.
 */
class Posts(
    private val storage: Storage,
    private val feed: ChangeFeed,
    private val settings: SettingsStore,
    private val secrets: Secrets,
    private val media: MediaStore,
    private val http: OkHttpClient,
    private val clock: () -> Long,
) {
    private val db get() = storage.db
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- reading ------------------------------------------------------------------

    fun get(projectId: String, id: String): PostDto = dto(projectId, row(projectId, id))

    /** Posts in any of [stages], most recently changed first. */
    fun list(projectId: String, stages: Collection<String>): List<PostDto> =
        db.postQueries.byStages(projectId, stages).executeAsList().map { dto(projectId, it) }

    private fun row(projectId: String, id: String): Post =
        db.postQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("post")

    private fun dto(projectId: String, post: Post): PostDto {
        val lock = db.postLockQueries.byPost(projectId, post.id).executeAsOneOrNull()?.takeIf { it.expires_at > clock() }
        return PostDto(
            id = post.id,
            stage = post.stage,
            version = post.version,
            channelId = post.channel_id,
            templateId = post.template_id,
            body = post.body,
            hashtags = Hashtags.parse(post.hashtags),
            addSourceHashtags = post.add_source_hashtags != 0L,
            contentWarning = post.content_warning,
            visibility = post.visibility,
            sourceUrl = post.source_url,
            origin = post.origin,
            sourceFields = runCatching { json.decodeFromString<Map<String, String>>(post.source_fields_json) }.getOrDefault(emptyMap()),
            linkMayIdentify = post.link_may_identify != 0L,
            media = db.postMediaQueries.forPost(projectId, post.id).executeAsList().mapNotNull { m ->
                media.byId(projectId, m.asset_id)?.let { PostMediaDto(m.position.toInt(), Library.assetDto(it), m.library_item_id, m.alt_text, m.alt_failed != 0L) }
            },
            finalText = post.final_text,
            lock = lock?.let { LockDto(it.device_id, it.device_name, it.expires_at) },
            createdAt = post.created_at,
            updatedAt = post.updated_at,
            readyAt = post.ready_at,
            reviewFolderId = post.review_folder_id,
            labels = db.postLabelQueries.forPost(projectId, post.id).executeAsList(),
            publication = db.publicationQueries.byPost(projectId, post.id).executeAsOneOrNull()?.let(Publishing::dto),
        )
    }

    // --- making -------------------------------------------------------------------

    /**
     * A draft from library items: the first item with a source feeds the
     * template; every item with a picture adds it, in the order given.
     */
    fun create(projectId: String, deviceId: String?, request: CreatePost): PostDto {
        val items = request.libraryItemIds.distinct().map { id ->
            db.libraryItemQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("library_item")
        }
        if (items.isEmpty()) throw ApiException.badRequest("library_items")
        val channel = (request.channelId ?: defaultChannelId(projectId))?.let { db.channelQueries.byId(projectId, it).executeAsOneOrNull() }
        val channelDefaults = channel?.let { runCatching { json.decodeFromString<ChannelDefaults>(it.defaults_json) }.getOrNull() }
        val template = template(projectId, request.templateId ?: channelDefaults?.templateId)
        val source = items.firstOrNull { it.origin != null || it.source_url != null }
        val fields = source?.source_fields_json?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()
        val inputs = TemplateEngine.Inputs(link = source?.source_url, source = ContentSource.fromName(source?.origin), fields = fields)
        val keys = keys(projectId)

        val id = UUID.randomUUID().toString()
        val now = clock()
        feed.change(projectId, "post", id) {
            db.postQueries.insert(
                projectId, id, PostStages.DRAFT, channel?.id, template.id,
                TemplateEngine.render(template, keys, inputs),
                Hashtags.format(TemplateEngine.hashtagsFor(template, keys, inputs)),
                if (template.addSourceHashtags) 1 else 0,
                template.contentWarning,
                channelDefaults?.visibility ?: template.visibility,
                source?.source_url, source?.origin, json.encodeToString(fields),
                source?.link_may_identify ?: 0, deviceId, now, now,
            )
            var position = 0
            items.filter { it.asset_id != null }.forEach { item ->
                val alt = item.alt_suggestion ?: template.staticAltText?.takeIf { template.altTextMode == "STATIC" }
                db.postMediaQueries.insert(projectId, id, (position++).toLong(), item.asset_id!!, item.id, alt, 0)
            }
        }
        return get(projectId, id)
    }

    // --- editing --------------------------------------------------------------------

    /** Takes or renews the lock for [deviceId]; another device's live lock is taken only when [force]d. */
    fun lock(projectId: String, postId: String, deviceId: String?, force: Boolean): LockDto {
        val device = deviceId ?: throw ApiException(HttpStatusCode.BadRequest, "device.required")
        val post = row(projectId, postId)
        if (post.stage != PostStages.DRAFT) throw ApiException(HttpStatusCode.Conflict, "post.not_draft", mapOf("stage" to post.stage))
        val held = db.postLockQueries.byPost(projectId, postId).executeAsOneOrNull()
        if (held != null && held.device_id != device && held.expires_at > clock() && !force) throw locked(held.device_name, held.expires_at)
        val name = db.deviceQueries.byId(projectId, device).executeAsOneOrNull()?.name ?: device
        val expires = clock() + LOCK_TTL_MS
        feed.change(projectId, "post", postId) { db.postLockQueries.upsert(projectId, postId, device, name, expires) }
        return LockDto(device, name, expires)
    }

    fun unlock(projectId: String, postId: String, deviceId: String?) {
        val device = deviceId ?: return
        feed.change(projectId, "post", postId) { db.postLockQueries.release(projectId, postId, device) }
    }

    fun patch(projectId: String, postId: String, deviceId: String?, version: Long?, patch: PostPatch): PostDto {
        val post = row(projectId, postId)
        requireEditable(projectId, post, deviceId, version)
        val keys = keys(projectId)
        val templateId = patch.templateId ?: post.template_id
        var body = patch.body ?: post.body
        var hashtags = patch.hashtags?.let(Hashtags::format) ?: post.hashtags
        val addSource = patch.addSourceHashtags ?: (post.add_source_hashtags != 0L)
        if (patch.rerender) {
            val template = template(projectId, templateId)
            val fields = runCatching { json.decodeFromString<Map<String, String>>(post.source_fields_json) }.getOrDefault(emptyMap())
            val inputs = TemplateEngine.Inputs(link = post.source_url, source = ContentSource.fromName(post.origin), fields = fields)
            body = TemplateEngine.render(template, keys, inputs)
            hashtags = Hashtags.format(TemplateEngine.hashtagsFor(template, keys, inputs, addSource))
        }
        val channelId = when (val c = patch.channelId) {
            null -> post.channel_id
            "" -> null
            else -> c.also { db.channelQueries.byId(projectId, it).executeAsOneOrNull() ?: throw ApiException.notFound("channel") }
        }
        feed.change(projectId, "post", postId) {
            db.postQueries.update(
                channelId, templateId, body, hashtags, if (addSource) 1 else 0,
                patch.contentWarning?.let { it.ifBlank { null } } ?: post.content_warning.takeIf { patch.contentWarning == null },
                patch.visibility ?: post.visibility,
                clock(), projectId, postId,
            )
            patch.media?.let { applyMedia(projectId, postId, it) }
        }
        return get(projectId, postId)
    }

    private fun applyMedia(projectId: String, postId: String, changes: List<app.fediferry.api.PostMediaPatch>) {
        for (change in changes) {
            if (change.remove) {
                db.postMediaQueries.remove(projectId, postId, change.position.toLong())
                continue
            }
            change.assetId?.let { assetId ->
                media.byId(projectId, assetId) ?: throw ApiException.notFound("media")
                db.postMediaQueries.setAsset(assetId, projectId, postId, change.position.toLong())
            }
            change.altText?.let { db.postMediaQueries.setAlt(it.ifBlank { null }, projectId, postId, change.position.toLong()) }
        }
        // Keep positions 0..n-1 after a removal.
        db.postMediaQueries.forPost(projectId, postId).executeAsList().forEachIndexed { i, m ->
            if (m.position != i.toLong()) db.postMediaQueries.renumber(i.toLong(), projectId, postId, m.position)
        }
    }

    // --- review --------------------------------------------------------------------

    /**
     * Freezes a draft: checks it against its channel, writes the text as it
     * will be posted — `{tags}` filled in — and lets go of the edit lock. A
     * post the channel would not take is refused with every reason at once.
     */
    fun markReady(projectId: String, postId: String, deviceId: String?, version: Long?): PostDto {
        val post = row(projectId, postId)
        if (post.stage != PostStages.DRAFT) throw ApiException(HttpStatusCode.Conflict, "post.not_draft", mapOf("stage" to post.stage))
        val held = db.postLockQueries.byPost(projectId, postId).executeAsOneOrNull()?.takeIf { it.expires_at > clock() }
        if (held != null && held.device_id != deviceId) throw locked(held.device_name, held.expires_at)
        if (version != null && version != post.version) {
            throw ApiException(HttpStatusCode.Conflict, "post.version_conflict", mapOf("current" to post.version.toString()))
        }
        val dto = dto(projectId, post)
        val channel = post.channel_id?.let { db.channelQueries.byId(projectId, it).executeAsOneOrNull() }?.let(Channels::dto)
        val blocking = PostValidator.check(dto, channel, keys(projectId).map { it.name }).filter { it.blocking }
        if (blocking.isNotEmpty()) throw ApiException(HttpStatusCode.UnprocessableEntity, "post.not_ready", violations = blocking)
        val now = clock()
        feed.change(projectId, "post", postId) {
            db.postQueries.markReady(PostValidator.finalText(dto), now, now, projectId, postId)
            db.postLockQueries.clear(projectId, postId)
        }
        return get(projectId, postId)
    }

    /** Back to draft, while nothing has been scheduled for it. */
    fun backToDraft(projectId: String, postId: String): PostDto {
        val post = row(projectId, postId)
        if (post.stage == PostStages.DRAFT) return dto(projectId, post)
        if (post.stage != PostStages.READY) throw ApiException(HttpStatusCode.Conflict, "post.not_ready_stage", mapOf("stage" to post.stage))
        feed.change(projectId, "post", postId) { db.postQueries.markDraft(clock(), projectId, postId) }
        return get(projectId, postId)
    }

    /** Sorts a ready (or later) post: its review folder and labels. The post itself stays as it is. */
    fun sortForReview(projectId: String, postId: String, patch: ReviewPatch): PostDto {
        val post = row(projectId, postId)
        if (post.stage == PostStages.DRAFT) throw ApiException(HttpStatusCode.Conflict, "post.not_ready_stage", mapOf("stage" to post.stage))
        val folder = when (val f = patch.folderId) {
            null -> post.review_folder_id
            "" -> null
            else -> f.also { db.reviewFolderQueries.byId(projectId, it).executeAsOneOrNull() ?: throw ApiException.notFound("review_folder") }
        }
        feed.change(projectId, "post", postId) {
            db.postQueries.setReviewFolder(folder, clock(), projectId, postId)
            patch.labels?.let { labels ->
                db.postLabelQueries.clear(projectId, postId)
                labels.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
                    .forEach { db.postLabelQueries.add(projectId, postId, it.take(MAX_LABEL)) }
            }
        }
        return get(projectId, postId)
    }

    fun reviewFolders(projectId: String): List<ReviewFolderDto> =
        db.reviewFolderQueries.all(projectId).executeAsList().map { ReviewFolderDto(it.id, it.name, it.sort_order.toInt()) }

    fun createReviewFolder(projectId: String, input: ReviewFolderInput): ReviewFolderDto {
        val name = input.name.trim().takeIf { it.isNotEmpty() } ?: throw ApiException.badRequest("name")
        val id = UUID.randomUUID().toString()
        val now = clock()
        val order = input.sortOrder ?: db.reviewFolderQueries.count(projectId).executeAsOne().toInt()
        feed.change(projectId, "review_folder", id) { db.reviewFolderQueries.insert(projectId, id, name, order.toLong(), now, now) }
        return ReviewFolderDto(id, name, order)
    }

    fun updateReviewFolder(projectId: String, id: String, input: ReviewFolderInput): ReviewFolderDto {
        val folder = db.reviewFolderQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("review_folder")
        val name = input.name.trim().takeIf { it.isNotEmpty() } ?: folder.name
        val order = input.sortOrder?.toLong() ?: folder.sort_order
        feed.change(projectId, "review_folder", id) { db.reviewFolderQueries.update(name, order, clock(), projectId, id) }
        return ReviewFolderDto(id, name, order.toInt())
    }

    /** Deletes a folder; its posts stay, out of any folder. */
    fun deleteReviewFolder(projectId: String, id: String) {
        db.reviewFolderQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("review_folder")
        feed.change(projectId, "review_folder", id, deleted = true) {
            db.postQueries.releaseReviewFolder(clock(), projectId, id)
            db.reviewFolderQueries.delete(projectId, id)
        }
    }

    fun labels(projectId: String): List<LabelDto> =
        db.postLabelQueries.counts(projectId).executeAsList().map { LabelDto(it.name, it.uses.toInt()) }

    fun delete(projectId: String, postId: String, deviceId: String?) {
        val post = row(projectId, postId)
        // Planned or being sent: take it out of the queue first. Out already: it leaves the history only.
        if (post.stage !in DELETABLE) throw ApiException(HttpStatusCode.Conflict, "post.not_deletable", mapOf("stage" to post.stage))
        val held = db.postLockQueries.byPost(projectId, postId).executeAsOneOrNull()
        if (held != null && held.device_id != deviceId && held.expires_at > clock()) throw locked(held.device_name, held.expires_at)
        val now = clock()
        feed.change(projectId, "post", postId, deleted = true) {
            db.postQueries.softDelete(now, now, projectId, postId)
            db.postLockQueries.clear(projectId, postId)
            if (post.stage == PostStages.FAILED) db.publicationQueries.delete(projectId, postId)
        }
    }

    private fun requireEditable(projectId: String, post: Post, deviceId: String?, version: Long?) {
        if (post.stage != PostStages.DRAFT) throw ApiException(HttpStatusCode.Conflict, "post.not_draft", mapOf("stage" to post.stage))
        val held = db.postLockQueries.byPost(projectId, post.id).executeAsOneOrNull()?.takeIf { it.expires_at > clock() }
        if (held == null || held.device_id != deviceId) {
            throw held?.let { locked(it.device_name, it.expires_at) } ?: ApiException(HttpStatusCode.Locked, "post.lock_required")
        }
        if (version == null || version != post.version) {
            throw ApiException(HttpStatusCode.Conflict, "post.version_conflict", mapOf("current" to post.version.toString()))
        }
    }

    private fun locked(device: String, until: Long) =
        ApiException(HttpStatusCode.Locked, "post.locked", mapOf("device" to device, "until" to until.toString()))

    // --- alt text -----------------------------------------------------------------

    /** Asks the project's alt-text model to describe one picture; the client decides whether to keep it. */
    suspend fun suggestAlt(projectId: String, postId: String, position: Int): AltSuggestion {
        val m = db.postMediaQueries.forPost(projectId, postId).executeAsList().firstOrNull { it.position == position.toLong() }
            ?: throw ApiException.notFound("post_media")
        val asset = media.byId(projectId, m.asset_id) ?: throw ApiException.notFound("media")
        val model = settings.list(projectId, SettingsKinds.AI_MODEL)
            .mapNotNull { runCatching { json.decodeFromJsonElement(AiModelData.serializer(), it.data) }.getOrNull() }
            .filter { it.kind == "ALT_TEXT" }
            .let { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
            ?: throw ApiException(HttpStatusCode.Conflict, "alt.no_model")
        val key = secrets.get(projectId, "ai-model:${model.id}").orEmpty()
        val bytes = media.file(projectId, asset.sha256).readBytes()
        val text = VisionAltTextProvider(http, model.endpoint, model.model, key, VISION_PROMPT).describe(bytes, asset.mime)
            .getOrElse { throw ApiException(HttpStatusCode.BadGateway, "alt.failed", mapOf("reason" to (it.message ?: it.javaClass.simpleName))) }
        return AltSuggestion(text)
    }

    // --- settings ------------------------------------------------------------------

    private fun template(projectId: String, id: String?): TemplateData {
        val all = settings.list(projectId, SettingsKinds.TEMPLATE)
            .mapNotNull { runCatching { json.decodeFromJsonElement(TemplateData.serializer(), it.data) }.getOrNull() }
        return all.firstOrNull { it.id == id } ?: all.firstOrNull { it.isDefault } ?: all.firstOrNull() ?: TemplateData.FALLBACK
    }

    private fun keys(projectId: String): List<PlaceholderKeyData> = settings.list(projectId, SettingsKinds.PLACEHOLDER_KEY)
        .mapNotNull { runCatching { json.decodeFromJsonElement(PlaceholderKeyData.serializer(), it.data) }.getOrNull() }

    private fun defaultChannelId(projectId: String): String? =
        db.channelQueries.all(projectId).executeAsList().let { list -> list.firstOrNull { it.is_default != 0L } ?: list.firstOrNull() }?.id

    companion object {
        const val LOCK_TTL_MS = 5 * 60 * 1000L
        const val MAX_LABEL = 40
        val DELETABLE = setOf(PostStages.DRAFT, PostStages.READY, PostStages.FAILED, PostStages.PUBLISHED)
        const val VISION_PROMPT = "Describe this image for a blind reader in one or two plain sentences. " +
            "Transcribe any text in the image verbatim. No preamble."
    }
}
