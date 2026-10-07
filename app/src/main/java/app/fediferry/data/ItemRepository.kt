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
package app.fediferry.data

import app.fediferry.template.inputsOf
import android.content.Context
import androidx.annotation.StringRes
import app.fediferry.R
import app.fediferry.mastodon.MastodonException
import app.fediferry.mastodon.ScheduledStatus
import app.fediferry.data.db.AccountDao
import app.fediferry.data.db.CleanupDao
import app.fediferry.data.db.ItemDao
import app.fediferry.data.db.PlaceholderKeyDao
import app.fediferry.data.db.TemplateDao
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.data.model.Template
import app.fediferry.link.CleanedLink
import app.fediferry.link.LinkResolver
import app.fediferry.log.DebugLog
import app.fediferry.link.MediaFetcher
import app.fediferry.media.ScreenshotAnalyzer
import app.fediferry.media.ScreenshotCropper
import app.fediferry.media.cleanup.BitmapCleaner
import app.fediferry.media.cleanup.CleanupRule
import app.fediferry.media.cleanup.ImageEditProvider
import app.fediferry.media.cleanup.MaskPolarity
import app.fediferry.media.cleanup.NoImageEditProvider
import app.fediferry.share.SharePayload
import app.fediferry.template.TemplateEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * The single ingest path. Every share, in every mode, is persisted here before
 * anything else happens — that is what makes the zero-touch path recoverable
 * rather than silently lossy.
 */
