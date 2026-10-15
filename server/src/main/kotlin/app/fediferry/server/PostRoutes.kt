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

import app.fediferry.api.AltSuggestionRequest
import app.fediferry.api.CleanupArea
import app.fediferry.api.DeriveRequest
import app.fediferry.api.PostPatch
import app.fediferry.api.PlanRequest
import app.fediferry.api.ScheduleInput
import app.fediferry.api.ReviewFolderInput
import app.fediferry.api.ReviewPatch
import app.fediferry.api.ProfileRuleData
import app.fediferry.api.SettingsKinds
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

fun Route.postRoutes(services: Services) {
    val posts = services.posts

    get("/posts") {
        val stages = (call.request.queryParameters["stage"] ?: app.fediferry.api.PostStages.DRAFT).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        call.respond(posts.list(call.projectId(), stages))
    }
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

    val publishing = services.publishing
    post("/posts/{id}/plan") { call.respond(publishing.plan(call.projectId(), call.parameters["id"].orEmpty(), call.receive<PlanRequest>())) }
    post("/posts/{id}/unplan") { call.respond(publishing.unplan(call.projectId(), call.parameters["id"].orEmpty())) }
    get("/schedules") { call.respond(publishing.schedules(call.projectId())) }
    post("/schedules") { call.respond(HttpStatusCode.Created, publishing.createSchedule(call.projectId(), call.receive<ScheduleInput>())) }
    patch("/schedules/{id}") {
        call.respond(publishing.updateSchedule(call.projectId(), call.parameters["id"].orEmpty(), call.receive<ScheduleInput>()))
    }
    delete("/schedules/{id}") {
        publishing.deleteSchedule(call.projectId(), call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/posts/{id}/lock") {
        call.respond(posts.lock(call.projectId(), call.parameters["id"].orEmpty(), call.deviceId(), call.request.queryParameters["force"] == "true"))
    }
    delete("/posts/{id}/lock") {
        posts.unlock(call.projectId(), call.parameters["id"].orEmpty(), call.deviceId())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/posts/{id}/media/{position}/alt-suggestion") {
        val position = call.parameters["position"]?.toIntOrNull() ?: throw ApiException.badRequest("position")
        // An older app sends an empty object: the project's default model.
        val request = runCatching { call.receive<AltSuggestionRequest>() }.getOrDefault(AltSuggestionRequest())
        call.respond(posts.suggestAlt(call.projectId(), call.parameters["id"].orEmpty(), position, request.modelId))
    }

    /** A new picture made from a stored one: cropped, and/or cleaned with a profile's areas or its own. */
    post("/media/{id}/derive") {
        val projectId = call.projectId()
        val source = services.media.byId(projectId, call.parameters["id"].orEmpty()) ?: throw ApiException.notFound("media")
        val request = call.receive<DeriveRequest>()
        val areas = request.areas ?: request.profileId?.let { profile ->
            services.settings.list(projectId, SettingsKinds.CLEANUP_RULE)
                .mapNotNull { runCatching { Json { ignoreUnknownKeys = true }.decodeFromJsonElement(ProfileRuleData.serializer(), it.data) }.getOrNull() }
                .filter { it.profileId == profile && it.enabled }
                .sortedBy { it.sortOrder }
                .map { CleanupArea(it.left, it.top, it.right, it.bottom, it.treatment) }
        }.orEmpty()
        val model = if (areas.any { it.treatment == TreatmentKind.AI_ERASE.name }) imageModel(services, projectId, request.modelId, outboundHttp) else null
        val derived = derive(services.media.file(projectId, source.sha256).readBytes(), source.mime, request, areas, model)
            ?: throw ApiException(HttpStatusCode.UnprocessableEntity, "media.not_editable")
        val asset = services.media.store(projectId, derived.bytes, derived.mime, parentId = source.id, recipe = Json.encodeToString(request))
        call.respond(MediaStore.assetDto(asset).copy(aiFailure = derived.aiFailure))
    }
}

