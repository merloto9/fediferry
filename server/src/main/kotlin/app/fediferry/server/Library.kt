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

import app.fediferry.api.FolderDto
import app.fediferry.api.FolderInput
import app.fediferry.api.IngestResult
import app.fediferry.api.LibraryItemDto
import app.fediferry.api.LibraryItemPatch
import app.fediferry.api.LibraryKinds
import app.fediferry.api.LibraryPage
import app.fediferry.api.MediaAssetDto
import app.fediferry.api.TagDto
import app.fediferry.link.CleanedLink
import app.fediferry.link.LinkResolver
import app.fediferry.link.MediaFetcher
import app.fediferry.server.db.Library_item
import app.fediferry.server.db.Media_asset
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/** A file that came with a share. */
class Upload(val bytes: ByteArray, val mime: String)

/**
 * The library: what shares and uploads become, and how they are organised.
 *
 * Everything that reaches out to a service — cleaning a link, fetching the
 * picture behind it — degrades to nothing on failure: the share is kept with
 * what it brought, never lost because a service was slow.
 */
class Library(
    private val storage: Storage,
    private val media: MediaStore,
    private val feed: ChangeFeed,
    private val resolvers: List<LinkResolver>,
    private val fetcher: MediaFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val db get() = storage.db
    private val log = LoggerFactory.getLogger("library")

    // --- ingest -------------------------------------------------------------

    /**
     * Turns one share into a library item. Safe to repeat: the same
     * [clientShareId] returns the item it made the first time.
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
        replayOf(projectId, clientShareId)?.let { return IngestResult(it, replayed = true) }

        val now = clock()
        val sharedLink = link?.trim()?.takeIf { it.isNotEmpty() } ?: text?.let(::firstLink)
        var asset = upload?.let { media.store(projectId, it.bytes, it.mime) }

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
        val origin = resolver?.source?.name

        // Pairing: one half of a link-and-screenshot pair completes the other.
        if (deviceId != null && !resolved) {
            val since = now - PAIRING_WINDOW_MS
            val pairTarget: Library_item? = when {
                asset != null && url == null -> db.libraryItemQueries.waitingForPicture(projectId, deviceId, since, ::Library_item).executeAsOneOrNull()
                url != null && asset == null -> db.libraryItemQueries.waitingForLink(projectId, deviceId, since, ::Library_item).executeAsOneOrNull()
                else -> null
            }
            if (pairTarget != null) {
                val joined = asset
                feed.change(projectId, "library_item", pairTarget.id) {
                    if (joined != null) {
                        db.libraryItemQueries.attachAsset(joined.id, kindOf(joined, url), now, projectId, pairTarget.id)
                    } else {
                        db.libraryItemQueries.attachLink(url, origin, json.encodeToString(fields), if (mayIdentify) 1 else 0, fields["title"], now, projectId, pairTarget.id)
                    }
                    db.ingestRecordQueries.insert(projectId, clientShareId, pairTarget.id, now)
                }
                return IngestResult(item(projectId, pairTarget.id)!!, paired = true)
            }
        }

        // The same picture again: the library already has it. A link that came
        // along this time is added to it when it had none.
        val again = asset?.let { db.libraryItemQueries.byAsset(projectId, it.id, ::Library_item).executeAsOneOrNull() }
        if (again != null) {
            feed.change(projectId, "library_item", again.id) {
                if (url != null && again.source_url == null) {
                    db.libraryItemQueries.attachLink(url, origin, json.encodeToString(fields), if (mayIdentify) 1 else 0, fields["title"], now, projectId, again.id)
                }
                db.ingestRecordQueries.insert(projectId, clientShareId, again.id, now)
            }
            return IngestResult(item(projectId, again.id)!!, duplicate = true)
        }

        val id = UUID.randomUUID().toString()
        val stored = asset
        val kind = when {
            stored != null -> kindOf(stored, url)
            url != null -> LibraryKinds.LINK
            else -> LibraryKinds.TEXT
        }
        if (stored == null && url == null && text.isNullOrBlank()) throw ApiException.badRequest("empty share")
        // A text that only carried the link says nothing more.
        val body = text?.trim()?.takeIf { it.isNotEmpty() && it != sharedLink }
        feed.change(projectId, "library_item", id) {
            db.libraryItemQueries.insert(
                projectId, id, kind, stored?.id, fields["title"], body, url, origin, json.encodeToString(fields),
                if (mayIdentify) 1 else 0, fields["alt"], null, deviceId, capturedAt ?: now, now, now,
            )
            db.ingestRecordQueries.insert(projectId, clientShareId, id, now)
        }
        return IngestResult(item(projectId, id)!!, resolved = resolved)
    }

    private fun replayOf(projectId: String, clientShareId: String): LibraryItemDto? =
        db.ingestRecordQueries.byShare(projectId, clientShareId).executeAsOneOrNull()?.let { item(projectId, it) }

    private fun kindOf(asset: Media_asset, url: String?): String =
        if (asset.mime.startsWith("video/")) LibraryKinds.VIDEO else LibraryKinds.IMAGE

    // --- reading ------------------------------------------------------------

    fun item(projectId: String, id: String): LibraryItemDto? =
        db.libraryItemQueries.byId(projectId, id).executeAsOneOrNull()?.let { dto(projectId, it) }

    fun page(
        projectId: String,
        folder: String?,
        unsorted: Boolean,
        tag: String?,
        query: String?,
        before: Long?,
        limit: Int,
    ): LibraryPage {
        val tagId = tag?.let { db.tagQueries.byName(projectId, it).executeAsOneOrNull()?.id ?: return LibraryPage(emptyList()) }
        val size = limit.coerceIn(1, 200)
        val rows = db.libraryItemQueries.list(
            project = projectId,
            folder = folder,
            unsorted = if (unsorted) 1L else 0L,
            tag = tagId,
            query = query?.trim()?.takeIf { it.isNotEmpty() },
            before = before,
            limit = size.toLong(),
        ).executeAsList()
        return LibraryPage(rows.map { dto(projectId, it) }, nextBefore = rows.takeIf { it.size == size }?.last()?.captured_at)
    }

    private fun dto(projectId: String, row: Library_item): LibraryItemDto = LibraryItemDto(
        id = row.id,
        kind = row.kind,
        asset = row.asset_id?.let { media.byId(projectId, it) }?.let(::assetDto),
        title = row.title,
        text = row.text,
        sourceUrl = row.source_url,
        origin = row.origin,
        sourceFields = runCatching { json.decodeFromString<Map<String, String>>(row.source_fields_json) }.getOrDefault(emptyMap()),
        linkMayIdentify = row.link_may_identify != 0L,
        folderId = row.folder_id,
        tags = db.tagQueries.forItem(projectId, row.id).executeAsList().map { it.name },
        deviceId = row.device_id,
        capturedAt = row.captured_at,
        createdAt = row.created_at,
        updatedAt = row.updated_at,
    )

    // --- changing -----------------------------------------------------------

    fun patch(projectId: String, id: String, patch: LibraryItemPatch): LibraryItemDto {
        val row = db.libraryItemQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("library_item")
        val folder = when (val f = patch.folderId) {
            null -> row.folder_id
            "" -> null
            else -> f.also { requireFolder(projectId, it) }
        }
        val now = clock()
        feed.change(projectId, "library_item", id) {
            db.libraryItemQueries.update(
                patch.title?.trim()?.ifEmpty { null } ?: row.title.takeIf { patch.title == null },
                patch.text?.trim()?.ifEmpty { null } ?: row.text.takeIf { patch.text == null },
                folder, now, projectId, id,
            )
            patch.tags?.let { names -> setTags(projectId, id, names) }
        }
        return item(projectId, id)!!
    }

    fun delete(projectId: String, id: String) {
        db.libraryItemQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("library_item")
        val now = clock()
        feed.change(projectId, "library_item", id, deleted = true) { db.libraryItemQueries.softDelete(now, now, projectId, id) }
    }

    private fun setTags(projectId: String, itemId: String, names: List<String>) {
        db.tagQueries.clearItem(projectId, itemId)
        names.map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.forEach { name ->
            val tag = db.tagQueries.byName(projectId, name).executeAsOneOrNull()
                ?: UUID.randomUUID().toString().let { tagId ->
                    db.tagQueries.insert(projectId, tagId, name, clock())
                    db.tagQueries.byName(projectId, name).executeAsOne()
                }
            db.tagQueries.link(projectId, itemId, tag.id)
        }
    }

    // --- folders and tags ---------------------------------------------------

    fun folders(projectId: String): List<FolderDto> =
        db.libraryFolderQueries.all(projectId).executeAsList().map { FolderDto(it.id, it.name, it.parent_id, it.sort_order.toInt()) }

    fun createFolder(projectId: String, input: FolderInput): FolderDto {
        val name = input.name.trim().ifEmpty { throw ApiException.badRequest("name") }
        input.parentId?.let { requireFolder(projectId, it) }
        val id = UUID.randomUUID().toString()
        val now = clock()
        feed.change(projectId, "folder", id) {
            val order = input.sortOrder ?: db.libraryFolderQueries.count(projectId).executeAsOne().toInt()
            db.libraryFolderQueries.insert(projectId, id, input.parentId, name, order.toLong(), now, now)
        }
        return folders(projectId).first { it.id == id }
    }

    fun updateFolder(projectId: String, id: String, input: FolderInput): FolderDto {
        val row = db.libraryFolderQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("folder")
        input.parentId?.let { parent ->
            requireFolder(projectId, parent)
            if (parent == id || isBelow(projectId, parent, id)) throw ApiException.badRequest("folder_cycle")
        }
        feed.change(projectId, "folder", id) {
            db.libraryFolderQueries.update(
                input.name.trim().ifEmpty { row.name }, input.parentId ?: row.parent_id,
                (input.sortOrder ?: row.sort_order.toInt()).toLong(), clock(), projectId, id,
            )
        }
        return folders(projectId).first { it.id == id }
    }

    /** Deletes a folder; what was in it moves up a level, nothing is lost. */
    fun deleteFolder(projectId: String, id: String) {
        val row = db.libraryFolderQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("folder")
        val now = clock()
        feed.change(projectId, "folder", id, deleted = true) {
            db.libraryFolderQueries.releaseItems(row.parent_id, now, projectId, id)
            db.libraryFolderQueries.releaseFolders(row.parent_id, now, projectId, id)
            db.libraryFolderQueries.delete(projectId, id)
        }
    }

    fun tags(projectId: String): List<TagDto> =
        db.tagQueries.all(projectId).executeAsList().map { TagDto(it.id, it.name, it.uses.toInt()) }

    fun deleteTag(projectId: String, id: String) {
        feed.change(projectId, "tag", id, deleted = true) {
            db.tagQueries.unlinkTag(projectId, id)
            db.tagQueries.delete(projectId, id)
        }
    }

    private fun requireFolder(projectId: String, id: String) {
        db.libraryFolderQueries.byId(projectId, id).executeAsOneOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "folder.unknown", mapOf("id" to id))
    }

    /** Whether [folder] sits somewhere below [ancestor]. */
    private fun isBelow(projectId: String, folder: String, ancestor: String): Boolean {
        var current: String? = folder
        repeat(64) {
            val parent = current?.let { db.libraryFolderQueries.byId(projectId, it).executeAsOneOrNull()?.parent_id } ?: return false
            if (parent == ancestor) return true
            current = parent
        }
        return false
    }

    companion object {
        /** A link and its screenshot shared within this long of each other become one item. */
        const val PAIRING_WINDOW_MS = 10 * 60 * 1000L

        private val json = Json { ignoreUnknownKeys = true }
        private val LINK = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

        fun firstLink(text: String): String? = LINK.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?')

        fun assetDto(row: Media_asset) = MediaAssetDto(
            row.id, row.sha256, row.mime, row.width?.toInt(), row.height?.toInt(), row.bytes, row.parent_asset_id,
        )
    }
}