class ItemRepository(
    private val items: ItemDao,
    private val templates: TemplateDao,
    private val accounts: AccountDao,
    private val media: MediaVault,
    private val cleanupDao: CleanupDao? = null,
    private val editProvider: suspend () -> ImageEditProvider = { NoImageEditProvider },
    private val maskPolarity: suspend () -> MaskPolarity = { MaskPolarity.TRANSPARENT_HOLE },
    private val editInstruction: suspend () -> String = { "" },
    private val resolvers: List<LinkResolver> = emptyList(),
    private val fetcher: MediaFetcher = MediaFetcher { Result.failure(UnsupportedOperationException()) },
    private val placeholderKeys: PlaceholderKeyDao? = null,
    /** For the messages that reach the user, in the app's language. */
    private val context: Context? = null,
) {

    /** A message for the user; without a [context] (tests) its resource id stands in. */
    private fun text(@StringRes id: Int, vararg args: Any): String =
        context?.getString(id, *args) ?: "string#$id ${args.joinToString()}".trim()

    private suspend fun keys() = placeholderKeys?.all().orEmpty()

    /** Renders with the user's own placeholders as well as the built-in ones. */
    private suspend fun render(template: Template, inputs: TemplateEngine.Inputs): String =
        TemplateEngine.render(template, keys(), inputs)

    /** The hashtags a post starts with, stored the way [Item.hashtags] keeps them. */
    private suspend fun hashtagsFor(
        template: Template,
        inputs: TemplateEngine.Inputs,
        withSource: Boolean = template.addSourceHashtags,
    ): String = Hashtags.format(TemplateEngine.hashtagsFor(template, keys(), inputs, withSource))

    /**
     * [after], with the body and hashtags it should have now that it knows
     * more than [before] did — a link, or its source's data. Each is only
     * rewritten while it is still exactly what the template produced: anything
     * else means the user edited it, and their choice is not ours to overwrite.
     */
    private suspend fun refreshed(before: Item, after: Item): Item {
        val template = resolveTemplate(before.templateId)
        val body = if (before.bodyText == render(template, TemplateEngine.inputsOf(before))) {
            render(template, TemplateEngine.inputsOf(after))
        } else {
            before.bodyText
        }
        val hashtags = when (before.hashtags) {
            // A draft from before per-post hashtags: its text already has them.
            null -> null
            // The post's own choice decides whether the source's tags join now.
            hashtagsFor(template, TemplateEngine.inputsOf(before), before.addSourceHashtags) ->
                hashtagsFor(template, TemplateEngine.inputsOf(after), before.addSourceHashtags)
            else -> before.hashtags
        }
        return after.copy(bodyText = body, hashtags = hashtags)
    }

    fun observeInbox(): Flow<List<Item>> = items.observeInbox()
    fun observeHistory(): Flow<List<Item>> = items.observeHistory()
    fun observe(id: String): Flow<Item?> = items.observe(id)

    suspend fun byId(id: String): Item? = items.byId(id)

    /**
     * Ingests one shared payload into a persisted [Item], reporting how it was
     * resolved so the caller can say something useful about it.
     */
    data class Ingested(val item: Item, val outcome: Outcome) {
        enum class Outcome {
            /** A new item, complete as shared. */
            CREATED,

            /** The same screenshot was already pending; folded into that item. */
            DUPLICATE,

            /** Joined to the other half of a two-step Instagram share. */
            PAIRED,

            /** A permalink with no image yet — a screenshot can still join it. */
            AWAITING_MEDIA,
        }
    }

    /**
     * Ingests one shared payload into a persisted [Item].
     *
     * Instagram's share sheet only ever hands over a permalink, so getting an
     * image and its attribution into one post takes two shares: the link, then a
     * screenshot. Rather than leaving two unrelated drafts behind, a share that
     * supplies the half another recent draft is missing joins that draft instead
     * of starting its own. See [PAIRING_WINDOW_MS].
     *
     * If the same screenshot was already ingested and is still pending, that item
     * is returned instead of a duplicate — sharing a screenshot twice by accident
     * should not produce two posts.
     */
    suspend fun ingest(
        payload: SharePayload,
        templateId: String? = null,
        status: Status = Status.DRAFT,
    ): Result<Ingested> = runCatching {
        val template = resolveTemplate(templateId)

        val stored = payload.imageUris.firstOrNull()
            ?.let { uri -> media.ingest(uri).getOrThrow() }

        stored?.sha256?.let { hash ->
            items.draftByMediaHash(hash)?.let { existing ->
                // Same bytes, still an unsent draft: fold the new share into it
                // rather than creating a second copy.
                val merged = existing.copy(sourceUrl = existing.sourceUrl ?: payload.link)
                if (merged != existing) items.update(merged)
                return@runCatching Ingested(merged, Ingested.Outcome.DUPLICATE)
            }
        }

        pair(payload, stored, status)?.let { return@runCatching it }

        val inputs = TemplateEngine.Inputs(link = payload.link)
        val body = render(template, inputs)

        val item = Item(
            id = UUID.randomUUID().toString(),
            mediaPath = stored?.file?.absolutePath,
            mediaHash = stored?.sha256,
            mimeType = stored?.mimeType,
            sourceUrl = payload.link,
            bodyText = body,
            hashtags = hashtagsFor(template, inputs),
            addSourceHashtags = template.addSourceHashtags,
            altText = null,
            contentWarning = template.contentWarning,
            visibility = template.visibility,
            templateId = template.id,
            accountId = template.accountId ?: accounts.defaultAccount()?.id,
            status = status,
        )
        items.upsert(item)

        val outcome = if (stored == null && payload.link != null) {
            Ingested.Outcome.AWAITING_MEDIA
        } else {
            Ingested.Outcome.CREATED
        }
        Ingested(item, outcome)
    }

    /**
     * Joins this share to a recent draft that is missing exactly what it carries,
     * or returns null when there is nothing to join.
     *
     * A share that carries both halves is self-contained and never pairs — only a
     * share supplying precisely the missing half is unambiguous enough to merge.
     */
    private suspend fun pair(
        payload: SharePayload,
        stored: MediaVault.Stored?,
        status: Status,
    ): Ingested? {
        val since = System.currentTimeMillis() - PAIRING_WINDOW_MS

        if (stored != null && payload.link == null) {
            val waiting = items.latestAwaitingMedia(since) ?: return null
            // Its body already rendered with the link, so it needs no rewrite.
            val merged = waiting.copy(
                mediaPath = stored.file.absolutePath,
                mediaHash = stored.sha256,
                mimeType = stored.mimeType,
                status = status,
            )
            items.update(merged)
            return Ingested(merged, Ingested.Outcome.PAIRED)
        }

        if (stored == null && payload.link != null) {
            val waiting = items.latestAwaitingLink(since) ?: return null
            val merged = refreshed(waiting, waiting.copy(sourceUrl = payload.link, status = status))
            items.update(merged)
            return Ingested(merged, Ingested.Outcome.PAIRED)
        }

        return null
    }

    suspend fun update(item: Item) = items.update(item)

    /**
     * Turns a picture picked in the Sources space into an item, so it lands in
     * the same editor as a share or a screenshot.
     *
     * The post's text and channel become its YouTube fields, for the user's
     * placeholders to map, and its permalink feeds `{link}`. An identical picture already
     * waiting as a draft is reused rather than duplicated, the same rule a
     * repeated share follows.
     */
    suspend fun ingestFromSource(
        imageUrl: String,
        permalink: String,
        fields: Map<String, String>,
        templateId: String? = null,
        status: Status = Status.DRAFT,
    ): Result<Item> = runCatching {
        val template = resolveTemplate(templateId)
        val inputs = TemplateEngine.Inputs(link = permalink, source = ContentSource.YOUTUBE, fields = fields)
        val bytes = fetcher.fetch(imageUrl).getOrThrow()
        val stored = media.store(bytes, mimeOf(imageUrl)).getOrThrow()

        items.draftByMediaHash(stored.sha256)?.let { return@runCatching it }

        val item = Item(
            id = UUID.randomUUID().toString(),
            mediaPath = stored.file.absolutePath,
            mediaHash = stored.sha256,
            mimeType = stored.mimeType,
            sourceUrl = permalink,
            bodyText = render(template, inputs),
            hashtags = hashtagsFor(template, inputs),
            addSourceHashtags = template.addSourceHashtags,
            origin = ContentSource.YOUTUBE,
            sourceFields = fields,
            contentWarning = template.contentWarning,
            visibility = template.visibility,
            templateId = template.id,
            accountId = template.accountId ?: accounts.defaultAccount()?.id,
            status = status,
        )
        items.upsert(item)
        item
    }

    /**
     * A new draft in the inbox from a post Mastodon holds for later: its text,
     * hashtags, content warning, visibility, account, and its picture with the
     * alt text. Mastodon cannot change a scheduled post's text or picture, so
     * this is how one gets edited. The scheduled post itself is not touched.
     *
     * The picture is downloaded from the server; if that fails, so does this,
     * and the scheduled post is still there to try again. A draft with the
     * same picture already in the inbox is returned instead of a second one.
     */
    suspend fun draftFromScheduled(accountId: String, status: ScheduledStatus): Result<Item> = runCatching {
        val template = resolveTemplate(null)
        val attachment = status.media.firstOrNull()
        val stored = attachment?.url?.let { url ->
            val bytes = fetcher.fetch(url).getOrElse { throw MastodonException(text(R.string.repo_download_failed, it.message.orEmpty())) }
            media.store(bytes, ScheduledDraft.mimeTypeOf(attachment)).getOrThrow()
        }
        stored?.let { items.draftByMediaHash(it.sha256) }?.let { return@runCatching it }

        // Its hashtags were written out when it was scheduled; they become the
        // post's own again, ticked, with {tags} where they stood.
        val (body, tags) = Hashtags.adoptFromText(status.params.text)
        val item = Item(
            id = UUID.randomUUID().toString(),
            mediaPath = stored?.file?.absolutePath,
            mediaHash = stored?.sha256,
            mimeType = stored?.mimeType,
            altText = attachment?.description?.takeIf { it.isNotBlank() },
            bodyText = body,
            hashtags = Hashtags.format(tags),
            addSourceHashtags = false,
            contentWarning = status.params.spoilerText?.takeIf { it.isNotBlank() },
            visibility = ScheduledDraft.visibilityOf(status.params.visibility) ?: template.visibility,
            templateId = template.id,
            accountId = accountId,
            status = Status.DRAFT,
        )
        items.upsert(item)
        item
    }

    /** YouTube serves WebP from its image CDN whatever the extension suggests. */
    private fun mimeOf(url: String): String = when {
        url.contains(".png", ignoreCase = true) -> "image/png"
        url.contains(".gif", ignoreCase = true) -> "image/gif"
        url.contains("webp", ignoreCase = true) -> "image/webp"
        else -> "image/jpeg"
    }

    /**
     * Replaces the item's link with one that no longer identifies whoever
     * shared it, where its service puts that in links (Pinterest's sender).
     * The clean link is checked before it is used; when it cannot be, the
     * original is kept and the item is flagged so the user is warned.
     *
     * The body is written again only while it is still the template's own
     * output, like every other late change to an item.
     */
    /** The service whose links carry the sharer and get cleaned, for this item; no I/O. */
    fun linkCleaningService(item: Item): String? {
        val url = item.sourceUrl ?: return null
        return resolvers.firstOrNull { it.handles(url) }?.takeIf { it.cleansLinks }?.serviceName
    }

    suspend fun cleanLink(item: Item): Item {
        val url = item.sourceUrl ?: return item
        val resolver = resolvers.firstOrNull { it.handles(url) } ?: return item
        return when (val cleaned = resolver.cleanLink(url)) {
            CleanedLink.Unchanged -> item
            is CleanedLink.Clean -> {
                DebugLog.d(LOG, "${resolver.serviceName} link cleaned of sharer details")
                refreshed(item, item.copy(sourceUrl = cleaned.url, linkMayIdentify = false)).also { items.update(it) }
            }
            is CleanedLink.MayIdentify -> {
                // Only that it happened and why — never the link.
                DebugLog.w(LOG, "${resolver.serviceName} link kept as shared: ${cleaned.reason}")
                item.copy(linkMayIdentify = true).also { items.update(it) }
            }
        }
    }

    private fun cleaningResolver(url: String): LinkResolver? =
        resolvers.firstOrNull { it.handles(url) }?.takeIf { it.cleansLinks }

    /**
     * Every link in the post that a service would clean: the link it was
     * shared with, and any in its text — including ones added in the editor.
     */
    fun cleanableLinks(item: Item): List<String> =
        (listOfNotNull(item.sourceUrl) + OldLinkCleanup.linksIn(item.bodyText))
            .distinct()
            .filter { cleaningResolver(it) != null }

    /** The services behind [cleanableLinks], for saying what will be checked. */
    fun cleaningServices(item: Item): List<String> =
        cleanableLinks(item).mapNotNull { cleaningResolver(it)?.serviceName }.distinct()

    /** Inbox posts with at least one link a service would clean; no I/O. */
    suspend fun oldLinkCandidates(): List<Item> =
        items.observeInbox().first().filter { OldLinkCleanup.isCandidate(it) && cleanableLinks(it).isNotEmpty() }

    /**
     * Cleans every link in every [oldLinkCandidates] post, one post at a time so
     * the services are not flooded.
     *
     * The link a post was shared with goes through [cleanLink], exactly as a
     * share's would. Links in the text are swapped for their clean form where
     * one is confirmed and left alone where not. Each failure only affects
     * that link, and the run carries on. Counts are per link.
     */
    suspend fun cleanOldLinks(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): OldLinkCleanup.Result {
        val candidates = oldLinkCandidates()
        var cleaned = 0
        var kept = 0
        var alreadyClean = 0
        candidates.forEachIndexed { index, item ->
            onProgress(index, candidates.size)
            var current = item
            val source = item.sourceUrl
            if (source != null && cleaningResolver(source) != null) {
                current = cleanLink(item)
                val newSource = current.sourceUrl ?: source
                when {
                    newSource != source -> {
                        // An edited text is not re-rendered, so swap the link in it by hand.
                        current = current.copy(bodyText = OldLinkCleanup.rewriteBody(current.bodyText, source, newSource))
                        cleaned++
                    }
                    current.linkMayIdentify -> kept++
                    else -> alreadyClean++
                }
            }
            val handled = setOfNotNull(source, current.sourceUrl)
            var body = current.bodyText
            OldLinkCleanup.linksIn(body).distinct().filter { it !in handled }.forEach { link ->
                val resolver = cleaningResolver(link) ?: return@forEach
                when (val result = resolver.cleanLink(link)) {
                    CleanedLink.Unchanged -> alreadyClean++
                    is CleanedLink.Clean -> {
                        body = OldLinkCleanup.rewriteBody(body, link, result.url)
                        cleaned++
                    }
                    is CleanedLink.MayIdentify -> {
                        // Only that it happened and why — never the link.
                        DebugLog.w(LOG, "${resolver.serviceName} link in the text kept: ${result.reason}")
                        kept++
                    }
                }
            }
            current = current.copy(bodyText = body)
            if (current != item) items.update(current)
        }
        onProgress(candidates.size, candidates.size)
        return OldLinkCleanup.Result(cleaned = cleaned, kept = kept, alreadyClean = alreadyClean)
    }

    /**
     * The service that would fetch this item's media, or null when nothing
     * would. No I/O, so a caller can decide what to show before waiting.
     */
    fun resolvingService(item: Item): String? {
        if (item.mediaPath != null) return null
        val url = item.sourceUrl ?: return null
        return resolvers.firstOrNull { it.handles(url) }?.serviceName
    }

    /**
     * Fetches the media behind a link-only item, for the services that publish
     * it — 9GAG does, Instagram does not.
     *
     * Every failure is a no-op that returns the item untouched: no resolver for
     * this host, the service declining, the download failing. The item keeps its
     * link and the screenshot path still works exactly as before, which is what
     * makes this safe to attempt on every share.
     */
    suspend fun resolveLinkMedia(item: Item): Item {
        if (item.mediaPath != null) return item
        val url = item.sourceUrl ?: return item
        val resolver = resolvers.firstOrNull { it.handles(url) }
        if (resolver == null) {
            DebugLog.d(LOG, "No resolver for ${hostOf(url)} — the screenshot path it is")
            return item
        }
        val name = resolver.javaClass.simpleName

        val post = resolver.resolve(url).getOrElse { error ->
            DebugLog.w(LOG, "$name declined ${hostOf(url)}", error)
            return item
        }
        // Which fields came back, never what they say.
        DebugLog.d(LOG, "$name resolved ${hostOf(url)} to ${post.mimeType}, fields ${post.fields.keys.sorted()}")

        val bytes = fetcher.fetch(post.mediaUrl).getOrElse { error ->
            DebugLog.w(LOG, "Download failed for the media $name pointed at", error)
            return item
        }
        val stored = media.store(bytes, post.mimeType).getOrElse { error ->
            DebugLog.w(LOG, "Could not store ${bytes.size} bytes from $name", error)
            return item
        }
        DebugLog.d(LOG, "$name attached ${bytes.size} bytes as ${stored.mimeType}")

        // The same post resolved twice while still a draft is the same bytes,
        // so fold into it rather than leaving a duplicate behind.
        items.draftByMediaHash(stored.sha256)?.takeIf { it.id != item.id }?.let { existing ->
            items.delete(item.id)
            return existing
        }

        val fetched = item.copy(
            mediaPath = stored.file.absolutePath,
            mediaHash = stored.sha256,
            mimeType = stored.mimeType,
            origin = resolver.source,
            sourceFields = post.fields,
        )
        // Now the post's own data is known, the placeholders mapped to it fill in.
        val resolved = refreshed(item, fetched)
        items.update(resolved)
        return resolved
    }

    /** Only the host reaches the log; the rest of a link can identify a person. */
    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url.trim()).host ?: "an unknown host" }.getOrDefault("an unknown host")

    /**
     * What the cropper thinks should be trimmed off this item's screenshot, or
     * null when there is nothing worth proposing. Always runs against the
     * original, so asking twice does not compound earlier crops.
     */
    suspend fun suggestCrop(item: Item): ScreenshotAnalyzer.Suggestion? {
        val source = item.originalMediaPath ?: item.mediaPath ?: return null
        return ScreenshotAnalyzer.suggest(source)?.takeIf { it.trimsAnything }
    }

    /**
     * Applies a crop, keeping the untouched screenshot so it can be redone or
     * undone later. Cropping an already-cropped item re-crops the original
     * rather than cutting into the previous result.
     */
    suspend fun applyCrop(item: Item, crop: ScreenshotCropper.Crop): Result<Item> = runCatching {
        val source = item.originalMediaPath ?: item.mediaPath
            ?: error(text(R.string.repo_no_image_to_crop))
        val bitmap = ScreenshotAnalyzer.crop(source, crop) ?: error(text(R.string.repo_unreadable_image))
        val stored = media.store(bitmap, item.mimeType).getOrThrow()
        bitmap.recycle()

        val cropped = item.copy(
            mediaPath = stored.file.absolutePath,
            mediaHash = stored.sha256,
            mimeType = stored.mimeType,
            originalMediaPath = source,
        )
        items.update(cropped)
        cropped
    }

    /**
     * Applies cleanup rules to the item's current image.
     *
     * Unlike a crop this works from what is on screen rather than from the
     * original, because the rules were drawn against that. The untouched
     * screenshot stays in [Item.originalMediaPath], so one undo still returns
     * the item to exactly what was shared.
     */
    /**
     * The result of a cleanup, including what the image model did or did not do.
     *
     * [aiFailure] exists because a model that is unreachable falls back to a
     * local fill, and silently substituting one treatment for another looks
     * from the outside exactly like a button that does nothing.
     */
    data class Cleaned(val item: Item, val aiUsed: Boolean, val aiFailure: String?)

    suspend fun applyCleanup(
        item: Item,
        rules: List<CleanupRule>,
        provider: ImageEditProvider = NoImageEditProvider,
        polarity: MaskPolarity = MaskPolarity.TRANSPARENT_HOLE,
        instruction: String = "",
    ): Result<Cleaned> = runCatching {
        if (rules.isEmpty()) return@runCatching Cleaned(item, aiUsed = false, aiFailure = null)
        val source = item.mediaPath ?: error(text(R.string.repo_no_image_to_clean))
        val outcome = BitmapCleaner.clean(source, rules, provider, polarity, instruction)
            ?: error(text(R.string.repo_unreadable_image))
        val bitmap = outcome.bitmap
        val stored = media.store(bitmap, item.mimeType).getOrThrow()
        bitmap.recycle()

        val cleaned = item.copy(
            mediaPath = stored.file.absolutePath,
            mediaHash = stored.sha256,
            mimeType = stored.mimeType,
            originalMediaPath = item.originalMediaPath ?: source,
        )
        items.update(cleaned)
        Cleaned(cleaned, outcome.aiUsed, outcome.aiFailure)
    }

    /**
     * Applies the default cleanup profile, if one is set and has rules.
     *
     * A profile becomes automatic by being marked default; leaving no profile
     * default means nothing ever happens without being asked for, which is the
     * control this needs rather than another switch in settings.
     */
    suspend fun applyDefaultCleanup(item: Item): Item {
        val dao = cleanupDao ?: return item
        if (item.mediaPath == null) return item
        if (item.mimeType?.startsWith("video/") == true) return item

        val profile = dao.defaultProfile() ?: return item
        val rules = dao.enabledRules(profile.id).map { it.toCleanupRule() }
        if (rules.isEmpty()) return item

        return applyCleanup(item, rules, editProvider(), maskPolarity(), editInstruction())
            .map { it.item }
            .getOrDefault(item)
    }

    /** Puts the untouched screenshot back, undoing every edit made to it. */
    suspend fun revertEdits(item: Item): Item {
        val original = item.originalMediaPath ?: return item
        val restored = item.copy(mediaPath = original, originalMediaPath = null)
        items.update(restored)
        return restored
    }

    suspend fun markQueued(id: String) = items.setStatus(id, Status.QUEUED)

    suspend fun markDraft(id: String) = items.setStatus(id, Status.DRAFT)

    suspend fun delete(id: String) {
        val item = items.byId(id) ?: return
        items.delete(id)
        val hash = item.mediaHash
        // Any surviving reference counts here, sent ones included.
        val stillUsed = hash != null && items.anyByMediaHash(hash) != null
        media.deleteIfUnreferenced(item.mediaPath, stillUsed)
    }

    suspend fun mediaBytes(item: Item): ByteArray? = item.mediaPath?.let { media.bytes(it) }

    /** Re-arms anything the process died mid-post. Called once on app start. */
    suspend fun recoverStalePosting() = items.requeueStalePosting()

    suspend fun purgePosted(olderThanDays: Int): Int {
        if (olderThanDays <= 0) return 0
        val cutoff = System.currentTimeMillis() - olderThanDays * 86_400_000L
        return items.purgePostedBefore(cutoff)
    }

    suspend fun templates(): List<Template> = templates.all()

    suspend fun resolveTemplate(id: String?): Template =
        (id?.let { templates.byId(it) } ?: templates.defaultTemplate() ?: templates.all().firstOrNull())
            ?: Template.seed().also { templates.upsert(it) }

    /** Seeds the default template so a fresh install can post straight away. */
    suspend fun seedIfEmpty() {
        if (templates.count() == 0) templates.upsert(Template.seed())
    }

    companion object {
        private const val LOG = "share"

        /**
         * How long a half-finished Instagram share stays open to pairing. Long
         * enough to screenshot and share, short enough that an unrelated share
         * half an hour later does not get swallowed into it.
         */
        const val PAIRING_WINDOW_MS = 10 * 60 * 1000L

        /** A share that carried neither media nor text is not worth persisting. */
        fun isActionable(payload: SharePayload): Boolean = !payload.isEmpty
    }
}
