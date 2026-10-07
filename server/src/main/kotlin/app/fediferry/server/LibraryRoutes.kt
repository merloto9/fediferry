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

import app.fediferry.api.FolderInput
import app.fediferry.api.LibraryItemPatch
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** Media, the share intake and the library, under the authenticated API. */
fun Route.libraryRoutes(services: Services) {
    val media = services.media
    val library = services.library

    // --- media ---------------------------------------------------------------

    post("/media") {
        val upload = receiveUpload(call) { _, _ -> }.upload ?: throw ApiException.badRequest("file")
        val asset = media.store(call.projectId(), upload.bytes, upload.mime)
        call.respond(Library.assetDto(asset))
    }

    /** Whether the server has this content already, so a client can skip the upload. */
    get("/media/by-hash/{sha}") {
        val asset = media.bySha(call.projectId(), call.parameters["sha"].orEmpty()) ?: throw ApiException.notFound("media")
        call.respond(Library.assetDto(asset))
    }

    get("/media/{id}") {
        val projectId = call.projectId()
        val asset = media.byId(projectId, call.parameters["id"].orEmpty()) ?: throw ApiException.notFound("media")
        call.immutable(asset.sha256)
        call.response.header(HttpHeaders.ContentType, asset.mime)
        call.respondFile(media.file(projectId, asset.sha256))
    }

    get("/media/{id}/thumb") {
        val projectId = call.projectId()
        val asset = media.byId(projectId, call.parameters["id"].orEmpty()) ?: throw ApiException.notFound("media")
        val width = call.request.queryParameters["w"]?.toIntOrNull() ?: 400
        val thumb = media.thumbnail(projectId, asset, width) ?: throw ApiException.notFound("thumbnail")
        call.immutable(asset.sha256 + "-" + width)
        call.response.header(HttpHeaders.ContentType, ContentType.Image.JPEG.toString())
        call.respondFile(thumb)
    }

    // --- intake --------------------------------------------------------------

    /**
     * One share from a phone: a picture, a link, a text, or a link and a
     * picture. Multipart fields: `file` (optional), `link`, `text`, `mode`,
     * `capturedAt`. Repeating the same share id returns the first result.
     */
    put("/ingest/{shareId}") {
        val shareId = call.parameters["shareId"].orEmpty()
        if (shareId.isBlank() || shareId.length > 64) throw ApiException.badRequest("share_id")
        val form = mutableMapOf<String, String>()
        val received = receiveUpload(call) { name, value -> form[name] = value }
        val result = library.ingest(
            projectId = call.projectId(),
            deviceId = call.deviceId(),
            clientShareId = shareId,
            link = form["link"],
            text = form["text"],
            upload = received.upload,
            capturedAt = form["capturedAt"]?.toLongOrNull(),
        )
        call.respond(if (result.replayed) HttpStatusCode.OK else HttpStatusCode.Created, result)
    }

    // --- library -------------------------------------------------------------

    get("/library/items") {
        val q = call.request.queryParameters
        call.respond(
            library.page(
                projectId = call.projectId(),
                folder = q["folder"]?.takeIf { it.isNotBlank() },
                unsorted = q["unsorted"] == "true",
                tag = q["tag"]?.takeIf { it.isNotBlank() },
                query = q["q"],
                before = q["before"]?.toLongOrNull(),
                limit = q["limit"]?.toIntOrNull() ?: 60,
            ),
        )
    }

    get("/library/items/{id}") {
        call.respond(library.item(call.projectId(), call.parameters["id"].orEmpty()) ?: throw ApiException.notFound("library_item"))
    }

    patch("/library/items/{id}") {
        call.respond(library.patch(call.projectId(), call.parameters["id"].orEmpty(), call.receive<LibraryItemPatch>()))
    }

    delete("/library/items/{id}") {
        library.delete(call.projectId(), call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }

    get("/library/folders") { call.respond(library.folders(call.projectId())) }
    post("/library/folders") { call.respond(HttpStatusCode.Created, library.createFolder(call.projectId(), call.receive<FolderInput>())) }
    patch("/library/folders/{id}") {
        call.respond(library.updateFolder(call.projectId(), call.parameters["id"].orEmpty(), call.receive<FolderInput>()))
    }
    delete("/library/folders/{id}") {
        library.deleteFolder(call.projectId(), call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }

    get("/tags") { call.respond(library.tags(call.projectId())) }
    delete("/tags/{id}") {
        library.deleteTag(call.projectId(), call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }
}

private class Received(val upload: Upload?)

/** Reads a multipart body: the `file` part as an [Upload], every other field through [field]. */
private suspend fun receiveUpload(
    call: io.ktor.server.application.ApplicationCall,
    field: (String, String) -> Unit,
): Received {
    var upload: Upload? = null
    call.receiveMultipart(formFieldLimit = MediaStore.MAX_BYTES.toLong()).forEachPart { part ->
        when (part) {
            is PartData.FileItem -> if (part.name == "file") {
                val bytes = part.provider().readRemaining().readByteArray()
                val mime = part.contentType?.withoutParameters()?.toString() ?: "application/octet-stream"
                upload = Upload(bytes, mime)
            }
            is PartData.FormItem -> field(part.name.orEmpty(), part.value)
            else -> Unit
        }
        part.dispose()
    }
    return Received(upload)
}

/** Content named by its hash never changes, so clients may keep it forever. */
private fun io.ktor.server.application.ApplicationCall.immutable(etag: String) {
    response.header(HttpHeaders.ETag, "\"$etag\"")
    response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
}
