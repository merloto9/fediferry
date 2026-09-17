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

import app.fediferry.data.db.AccountDao
import app.fediferry.data.db.ItemDao
import app.fediferry.data.db.TemplateDao
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.data.model.Template
import app.fediferry.media.ScreenshotAnalyzer
import app.fediferry.media.ScreenshotCropper
import app.fediferry.share.SharePayload
import app.fediferry.template.TemplateEngine
import kotlinx.coroutines.flow.Flow
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
) {

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
            items.byMediaHash(hash)?.let { existing ->
                // Same bytes, still pending: fold the new share into it rather
                // than creating a second copy.
                val merged = existing.copy(sourceUrl = existing.sourceUrl ?: payload.link)
                if (merged != existing) items.update(merged)
                return@runCatching Ingested(merged, Ingested.Outcome.DUPLICATE)
            }
        }

        pair(payload, stored, status)?.let { return@runCatching it }

        val body = TemplateEngine.render(
            template,
            TemplateEngine.Inputs(link = payload.link),
        )

        val item = Item(
            id = UUID.randomUUID().toString(),
            mediaPath = stored?.file?.absolutePath,
            mediaHash = stored?.sha256,
            mimeType = stored?.mimeType,
            sourceUrl = payload.link,
            bodyText = body,
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
            val merged = waiting.copy(
                sourceUrl = payload.link,
                bodyText = relink(waiting, payload.link),
                status = status,
            )
            items.update(merged)
            return Ingested(merged, Ingested.Outcome.PAIRED)
        }

        return null
    }

    /**
     * Re-renders a draft's body now that a link is available — but only if the
     * body is still exactly what the template produced without one. Anything else
     * means the user has edited it, and their text is not ours to overwrite.
     */
    private suspend fun relink(item: Item, link: String): String {
        val template = resolveTemplate(item.templateId)
        val unlinked = TemplateEngine.render(template, TemplateEngine.Inputs(link = null))
        if (item.bodyText != unlinked) return item.bodyText
        return TemplateEngine.render(template, TemplateEngine.Inputs(link = link))
    }

    suspend fun update(item: Item) = items.update(item)

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
            ?: error("This item has no image to crop")
        val bitmap = ScreenshotAnalyzer.crop(source, crop) ?: error("Could not read the image")
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

    /** Puts the untouched screenshot back. */
    suspend fun revertCrop(item: Item): Item {
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
        val stillUsed = hash != null && items.byMediaHash(hash) != null
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
