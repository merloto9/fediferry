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

import app.fediferry.api.Changes
import app.fediferry.api.SecretInput
import app.fediferry.api.SettingsEntryDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsTest {

    private val dir: File = Files.createTempDirectory("fediferry-settings").toFile()
    private val storage = Storage.open(File(dir, "fediferry.db"))
    private val services = Services(storage, dir, version = "test", resolvers = emptyList(), fetcher = { Result.failure(IllegalStateException()) })

    @After fun cleanUp() {
        storage.close()
        dir.deleteRecursively()
    }

    private fun test(block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) = testApplication {
        application { fediferry(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        block(client, services.projects.create("Memes").token)
    }

    private val template = buildJsonObject {
        put("id", "t1")
        put("name", "Meme")
        put("body", "{tags}\n\nvia {link}")
    }

    private suspend fun HttpClient.putSetting(token: String, kind: String, id: String, body: kotlinx.serialization.json.JsonObject) =
        put("/api/v1/settings/$kind/$id") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun `settings are kept per kind and id`() = test { client, token ->
        assertEquals(HttpStatusCode.OK, client.putSetting(token, "template", "t1", template).status)
        val list = client.get("/api/v1/settings/template") { bearerAuth(token) }.body<List<SettingsEntryDto>>()
        assertEquals(listOf("t1"), list.map { it.id })
        assertEquals(JsonPrimitive("Meme"), list.single().data["name"])

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/settings/template/t1") { bearerAuth(token) }.status)
        assertTrue(client.get("/api/v1/settings/template") { bearerAuth(token) }.body<List<SettingsEntryDto>>().isEmpty())
    }

    @Test
    fun `writing the same object again changes nothing`() = test { client, token ->
        client.putSetting(token, "template", "t1", template)
        val rev = client.get("/api/v1/changes?since=0") { bearerAuth(token) }.body<Changes>().rev
        client.putSetting(token, "template", "t1", template)
        assertEquals(rev, client.get("/api/v1/changes?since=0") { bearerAuth(token) }.body<Changes>().rev)

        val changed = client.get("/api/v1/changes?since=0") { bearerAuth(token) }.body<Changes>().changed.single()
        assertEquals("settings.template", changed.type)
        assertEquals("t1", changed.id)
    }

    @Test
    fun `unknown kinds are refused`() = test { client, token ->
        assertEquals(HttpStatusCode.NotFound, client.putSetting(token, "passwords", "x", template).status)
    }

    @Test
    fun `projects keep their own settings`() = test { client, token ->
        client.putSetting(token, "template", "t1", template)
        val other = services.projects.create("Other").token
        assertTrue(client.get("/api/v1/settings/template") { bearerAuth(other) }.body<List<SettingsEntryDto>>().isEmpty())
    }

    @Test
    fun `a secret goes in, only its name comes out, and it is stored encrypted`() = test { client, token ->
        val put = client.put("/api/v1/secrets/ai-model:m1") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(SecretInput("sk-very-secret"))
        }
        assertEquals(HttpStatusCode.NoContent, put.status)
        val listed = client.get("/api/v1/secrets") { bearerAuth(token) }.bodyAsText()
        assertEquals("[\"ai-model:m1\"]", listed)
        assertFalse(listed.contains("sk-very-secret"))

        val projectId = services.projects.all().single().id
        assertEquals("sk-very-secret", services.secrets.get(projectId, "ai-model:m1"))
        val raw = storage.db.secretQueries.byId(projectId, "ai-model:m1").executeAsOne()
        assertFalse(raw.ciphertext.contains("secret"))
        // The database file itself never holds the plain value.
        storage.close()
        assertFalse(File(dir, "fediferry.db").readBytes().toString(Charsets.ISO_8859_1).contains("sk-very-secret"))
    }

    @Test
    fun `the master key is made once and kept`() {
        val a = Crypto.load(dir, env = null)
        val sealed = a.seal("hello")
        val b = Crypto.load(dir, env = null)
        assertEquals("hello", b.open(sealed.ciphertext, sealed.nonce))
        assertTrue(File(dir, "master.key").exists())
    }
}
