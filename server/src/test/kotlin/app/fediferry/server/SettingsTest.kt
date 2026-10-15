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
    fun `deleting a model deletes its key, and keys left behind earlier are swept`() = test { client, token ->
        val model = buildJsonObject { put("id", "m1"); put("kind", "ALT_TEXT") }
        client.putSetting(token, "ai_model", "m1", model)
        client.put("/api/v1/secrets/ai-model:m1") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(SecretInput("sk-one")) }
        val projectId = services.projects.all().single().id

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/settings/ai_model/m1") { bearerAuth(token) }.status)
        assertEquals(null, services.secrets.get(projectId, "ai-model:m1"))

        // A key orphaned before deleting took it along; other secrets stay.
        services.secrets.put(projectId, "ai-model:gone", "sk-two")
        services.secrets.put(projectId, "other", "keep")
        assertEquals(1, services.settings.dropOrphanedKeys(projectId))
        assertEquals(listOf("other"), services.secrets.ids(projectId))
    }

    @Test
    fun `a secret is bound to its place, and older ones are bound once`() = test { _, _ ->
        val projectId = services.projects.all().single().id
        services.secrets.put(projectId, "ai-model:a", "sk-a")
        // Copied into another row, it does not open there.
        val row = storage.db.secretQueries.byId(projectId, "ai-model:a").executeAsOne()
        storage.db.secretQueries.upsert(projectId, "ai-model:b", row.ciphertext, row.nonce, 0)
        assertEquals(null, services.secrets.get(projectId, "ai-model:b"))

        // One sealed before binding opens again after the next start, bound.
        val key = java.util.Base64.getDecoder().decode(File(dir, "master.key").readText().trim())
        val nonce = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
        }
        val b64 = java.util.Base64.getEncoder()
        storage.db.secretQueries.upsert(projectId, "ai-model:old", b64.encodeToString(cipher.doFinal("sk-old".toByteArray())), b64.encodeToString(nonce), 0)
        assertEquals(null, services.secrets.get(projectId, "ai-model:old"))
        File(dir, "secrets-bound").delete()
        val again = Services(storage, dir)
        assertEquals("sk-old", again.secrets.get(projectId, "ai-model:old"))
        assertEquals("sk-a", again.secrets.get(projectId, "ai-model:a"))
    }

    @Test
    fun `the master key is made once and kept`() {
        val a = Crypto.load(dir, env = null)
        val sealed = a.seal("hello", "here")
        val b = Crypto.load(dir, env = null)
        assertEquals("hello", b.open(sealed.ciphertext, sealed.nonce, "here"))
        assertTrue(File(dir, "master.key").exists())
    }
}
