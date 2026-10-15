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
import app.fediferry.api.SecretIds
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
        /** What a fresh install has; a phone holding only these has nothing of its own. */
        val seeds: () -> List<T> = { emptyList() },
        /** How an entry is named in a conflict, and a line that tells two versions apart. */
        val describe: (T) -> Pair<String, String> = { id(it) to "" },
        /** Settles two versions of one entry without asking, where adding them up is the answer. */
        val combine: ((phone: T, server: T) -> T)? = null,
        /** For kinds with one default per group: which one that is. */
        val defaults: Defaults<T>? = null,
    ) {
        fun encode(value: T): JsonObject = json.encodeToJsonElement(serializer, value).jsonObject

        fun decode(data: JsonObject): T = json.decodeFromJsonElement(serializer, data)

        suspend fun local(): Map<String, JsonObject> = load().associate { id(it) to encode(it) }

        suspend fun apply(data: JsonObject) = upsert(decode(data))
    }

    /** One default per [group]: the default template, the default model of each AI kind. */
    private class Defaults<T>(val group: (T) -> String, val isDefault: (T) -> Boolean, val with: (T, Boolean) -> T)

    private val kinds: List<Kind<*>> = listOf(
        Kind(
            SettingsKinds.TEMPLATE, setOf("templates"), Template.serializer(), { db.templates().all() }, { it.id }, { db.templates().upsert(it) }, { db.templates().deleteAny(it) },
            seeds = { listOf(Template.seed()) },
            describe = { it.name to it.body.lines().first().take(60) + it.tags.takeIf(String::isNotBlank)?.let { t -> " · $t" }.orEmpty() },
            defaults = Defaults({ "" }, { it.isDefault }, { t, d -> t.copy(isDefault = d) }),
        ),
        Kind(
            SettingsKinds.PLACEHOLDER_KEY, setOf("placeholder_keys"), PlaceholderKey.serializer(), { db.placeholderKeys().all() }, { it.id }, { db.placeholderKeys().upsert(it) }, { db.placeholderKeys().delete(it) },
            seeds = { listOf(PlaceholderKey.seed(), PlaceholderKey.tagsSeed()) },
            describe = { "{${it.name}}" to it.mappings.entries.joinToString(" · ") { (k, v) -> "$k: $v" }.take(80) },
        ),
        Kind(
            SettingsKinds.HASHTAG, setOf("hashtags"), Hashtag.serializer(), { db.hashtags().all() }, { it.tag }, { db.hashtags().upsert(it) }, { db.hashtags().delete(it) },
            seeds = { Template.seed().hashtagList.mapIndexed { i, tag -> Hashtag(tag, i) } },
            // The same hashtag on both sides: only its place in the list can differ; the project's stays.
            combine = { _, server -> server },
        ),
        Kind(
            SettingsKinds.HASHTAG_USAGE, setOf("hashtag_usage"), HashtagUsage.serializer(), { db.hashtagUsage().all() }, { it.key }, { db.hashtagUsage().upsert(it) }, { db.hashtagUsage().delete(it) },
            // Counted on two sides apart: both counts together.
            combine = { phone, server -> server.copy(uses = phone.uses + server.uses) },
        ),
        Kind(
            SettingsKinds.AI_MODEL, setOf("ai_models"), AiModel.serializer(), { db.aiModels().all() }, { it.id }, { db.aiModels().upsert(it) }, { db.aiModels().delete(it) },
            describe = { it.displayName to listOfNotNull(it.model.ifBlank { null }, runCatching { java.net.URI(it.endpoint).host }.getOrNull()).joinToString(" · ") },
            defaults = Defaults({ it.kind.name }, { it.isDefault }, { m, d -> m.copy(isDefault = d) }),
        ),
        Kind(
            SettingsKinds.CLEANUP_PROFILE, setOf("cleanup_profiles"), CleanupProfile.serializer(), { db.cleanup().profiles() }, { it.id }, { db.cleanup().upsertProfile(it) }, { db.cleanup().deleteProfileRow(it) },
            describe = { it.name to "" },
            defaults = Defaults({ "" }, { it.isDefault }, { p, d -> p.copy(isDefault = d) }),
        ),
        Kind(
            SettingsKinds.CLEANUP_RULE, setOf("cleanup_rules"), ProfileRule.serializer(), { db.cleanup().allRules() }, { it.id }, { db.cleanup().upsertRule(it) }, { db.cleanup().deleteRule(it) },
            describe = { it.treatment.name to "${it.left}, ${it.top} – ${it.right}, ${it.bottom}" + if (it.enabled) "" else " (off)" },
        ),
        Kind(
            SettingsKinds.SOURCE, setOf("sources"), Source.serializer(), { db.sources().all() }, { it.id }, { db.sources().upsert(it) }, { db.sources().delete(it) },
            describe = { it.displayName to it.handle },
        ),
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
        // A model still in the old single-model settings is not in the list to sync yet.
        aiModels.migrateOnce()
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

    // --- joining a project with a phone that has its own ------------------------

    /**
     * What joining [client]'s project would do to the settings: what each side
     * adds to the other, and what both have differently. Changes nothing.
     */
    suspend fun preview(client: ServerClient): SettingsPreview {
        aiModels.migrateOnce()
        val preview = SettingsPreview()
        for (kind in kinds) previewKind(kind, client, preview)
        return preview
    }

    private suspend fun <T> previewKind(kind: Kind<T>, client: ServerClient, into: SettingsPreview) {
        val remote = client.settings(kind.name).associate { it.id to kind.decode(it.data) }
        val local = kind.load().associateBy(kind.id)
        val seeds = kind.seeds().associate { kind.id(it) to digest(kind.encode(it)) }
        if (local.any { (id, value) -> seeds[id] != digest(kind.encode(value)) }) into.phoneHasOwn = true
        (local.keys - remote.keys).takeIf { it.isNotEmpty() }?.let { into.fromPhone[kind.name] = it.size }
        (remote.keys - local.keys).takeIf { it.isNotEmpty() }?.let { into.fromServer[kind.name] = it.size }
        for (id in local.keys.intersect(remote.keys)) {
            val l = local.getValue(id)
            val r = remote.getValue(id)
            if (kind.combine != null || !differ(kind, l, r)) continue
            val (phoneName, phoneLine) = kind.describe(l)
            val (_, serverLine) = kind.describe(r)
            into.conflicts += MergeConflict(entryKey(kind.name, id), kind.name, phoneName, phoneLine, serverLine)
        }
        val d = kind.defaults ?: return
        val groups = (local.values + remote.values).map(d.group).toSet()
        for (group in groups) {
            val phone = local.values.firstOrNull { d.group(it) == group && d.isDefault(it) }
            val server = remote.values.firstOrNull { d.group(it) == group && d.isDefault(it) }
            if (phone == null || server == null || kind.id(phone) == kind.id(server)) continue
            into.conflicts += MergeConflict(defaultKey(kind.name, group), kind.name, group, kind.describe(phone).first, kind.describe(server).first, isDefault = true)
        }
    }

    /** Whether two versions of one entry differ in more than which is the default; that is settled apart. */
    private fun <T> differ(kind: Kind<T>, phone: T, server: T): Boolean {
        val d = kind.defaults
        return if (d == null) digest(kind.encode(phone)) != digest(kind.encode(server))
        else digest(kind.encode(d.with(phone, false))) != digest(kind.encode(d.with(server, false)))
    }

    /**
     * The first sync with a project for a phone that has settings of its own:
     * nothing is dropped. What only one side has goes to the other, hashtag
     * counts are added up, and where both have one thing differently,
     * [choices] (keyed by [MergeConflict.key]) says whose stays — the
     * project's, where it says nothing.
     */
    suspend fun merge(client: ServerClient, projectId: String, choices: Map<String, MergeSide>) = mutex.withLock {
        aiModels.migrateOnce()
        val state = mutableMapOf<String, MutableMap<String, String>>()
        for (kind in kinds) state[kind.name] = mergeKind(kind, client, choices)
        writeState(projectId, state)
    }

    private suspend fun <T> mergeKind(kind: Kind<T>, client: ServerClient, choices: Map<String, MergeSide>): MutableMap<String, String> {
        val remote = client.settings(kind.name).associate { it.id to kind.decode(it.data) }
        val local = kind.load().associateBy(kind.id)
        val merged = LinkedHashMap<String, T>()
        for (id in remote.keys + local.keys) {
            val l = local[id]
            val r = remote[id]
            merged[id] = when {
                l == null -> r!!
                r == null -> l
                kind.combine != null -> kind.combine.invoke(l, r)
                !differ(kind, l, r) -> r
                choices[entryKey(kind.name, id)] == MergeSide.PHONE -> l
                else -> {
                    // The project's AI model wins: so does its key, which only the server has.
                    if (kind.name == SettingsKinds.AI_MODEL) aiModels.forgetKey(id)
                    r
                }
            }
        }
        kind.defaults?.let { d ->
            for (group in merged.values.map(d.group).toSet()) {
                val phone = local.values.firstOrNull { d.group(it) == group && d.isDefault(it) }?.let(kind.id)
                val server = remote.values.firstOrNull { d.group(it) == group && d.isDefault(it) }?.let(kind.id)
                val winner = if (phone != null && choices[defaultKey(kind.name, group)] == MergeSide.PHONE) phone else server ?: phone
                for ((id, value) in merged.filterValues { d.group(it) == group }) {
                    if (d.isDefault(value) != (id == winner)) merged[id] = d.with(value, id == winner)
                }
            }
        }
        val settled = mutableMapOf<String, String>()
        for ((id, value) in merged) {
            val data = kind.encode(value)
            val hash = digest(data)
            if (local[id]?.let { digest(kind.encode(it)) } != hash) kind.upsert(value)
            if (remote[id]?.let { digest(kind.encode(it)) } != hash) client.putSetting(kind.name, id, data)
            settled[id] = hash
        }
        return settled
    }

    /** AI keys go to the server, which will use them; they never come back. */
    private suspend fun pushSecrets() = mutex.withLock {
        val connection = connections.current() ?: return@withLock
        val client = connections.client(connection)
        runCatching {
            val state = readState(connection.projectId) ?: mutableMapOf()
            val sent = state.getOrPut(SECRETS) { mutableMapOf() }
            val models = db.aiModels().all()
            for (model in models) {
                val id = SecretIds.aiModel(model.id)
                val key = aiModels.apiKey(model)
                if (key.isEmpty()) {
                    // The key was cleared here: the server's copy goes too.
                    if (id in sent) {
                        client.deleteSecret(id)
                        sent.remove(id)
                    }
                    continue
                }
                val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                if (sent[id] == hash) continue
                client.putSecret(id, key)
                sent[id] = hash
            }
            // A deleted model's key went with its settings entry on the server.
            val kept = models.map { SecretIds.aiModel(it.id) }.toSet()
            sent.keys.retainAll { it in kept }
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

    companion object {
        private const val LOG = "sync"
        private const val SECRETS = "_secrets"
        private const val PUSH_DELAY_MS = 1_500L
        private const val RETRY_MS = 15_000L

        fun entryKey(kind: String, id: String) = "$kind/$id"

        fun defaultKey(kind: String, group: String) = "default/$kind/$group"
    }
}
