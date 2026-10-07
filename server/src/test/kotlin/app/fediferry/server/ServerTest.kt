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

import app.fediferry.api.ApiErrorBody
import app.fediferry.api.Changes
import app.fediferry.api.DeviceInfo
import app.fediferry.api.DeviceRegistration
import app.fediferry.api.ErrorCodes
import app.fediferry.api.Health
import app.fediferry.api.ProjectInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ServerTest {

    private val dir: File = Files.createTempDirectory("fediferry-test").toFile()
    private val storage = Storage.open(File(dir, "fediferry.db"))
    private val services = Services(storage, dir, version = "test", resolvers = emptyList(), fetcher = { Result.failure(IllegalStateException("offline")) })

    @After fun cleanUp() {
        storage.close()
        dir.deleteRecursively()
    }

    private fun serverTest(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { fediferry(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        block(client)
    }

    private suspend fun HttpClient.register(token: String, id: String, name: String) = post("/api/v1/devices") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(DeviceRegistration(id, name, "android", "1.0"))
    }

    @Test
    fun `health needs no token, everything else does`() = serverTest { client ->
        assertEquals("test", client.get("/api/v1/health").body<Health>().version)

        val anonymous = client.get("/api/v1/project")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals(ErrorCodes.UNAUTHORIZED, anonymous.body<ApiErrorBody>().code)

        val wrong = client.get("/api/v1/project") { bearerAuth("ffp_not-a-token") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
    }

    @Test
    fun `a token opens its own project`() = serverTest { client ->
        val created = services.projects.create("Memes")
        val project = client.get("/api/v1/project") { bearerAuth(created.token) }.body<ProjectInfo>()
        assertEquals("Memes", project.name)
        assertEquals(created.project.id, project.id)
    }

    @Test
    fun `projects never see each other`() = serverTest { client ->
        val a = services.projects.create("A")
        val b = services.projects.create("B")
        assertEquals(HttpStatusCode.OK, client.register(a.token, "phone-a", "Phone A").status)
        assertEquals(HttpStatusCode.OK, client.register(b.token, "phone-b", "Phone B").status)

        val seenByA = client.get("/api/v1/devices") { bearerAuth(a.token) }.body<List<DeviceInfo>>()
        val seenByB = client.get("/api/v1/devices") { bearerAuth(b.token) }.body<List<DeviceInfo>>()
        assertEquals(listOf("phone-a"), seenByA.map { it.id })
        assertEquals(listOf("phone-b"), seenByB.map { it.id })

        // The same device id in two projects is two separate devices.
        client.register(b.token, "phone-a", "Shared phone")
        assertEquals("Phone A", client.get("/api/v1/devices") { bearerAuth(a.token) }.body<List<DeviceInfo>>().single().name)
        // And the change feeds are separate too.
        val changesA = client.get("/api/v1/changes?since=0") { bearerAuth(a.token) }.body<Changes>()
        assertEquals(1, changesA.changed.size)
    }

    @Test
    fun `a revoked token is locked out, the project's other tokens are not`() = serverTest { client ->
        val created = services.projects.create("Memes")
        val second = services.projects.addToken(created.project, "partner")
        val first = services.projects.tokens(created.project).first { it.label == "first token" }

        assertTrue(services.projects.revokeToken(created.project, first.id))
        assertFalse("revoking twice", services.projects.revokeToken(created.project, first.id))

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/project") { bearerAuth(created.token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/project") { bearerAuth(second) }.status)
    }

    @Test
    fun `a device registers once and updates itself after`() = serverTest { client ->
        val token = services.projects.create("Memes").token
        client.register(token, "pixel", "Pixel")
        val renamed = client.register(token, "pixel", "Pixel 9").body<DeviceInfo>()
        assertEquals("Pixel 9", renamed.name)
        assertEquals(1, client.get("/api/v1/devices") { bearerAuth(token) }.body<List<DeviceInfo>>().size)

        val bad = client.register(token, "", "No id")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    @Test
    fun `the change feed wakes a waiting client`() = serverTest { client ->
        val token = services.projects.create("Memes").token
        val start = client.get("/api/v1/changes?since=0") { bearerAuth(token) }.body<Changes>()
        assertEquals(0L, start.rev)

        val woken = coroutineScope {
            val waiting = async { client.get("/api/v1/changes?since=0&wait=20") { bearerAuth(token) }.body<Changes>() }
            delay(300)
            client.register(token, "fairphone", "Fairphone")
            waiting.await()
        }

        assertEquals(1L, woken.rev)
        assertEquals("device", woken.changed.single().type)
        assertEquals("fairphone", woken.changed.single().id)
        // Asking again from there returns at once with nothing new.
        assertTrue(client.get("/api/v1/changes?since=1") { bearerAuth(token) }.body<Changes>().changed.isEmpty())
    }

    @Test
    fun `tokens are stored only as hashes`() {
        val created = services.projects.create("Memes")
        val stored = services.projects.tokens(created.project).single()
        assertNotEquals(created.token, stored.token_hash)
        assertEquals(Tokens.hash(created.token), stored.token_hash)
        assertTrue(created.token.startsWith("ffp_"))
        assertNull(services.projects.authenticate(created.token + "x"))
    }

    @Test
    fun `project names are unique and deleting removes everything`() {
        val created = services.projects.create("Memes")
        assertTrue(runCatching { services.projects.create(" Memes ") }.isFailure)
        services.projects.delete(created.project)
        assertNull(services.projects.authenticate(created.token))
        assertTrue(services.projects.all().isEmpty())
    }

    @Test
    fun `reopening the data file keeps everything`() {
        val created = services.projects.create("Memes")
        storage.close()
        Storage.open(File(dir, "fediferry.db")).use { again ->
            assertEquals(created.project.id, Projects(again).authenticate(created.token)?.projectId)
        }
    }
}
