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

import app.fediferry.api.CreatePost
import app.fediferry.api.DeriveRequest
import app.fediferry.api.PostPatch
import app.fediferry.api.ReviewFolderInput
import app.fediferry.api.ReviewPatch
import app.fediferry.api.ProfileRuleData
import app.fediferry.api.SettingsKinds
import app.fediferry.media.cleanup.CleanupRule
import app.fediferry.media.cleanup.ImageCleaner
import app.fediferry.media.cleanup.Region
import app.fediferry.media.cleanup.TreatmentKind
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

fun Route.postRoutes(services: Services) {
    val posts = services.posts

    get("/posts") {
        call.respond(posts.list(call.projectId(), call.request.queryParameters["stage"] ?: app.fediferry.api.PostStages.DRAFT))
    }
    post("/posts") { call.respond(HttpStatusCode.Created, posts.create(call.projectId(), call.deviceId(), call.receive<CreatePost>())) }
    get("/posts/{id}") { call.respond(posts.get(call.projectId(), call.parameters["id"].orEmpty())) }
    patch("/posts/{id}") {
        val version = call.request.headers[HttpHeaders.IfMatch]?.trim('"', ' ')?.toLongOrNull()
        call.respond(posts.patch(call.projectId(), call.parameters["id"].orEmpty(), call.deviceId(), version, call.receive<PostPatch>()))
    }
    delete("/posts/{id}") {
        posts.delete(call.projectId(), call.parameters["id"].orEmpty(), call.deviceId())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/posts/{id}/ready") {
        val version = call.request.headers[HttpHeaders.IfMatch]?.trim('"', ' ')?.toLongOrNull()
        call.respond(posts.markReady(call.projectId(), call.parameters["id"].orEmpty(), call.deviceId(), version))
    }
    post("/posts/{id}/unready") { call.respond(posts.backToDraft(call.projectId(), call.parameters["id"].orEmpty())) }
    patch("/posts/{id}/review") {
        call.respond(posts.sortForReview(call.projectId(), call.parameters["id"].orEmpty(), call.receive<ReviewPatch>()))
    }
    get("/review/folders") { call.respond(posts.reviewFolders(call.projectId())) }
    post("/review/folders") { call.respond(HttpStatusCode.Created, posts.createReviewFolder(call.projectId(), call.receive<ReviewFolderInput>())) }
    patch("/review/folders/{id}") {
        call.respond(posts.updateReviewFolder(call.projectId(), call.parameters["id"].orEmpty(), call.receive<ReviewFolderInput>()))
    }
    delete("/review/folders/{id}") {
        posts.deleteReviewFolder(call.projectId(), call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }
    get("/labels") { call.respond(posts.labels(call.projectId())) }
    post("/posts/{id}/lock") {
        call.respond(posts.lock(call.projectId(), call.parameters["id"].orEmpty(), call.deviceId(), call.request.queryParameters["force"] == "true"))
    }
    delete("/posts/{id}/lock") {
        posts.unlock(call.projectId(), call.parameters["id"].orEmpty(), call.deviceId())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/posts/{id}/media/{position}/alt-suggestion") {
        val position = call.parameters["position"]?.toIntOrNull() ?: throw ApiException.badRequest("position")
        call.respond(posts.suggestAlt(call.projectId(), call.parameters["id"].orEmpty(), position))
    }

    /** A new picture made from a stored one: cropped, and/or cleaned with a profile's rules. */
    post("/media/{id}/derive") {
        val projectId = call.projectId()
        val source = services.media.byId(projectId, call.parameters["id"].orEmpty()) ?: throw ApiException.notFound("media")
        val request = call.receive<DeriveRequest>()
        val rules = request.profileId?.let { profile ->
            services.settings.list(projectId, SettingsKinds.CLEANUP_RULE)
                .mapNotNull { runCatching { Json { ignoreUnknownKeys = true }.decodeFromJsonElement(ProfileRuleData.serializer(), it.data) }.getOrNull() }
                .filter { it.profileId == profile && it.enabled }
                .sortedBy { it.sortOrder }
        }.orEmpty()
        val derived = derive(services.media.file(projectId, source.sha256).readBytes(), source.mime, request, rules)
            ?: throw ApiException(HttpStatusCode.UnprocessableEntity, "media.not_editable")
        val asset = services.media.store(projectId, derived.first, derived.second, parentId = source.id, recipe = Json.encodeToString(request))
        call.respond(Library.assetDto(asset))
    }
}

/**
 * Applies [request] to a picture: the crop first, then the clean-up rules on
 * what is left. AI erasing needs the image model, which runs elsewhere; here
 * it falls back to filling from the surroundings, as on the phone without one.
 */
internal fun derive(bytes: ByteArray, mime: String, request: DeriveRequest, rules: List<ProfileRuleData>): Pair<ByteArray, String>? {
    if (!mime.startsWith("image/") || mime == "image/gif") return null
    var image = ImageIO.read(bytes.inputStream()) ?: return null
    request.crop?.let { c ->
        val x = (c.left.coerceIn(0f, 1f) * image.width).toInt()
        val y = (c.top.coerceIn(0f, 1f) * image.height).toInt()
        val w = ((c.right.coerceIn(0f, 1f) - c.left.coerceIn(0f, 1f)) * image.width).toInt().coerceAtMost(image.width - x)
        val h = ((c.bottom.coerceIn(0f, 1f) - c.top.coerceIn(0f, 1f)) * image.height).toInt().coerceAtMost(image.height - y)
        if (w < 8 || h < 8) return null
        image = image.getSubimage(x, y, w, h)
    }
    if (rules.isNotEmpty()) {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val cleaned = ImageCleaner.apply(
            pixels, image.width, image.height,
            rules.map { r ->
                val kind = runCatching { TreatmentKind.valueOf(r.treatment) }.getOrDefault(TreatmentKind.FILL)
                CleanupRule(Region(r.left, r.top, r.right, r.bottom), if (kind == TreatmentKind.AI_ERASE) TreatmentKind.FILL else kind)
            },
        )
        image = BufferedImage(cleaned.width, cleaned.height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, cleaned.width, cleaned.height, cleaned.pixels, 0, cleaned.width)
        }
    }
    return encode(image, jpeg = mime == "image/jpeg")
}

/** JPEG stays JPEG (at high quality); everything else becomes PNG, which loses nothing. */
private fun encode(image: BufferedImage, jpeg: Boolean): Pair<ByteArray, String> {
    val out = ByteArrayOutputStream()
    if (jpeg) {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.92f
        }
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(rgb, null, null), params)
        }
        writer.dispose()
        return out.toByteArray() to "image/jpeg"
    }
    ImageIO.write(image, "png", out)
    return out.toByteArray() to "image/png"
}
