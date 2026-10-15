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
package app.fediferry

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fediferry.api.SettingsEntryDto
import app.fediferry.api.SettingsKinds
import app.fediferry.client.ServerClient
import app.fediferry.connect.MergeSide
import app.fediferry.connect.ProjectSync
import app.fediferry.data.db.AppDatabase
import app.fediferry.data.model.AiKind
import app.fediferry.data.model.AiModel
import app.fediferry.data.model.Hashtag
import app.fediferry.data.model.HashtagUsage
import app.fediferry.data.model.Template
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URLDecoder
import java.util.UUID

/** Joining a project with a phone that has settings of its own: nothing is dropped, conflicts go the chosen way. */
@RunWith(AndroidJUnit4::class)
class ProjectMergeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var sync: ProjectSync
    private val projectId = "test-" + UUID.randomUUID()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The project's settings, as the server keeps them: kind → id → data. */
    private val server = mutableMapOf<String, MutableMap<String, JsonObject>>()

    private val fakeServer = Interceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath.removePrefix("/api/v1/settings/").split('/')
        val kind = path[0]
        val entries = server.getOrPut(kind) { mutableMapOf() }
        val body = when (request.method) {
            "GET" -> json.encodeToString(
                ListSerializer(SettingsEntryDto.serializer()),
                entries.map { (id, data) -> SettingsEntryDto(id, data, 0) },
            )
            "PUT" -> {
                val id = URLDecoder.decode(path[1], "UTF-8")
                val data = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                entries[id] = data
                json.encodeToString(SettingsEntryDto.serializer(), SettingsEntryDto(id, data, 0))
            }
            else -> "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    private val client = ServerClient("https://fediferry.example", "ffp_test", "pixel", OkHttpClient.Builder().addInterceptor(fakeServer).build())

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        sync = ProjectSync(context, db, ServiceLocator.serverConnections(context), ServiceLocator.aiModels(context), CoroutineScope(Dispatchers.IO))
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "sync/$projectId.json").delete()
    }

    private inline fun <reified T> put(kind: String, id: String, value: T) {
        server.getOrPut(kind) { mutableMapOf() }[id] = json.encodeToJsonElement(value).jsonObject
    }

    private fun seedBothSides() = runBlocking {
        // The phone: its own default template, an extra one, hashtags, usage and an alt-text model.
        db.templates().upsert(Template.seed().copy(body = "Phone {tags}"))
        db.templates().upsert(Template(id = "phone-only", name = "Phone only", body = "x"))
        db.hashtags().upsert(Hashtag("#meme", 0))
        db.hashtags().upsert(Hashtag("#phone", 1))
        db.hashtagUsage().upsert(HashtagUsage("#meme", 2))
        db.aiModels().upsert(AiModel("m-phone", AiKind.ALT_TEXT, "Phone vision", "https://a.example/v1", "a", isDefault = true))

        // The project: the same template id written differently, its own template, model and hashtags.
        put(SettingsKinds.TEMPLATE, Template.DEFAULT_ID, Template.seed().copy(body = "Server {tags}"))
        put(SettingsKinds.TEMPLATE, "server-only", Template(id = "server-only", name = "Server only", body = "y"))
        put(SettingsKinds.HASHTAG, "#meme", Hashtag("#meme", 5))
        put(SettingsKinds.HASHTAG, "#server", Hashtag("#server", 6))
        put(SettingsKinds.HASHTAG_USAGE, "#meme", HashtagUsage("#meme", 3))
        put(SettingsKinds.AI_MODEL, "m-server", AiModel("m-server", AiKind.ALT_TEXT, "Server vision", "https://b.example/v1", "b", isDefault = true))
    }

    @Test
    fun previewNamesWhatDiffersAndAddsUpTheRest() = runBlocking {
        seedBothSides()
        val preview = sync.preview(client)

        assertTrue(preview.phoneHasOwn)
        assertEquals(
            setOf("${SettingsKinds.TEMPLATE}/${Template.DEFAULT_ID}", "default/${SettingsKinds.AI_MODEL}/${AiKind.ALT_TEXT.name}"),
            preview.conflicts.map { it.key }.toSet(),
        )
        assertEquals(1, preview.fromPhone[SettingsKinds.HASHTAG])
        assertEquals(1, preview.fromPhone[SettingsKinds.AI_MODEL])
        assertEquals(1, preview.fromServer[SettingsKinds.AI_MODEL])
    }

    @Test
    fun mergeKeepsBothSidesAndFollowsTheChoices() = runBlocking {
        seedBothSides()
        sync.merge(
            client, projectId,
            mapOf(
                "${SettingsKinds.TEMPLATE}/${Template.DEFAULT_ID}" to MergeSide.PHONE,
                "default/${SettingsKinds.AI_MODEL}/${AiKind.ALT_TEXT.name}" to MergeSide.PHONE,
            ),
        )

        // Templates: all three, the phone's version of the one both had.
        val templates = db.templates().all().associateBy { it.id }
        assertEquals(setOf(Template.DEFAULT_ID, "phone-only", "server-only"), templates.keys)
        assertEquals("Phone {tags}", templates.getValue(Template.DEFAULT_ID).body)
        assertEquals(templates.keys, server.getValue(SettingsKinds.TEMPLATE).keys)
        assertEquals("Phone {tags}", server.getValue(SettingsKinds.TEMPLATE).getValue(Template.DEFAULT_ID)["body"]!!.jsonPrimitive.content)

        // Hashtags: both lists, the project's order for the shared one, the counts added up.
        assertEquals(setOf("#meme", "#phone", "#server"), db.hashtags().all().map { it.tag }.toSet())
        assertEquals(setOf("#meme", "#phone", "#server"), server.getValue(SettingsKinds.HASHTAG).keys)
        assertEquals(5, db.hashtags().all().first { it.tag == "#meme" }.sortOrder)
        assertEquals(5, db.hashtagUsage().all().single().uses)
        assertEquals("5", server.getValue(SettingsKinds.HASHTAG_USAGE).getValue("#meme")["uses"]!!.jsonPrimitive.content)

        // AI models: both, with the phone's as the one default on either side.
        val models = db.aiModels().all().associateBy { it.id }
        assertEquals(setOf("m-phone", "m-server"), models.keys)
        assertTrue(models.getValue("m-phone").isDefault)
        assertTrue(!models.getValue("m-server").isDefault)
        assertEquals("false", server.getValue(SettingsKinds.AI_MODEL).getValue("m-server")["isDefault"]!!.jsonPrimitive.content)
        assertEquals("true", server.getValue(SettingsKinds.AI_MODEL).getValue("m-phone")["isDefault"]!!.jsonPrimitive.content)

        // Settled: the next look finds nothing left to choose or copy.
        val after = sync.preview(client)
        assertTrue(after.conflicts.isEmpty())
        assertTrue(after.fromPhone.isEmpty() && after.fromServer.isEmpty())
    }

    @Test
    fun withoutAChoiceTheProjectsVersionStays() = runBlocking {
        seedBothSides()
        sync.merge(client, projectId, emptyMap())
        assertEquals("Server {tags}", db.templates().all().first { it.id == Template.DEFAULT_ID }.body)
        assertTrue(db.aiModels().all().first { it.id == "m-server" }.isDefault)
        assertTrue(!db.aiModels().all().first { it.id == "m-phone" }.isDefault)
        // Nothing of the phone's was dropped.
        assertTrue(db.templates().all().any { it.id == "phone-only" })
        assertTrue(db.hashtags().all().any { it.tag == "#phone" })
    }
}
