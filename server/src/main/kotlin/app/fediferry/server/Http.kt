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

import app.fediferry.mastodon.MastodonAccounts
import java.util.concurrent.TimeUnit
import java.io.File
import okhttp3.OkHttpClient
import app.fediferry.module.reddit.RedditResolver
import app.fediferry.module.pinterest.PinterestResolver
import app.fediferry.module.ninegag.NineGagResolver
import app.fediferry.link.OkHttpMediaFetcher
import app.fediferry.link.MediaFetcher
import app.fediferry.link.LinkResolver
import app.fediferry.api.Api
import app.fediferry.api.ApiErrorBody
import app.fediferry.api.DeviceInfo
import app.fediferry.api.DeviceRegistration
import app.fediferry.api.ErrorCodes
import app.fediferry.api.Health
import app.fediferry.api.ProjectInfo
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Everything the HTTP layer works with, built once per process (or per test). */
class Services(
    val storage: Storage,
    val dataDir: File,
    val clock: () -> Long = System::currentTimeMillis,
    val version: String = SERVER_VERSION,
    resolvers: List<LinkResolver> = defaultResolvers(version),
    fetcher: MediaFetcher = OkHttpMediaFetcher(outboundHttp),
    mastodon: MastodonAccounts = MastodonAccounts(outboundHttp),
) {
    val projects = Projects(storage, clock)
    val feed = ChangeFeed(storage, clock)
    val media = MediaStore(storage, dataDir, clock)
    val library = Library(storage, media, feed, resolvers, fetcher, clock)
    val crypto = Crypto.load(dataDir)
    val secrets = Secrets(storage.db, crypto, clock)
    val settings = SettingsStore(storage, feed, clock)
    val channels = Channels(storage, secrets, crypto, feed, mastodon, clock)
    val posts = Posts(storage, feed, settings, secrets, media, outboundHttp, clock)
}

/** One HTTP client for everything the server fetches from services. */
internal val outboundHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

/** The source modules whose shared links the server can clean and resolve. */
fun defaultResolvers(version: String): List<LinkResolver> = listOf(
    NineGagResolver(outboundHttp),
    PinterestResolver(outboundHttp),
    RedditResolver(outboundHttp, version),
)

private val log = LoggerFactory.getLogger("fediferry")

/** The longest a client may ask `/changes` to wait. */
private const val MAX_WAIT_SECONDS = 60

/**
 * The FediFerry API. Nothing here logs a request's headers or body: a token
 * or a post's text must never reach a log file.
 */
fun Application.fediferry(services: Services) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, e.body) }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorBody(ErrorCodes.BAD_REQUEST))
        }
        exception<Throwable> { call, e ->
            log.error("Request failed: ${e.javaClass.simpleName}", e)
            call.respond(HttpStatusCode.InternalServerError, ApiErrorBody("server.error"))
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respond(status, ApiErrorBody(ErrorCodes.UNAUTHORIZED))
        }
    }
    install(Authentication) {
        bearer("project") {
            authenticate { credential -> services.projects.authenticate(credential.token) }
        }
    }

    routing {
        route(Api.PREFIX) {
            get("/health") { call.respond(Health(version = services.version)) }

            authenticate("project") {
                get("/project") {
                    val id = call.projectId()
                    val project = services.projects.byId(id) ?: throw ApiException.notFound("project")
                    call.respond(ProjectInfo(project.id, project.name, project.rev_counter))
                }

                deviceRoutes(services)
                libraryRoutes(services)
                settingsRoutes(services)
                channelRoutes(services)
                postRoutes(services)

                get("/changes") {
                    val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                    val wait = (call.request.queryParameters["wait"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_WAIT_SECONDS)
                    call.respond(services.feed.await(call.projectId(), since, wait * 1000L))
                }
            }
        }
    }
}

private fun io.ktor.server.routing.Route.deviceRoutes(services: Services) {
    val db = services.storage.db

    post("/devices") {
        val projectId = call.projectId()
        val device = call.receive<DeviceRegistration>()
        if (device.id.isBlank() || device.name.isBlank() || device.id.length > 64 || device.name.length > 80) {
            throw ApiException.badRequest("device")
        }
        val now = services.clock()
        services.feed.change(projectId, "device", device.id) {
            db.deviceQueries.upsert(projectId, device.id, device.name.trim(), device.platform, device.appVersion, now, now)
        }
        val saved = db.deviceQueries.byId(projectId, device.id).executeAsOne()
        call.respond(DeviceInfo(saved.id, saved.name, saved.platform, saved.app_version, saved.last_seen_at))
    }

    get("/devices") {
        val list = db.deviceQueries.byProject(call.projectId()).executeAsList()
            .map { DeviceInfo(it.id, it.name, it.platform, it.app_version, it.last_seen_at) }
        call.respond(list)
    }
}

/** The project the request's token opens; only called inside `authenticate`. */
fun ApplicationCall.projectId(): String = principal<ProjectPrincipal>()!!.projectId

/** The calling device, from its header, or null before it has registered. */
fun ApplicationCall.deviceId(): String? = request.headers[Api.DEVICE_HEADER]?.takeIf { it.isNotBlank() }
