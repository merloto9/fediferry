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

import app.fediferry.api.IngestResult
import app.fediferry.api.PostStages
import app.fediferry.link.CleanedLink
import app.fediferry.link.LinkResolver
import app.fediferry.link.MediaFetcher
import app.fediferry.server.db.Media_asset
import app.fediferry.server.db.Post
import org.slf4j.LoggerFactory

/** A file that came with a share. */
class Upload(val bytes: ByteArray, val mime: String)

/**
 * Shares from phones, each made a draft. There is no library in between: what
 * is shared is a post in the making, and it is kept only until it is out.
 *
 * Everything that reaches out to a service — cleaning a link, fetching the
 * picture behind it — degrades to nothing on failure: the share becomes a
 * draft with what it brought, never lost because a service was slow.
 */
class Intake(
    private val storage: Storage,
    private val media: MediaStore,
    private val posts: Posts,
    private val resolvers: List<LinkResolver>,
    private val fetcher: MediaFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val db get() = storage.db
    private val log = LoggerFactory.getLogger("intake")

    /**
     * Turns one share into a draft, or completes the draft it is the other
     * half of. Safe to repeat: the same [clientShareId] returns the draft it
     * went into the first time.
     */
    suspend fun ingest(
        projectId: String,
        deviceId: String?,
        clientShareId: String,
        link: String?,
        text: String?,
        upload: Upload?,
        capturedAt: Long?,
    ): IngestResult {
        db.ingestRecordQueries.byShare(projectId, clientShareId).executeAsOneOrNull()?.let { postId ->
            return IngestResult(runCatching { posts.get(projectId, postId) }.getOrNull(), replayed = true)
        }

        val now = clock()
        val sharedLink = link?.trim()?.takeIf { it.isNotEmpty() } ?: text?.let(::firstLink)
        var asset: Media_asset? = upload?.let { media.store(projectId, it.bytes, it.mime) }

        // The link: cleaned of whoever shared it where its service puts that in.
        var url = sharedLink
        var mayIdentify = false
        var fields = emptyMap<String, String>()
        var resolved = false
        val resolver = sharedLink?.let { l -> resolvers.firstOrNull { it.handles(l) } }
        if (resolver != null && sharedLink != null) {
            if (resolver.cleansLinks) {
                when (val cleaned = runCatching { resolver.cleanLink(sharedLink) }.getOrElse { CleanedLink.MayIdentify(it.message ?: "") }) {
                    CleanedLink.Unchanged -> Unit
                    is CleanedLink.Clean -> url = cleaned.url
                    is CleanedLink.MayIdentify -> mayIdentify = true
                }
            }
            // A link on its own: fetch the picture behind it, where the service publishes one.
            if (asset == null) {
                resolver.resolve(url!!).onSuccess { post ->
                    fields = post.fields
                    fetcher.fetch(post.mediaUrl).onSuccess { bytes ->
                        asset = media.store(projectId, bytes, post.mimeType)
                        resolved = true
                    }.onFailure { log.info("Fetching the media from ${resolver.serviceName} failed: ${it.javaClass.simpleName}") }
                }.onFailure { log.info("${resolver.serviceName} could not resolve a link: ${it.javaClass.simpleName}") }
            }
        }
        val source = Posts.Source(url, resolver?.source?.name, fields, mayIdentify)
        val picture = asset

        // Pairing: one half of a link-and-screenshot pair completes the other's draft.
        if (deviceId != null && !resolved) {
            val since = now - PAIRING_WINDOW_MS
            if (picture != null && url == null) {
                db.postQueries.waitingForPicture(projectId, deviceId, since).executeAsOneOrNull()?.let { draft ->
                    return IngestResult(posts.attachPicture(projectId, draft.id, clientShareId, picture), paired = true)
                }
            }
            if (url != null && picture == null) {
                db.postQueries.waitingForLink(projectId, deviceId, since).executeAsOneOrNull()?.let { draft ->
                    return IngestResult(posts.attachLink(projectId, draft.id, clientShareId, source), paired = true)
                }
            }
        }

        // The same picture again: a post has it already. A link that came along
        // this time is added to it, while it is a draft that had none.
        val again: Post? = picture?.let { db.postQueries.byAsset(projectId, it.id).executeAsOneOrNull() }
        if (again != null) {
            val post = if (url != null && again.source_url == null && again.stage == PostStages.DRAFT) {
                posts.attachLink(projectId, again.id, clientShareId, source)
            } else {
                posts.recordShare(projectId, again.id, clientShareId)
                posts.get(projectId, again.id)
            }
            return IngestResult(post, duplicate = true)
        }

        if (picture == null && url == null && text.isNullOrBlank()) throw ApiException.badRequest("empty share")
        // A text that only carried the link says nothing more.
        val ownText = text?.trim()?.takeIf { it.isNotEmpty() && it != sharedLink }
        val draft = posts.createDraft(projectId, deviceId, clientShareId, source, picture, fields["alt"], ownText, capturedAt ?: now)
        return IngestResult(draft, resolved = resolved)
    }

    companion object {
        /** A link and its screenshot shared within this long of each other become one draft. */
        const val PAIRING_WINDOW_MS = 10 * 60 * 1000L

        private val LINK = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

        fun firstLink(text: String): String? = LINK.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?')
    }
}
