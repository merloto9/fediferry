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
package app.fediferry.connect

import android.content.Context
import androidx.room.InvalidationTracker
import app.fediferry.api.SettingsKinds
import app.fediferry.client.ServerClient
import app.fediferry.data.AiModels
import app.fediferry.data.db.AppDatabase
import app.fediferry.data.model.AiModel
import app.fediferry.data.model.CleanupProfile
import app.fediferry.data.model.Hashtag
import app.fediferry.data.model.HashtagUsage
import app.fediferry.data.model.PlaceholderKey
import app.fediferry.data.model.ProfileRule
import app.fediferry.data.model.Source
import app.fediferry.data.model.Template
import app.fediferry.log.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest

/**
 * Keeps this phone's copy of the project settings — templates, placeholders,
 * hashtags, AI models, clean-up profiles, sources — in step with the server.
 *
 * The settings screens keep working on the phone's database as before. For
 * each kind the sync compares three states: the phone's, the server's, and
 * the one both had at the last sync. Whoever changed an object since then
 * wins; when both did, the server does. A phone joining a project that has
 * settings already takes the project's instead of adding its own.
 */
class ProjectSync(
    private val context: Context,
    private val db: AppDatabase,
    private val connections: ServerConnections,
    private val aiModels: AiModels,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var follow: Job? = null
    private var pendingPush: Job? = null

    /** One kind of settings: how to read, write and remove it locally. */
    private inner class Kind<T>(
        val name: String,
        val tables: Set<String>,
        val serializer: KSerializer<T>,
        val load: suspend () -> List<T>,
        val id: (T) -> String,
        val upsert: suspend (T) -> Unit,
        val remove: suspend (String) -> Unit,
    ) {
        suspend fun local(): Map<String, JsonObject> =
            load().associate { id(it) to json.encodeToJsonElement(serializer, it).jsonObject }

        suspend fun apply(data: JsonObject) = upsert(json.decodeFromJsonElement(serializer, data))
    }

    private val kinds: List<Kind<*>> = listOf(
        Kind(SettingsKinds.TEMPLATE, setOf("templates"), Template.serializer(), { db.templates().all() }, { it.id }, { db.templates().upsert(it) }, { db.templates().deleteAny(it) }),
        Kind(SettingsKinds.PLACEHOLDER_KEY, setOf("placeholder_keys"), PlaceholderKey.serializer(), { db.placeholderKeys().all() }, { it.id }, { db.placeholderKeys().upsert(it) }, { db.placeholderKeys().delete(it) }),
        Kind(SettingsKinds.HASHTAG, setOf("hashtags"), Hashtag.serializer(), { db.hashtags().all() }, { it.tag }, { db.hashtags().upsert(it) }, { db.hashtags().delete(it) }),
        Kind(SettingsKinds.HASHTAG_USAGE, setOf("hashtag_usage"), HashtagUsage.serializer(), { db.hashtagUsage().all() }, { it.key }, { db.hashtagUsage().upsert(it) }, { db.hashtagUsage().delete(it) }),
        Kind(SettingsKinds.AI_MODEL, setOf("ai_models"), AiModel.serializer(), { db.aiModels().all() }, { it.id }, { db.aiModels().upsert(it) }, { db.aiModels().delete(it) }),
        Kind(SettingsKinds.CLEANUP_PROFILE, setOf("cleanup_profiles"), CleanupProfile.serializer(), { db.cleanup().profiles() }, { it.id }, { db.cleanup().upsertProfile(it) }, { db.cleanup().deleteProfileRow(it) }),
        Kind(SettingsKinds.CLEANUP_RULE, setOf("cleanup_rules"), ProfileRule.serializer(), { db.cleanup().allRules() }, { it.id }, { db.cleanup().upsertRule(it) }, { db.cleanup().deleteRule(it) }),
        Kind(SettingsKinds.SOURCE, setOf("sources"), Source.serializer(), { db.sources().all() }, { it.id }, { db.sources().upsert(it) }, { db.sources().delete(it) }),
    )

    // --- the state both sides had at the last sync, per project ---------------

    private fun stateFile(projectId: String) = File(context.filesDir, "sync/$projectId.json")

    private fun readState(projectId: String): MutableMap<String, MutableMap<String, String>>? =
        stateFile(projectId).takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString<Map<String, Map<String, String>>>(f.readText()) }.getOrNull()
                ?.mapValues { it.value.toMutableMap() }?.toMutableMap()
        }

    private fun writeState(projectId: String, state: Map<String, Map<String, String>>) {
        stateFile(projectId).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(state))
    }

    private fun digest(data: JsonObject): String =
        MessageDigest.getInstance("SHA-256").digest(json.encodeToString(JsonObject.serializer(), data).toByteArray())
            .joinToString("") { "%02x".format(it) }

    // --- syncing ----------------------------------------------------------------

    /** Syncs every kind; the first time for a project, this is the import or the takeover. */
    suspend fun syncAll() {
        for (kind in kinds) sync(kind.name)
        pushSecrets()
    }

    suspend fun sync(kindName: String) = mutex.withLock {
        val connection = connections.current() ?: return@withLock
        val kind = kinds.firstOrNull { it.name == kindName } ?: return@withLock
        val client = connections.client(connection)
        runCatching { syncKind(kind, client, connection.projectId) }
            .onFailure { DebugLog.w(LOG, "Syncing $kindName failed: ${it.javaClass.simpleName}") }
    }

    private suspend fun <T> syncKind(kind: Kind<T>, client: ServerClient, projectId: String) {
        val state = readState(projectId)
        val firstTime = state == null
        val known = state?.get(kind.name).orEmpty()
        val remote = client.settings(kind.name).associate { it.id to it.data }
        val local = kind.local()

        if (firstTime && remote.isNotEmpty()) {
            // Joining a project that has settings: take them, drop this phone's own.
            remote.values.forEach { kind.apply(it) }
            (local.keys - remote.keys).forEach { kind.remove(it) }
        } else {
            for (id in local.keys + remote.keys) {
                val l = local[id]?.let(::digest)
                val r = remote[id]?.let(::digest)
                val k = known[id]
                when {
                    l == r -> Unit
                    r == k -> if (local[id] != null) client.putSetting(kind.name, id, local[id]!!) else client.deleteSetting(kind.name, id)
                    else -> if (remote[id] != null) kind.apply(remote[id]!!) else kind.remove(id)
                }
            }
        }
        // Both sides now hold the same; that is the state for next time.
        val settled = kind.local().mapValues { digest(it.value) }
        val all = (state ?: mutableMapOf()).also { it[kind.name] = settled.toMutableMap() }
        for (other in kinds) all.putIfAbsent(other.name, mutableMapOf())
        writeState(projectId, all)
    }

    /** AI keys go to the server, which will use them; they never come back. */
    private suspend fun pushSecrets() = mutex.withLock {
        val connection = connections.current() ?: return@withLock
        val client = connections.client(connection)
        runCatching {
            val state = readState(connection.projectId) ?: mutableMapOf()
            val sent = state.getOrPut(SECRETS) { mutableMapOf() }
            for (model in db.aiModels().all()) {
                val key = aiModels.apiKey(model).takeIf { it.isNotEmpty() } ?: continue
                val id = "ai-model:${model.id}"
                val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                if (sent[id] == hash) continue
                client.putSecret(id, key)
                sent[id] = hash
            }
            writeState(connection.projectId, state)
        }.onFailure { DebugLog.w(LOG, "Sending AI keys failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Keeps syncing while the app runs: local edits go up a moment after they
     * happen, other phones' edits come down as the change feed reports them.
     */
    fun start() {
        val tables = kinds.flatMap { it.tables }.toTypedArray()
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(tables) {
            override fun onInvalidated(tables: Set<String>) {
                if (connections.current() == null) return
                pendingPush?.cancel()
                pendingPush = scope.launch {
                    delay(PUSH_DELAY_MS)
                    kinds.filter { k -> k.tables.any { it in tables } }.forEach { sync(it.name) }
                    pushSecrets()
                }
            }
        })
        restartFollowing()
    }

    /** (Re)starts following the change feed, e.g. after connecting to another project. */
    fun restartFollowing() {
        follow?.cancel()
        val connection = connections.current() ?: return
        follow = scope.launch {
            syncAll()
            val client = connections.client(connection)
            var rev = runCatching { client.project().rev }.getOrDefault(0L)
            while (isActive) {
                val changes = runCatching { client.changes(rev) }.getOrNull()
                if (changes == null) {
                    delay(RETRY_MS)
                    continue
                }
                changes.changed.map { it.type }.filter { it.startsWith("settings.") }.toSet()
                    .forEach { sync(it.removePrefix("settings.")) }
                rev = changes.rev
            }
        }
    }

    fun stop() {
        follow?.cancel()
        follow = null
    }

    private companion object {
        const val LOG = "sync"
        const val SECRETS = "_secrets"
        const val PUSH_DELAY_MS = 1_500L
        const val RETRY_MS = 15_000L
    }
}
