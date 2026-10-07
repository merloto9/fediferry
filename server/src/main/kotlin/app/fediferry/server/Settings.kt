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

import app.fediferry.api.SecretInput
import app.fediferry.api.SettingsEntryDto
import app.fediferry.api.SettingsKinds
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A project's settings, one JSON object per entry, mirrored by every phone.
 * The last write wins: settings change rarely, and each object is small.
 */
class SettingsStore(private val storage: Storage, private val feed: ChangeFeed, private val clock: () -> Long) {
    private val db get() = storage.db
    private val json = Json

    fun list(projectId: String, kind: String): List<SettingsEntryDto> =
        db.settingsEntryQueries.byKind(projectId, requireKind(kind)).executeAsList()
            .map { SettingsEntryDto(it.id, json.decodeFromString<JsonObject>(it.json), it.updated_at) }

    fun get(projectId: String, kind: String, id: String): JsonObject? =
        db.settingsEntryQueries.byId(projectId, requireKind(kind), id).executeAsOneOrNull()?.let { json.decodeFromString(it.json) }

    fun put(projectId: String, kind: String, id: String, data: JsonObject, deviceId: String?): SettingsEntryDto {
        requireKind(kind)
        if (id.isBlank() || id.length > 200) throw ApiException.badRequest("id")
        val now = clock()
        val text = json.encodeToString(JsonObject.serializer(), data)
        // Unchanged objects are not written again, so syncing phones do not wake each other in a loop.
        val current = db.settingsEntryQueries.byId(projectId, kind, id).executeAsOneOrNull()
        if (current?.json == text) return SettingsEntryDto(id, data, current.updated_at)
        feed.change(projectId, SettingsKinds.changeType(kind), id) {
            db.settingsEntryQueries.upsert(projectId, kind, id, text, now, deviceId)
        }
        return SettingsEntryDto(id, data, now)
    }

    fun delete(projectId: String, kind: String, id: String) {
        requireKind(kind)
        db.settingsEntryQueries.byId(projectId, kind, id).executeAsOneOrNull() ?: return
        feed.change(projectId, SettingsKinds.changeType(kind), id, deleted = true) {
            db.settingsEntryQueries.delete(projectId, kind, id)
        }
    }

    private fun requireKind(kind: String): String =
        kind.takeIf { it in SettingsKinds.ALL } ?: throw ApiException(HttpStatusCode.NotFound, "settings.unknown_kind", mapOf("kind" to kind))
}

fun Route.settingsRoutes(services: Services) {
    val settings = services.settings

    get("/settings/{kind}") { call.respond(settings.list(call.projectId(), call.parameters["kind"].orEmpty())) }

    put("/settings/{kind}/{id}") {
        val body = call.receive<JsonObject>()
        call.respond(settings.put(call.projectId(), call.parameters["kind"].orEmpty(), call.parameters["id"].orEmpty(), body, call.deviceId()))
    }

    delete("/settings/{kind}/{id}") {
        settings.delete(call.projectId(), call.parameters["kind"].orEmpty(), call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }

    // Secrets go in and are used by the server; only their names come back.
    get("/secrets") { call.respond(services.secrets.ids(call.projectId())) }

    put("/secrets/{id}") {
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank() || id.length > 200) throw ApiException.badRequest("id")
        services.secrets.put(call.projectId(), id, call.receive<SecretInput>().value)
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/secrets/{id}") {
        services.secrets.delete(call.projectId(), call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }
}
