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
package app.fediferry.data

import app.fediferry.data.db.AiModelDao
import app.fediferry.data.model.AiKind
import app.fediferry.data.model.AiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The configured AI models, with their keys kept apart from them: the models
 * in Room, each key encrypted in [TokenStore] under [keyId]. Never logs a key.
 */
class AiModels(
    private val dao: AiModelDao,
    private val keys: TokenStore,
    private val settings: SettingsStore,
) {
    fun observeAll(): Flow<List<AiModel>> = dao.observeAll()

    suspend fun ofKind(kind: AiKind): List<AiModel> {
        migrateOnce()
        return dao.ofKind(kind)
    }

    /** The model to use for [kind]: [id] if it still exists, else the default. */
    suspend fun pick(kind: AiKind, id: String? = null): AiModel? = AiModel.pick(ofKind(kind), id)

    fun apiKey(model: AiModel): String = keys.get(keyId(model.id)).orEmpty()

    fun hasApiKey(model: AiModel): Boolean = apiKey(model).isNotEmpty()

    /**
     * Saves [model]. [newApiKey] replaces its key when given — an empty string
     * removes it — and leaves it alone when null, so a key never has to be
     * shown again to keep it.
     */
    suspend fun save(model: AiModel, newApiKey: String? = null) {
        // The key first: if storing it fails, nothing is half-saved — and the
        // move from old settings, which wipes them afterwards, stops before
        // it could lose a working key.
        when {
            newApiKey == null -> Unit
            newApiKey.isEmpty() -> keys.remove(keyId(model.id))
            else -> keys.put(keyId(model.id), newApiKey)
        }
        val first = dao.count(model.kind) == 0 && dao.byId(model.id) == null
        dao.upsert(if (first) model.copy(isDefault = true) else model)
    }

    suspend fun add(kind: AiKind): AiModel {
        val model = AiModel(id = UUID.randomUUID().toString(), kind = kind, name = "", endpoint = "", model = "", sortOrder = dao.count(kind))
        save(model)
        return dao.byId(model.id) ?: model
    }

    suspend fun setDefault(model: AiModel) = dao.setDefault(model.kind, model.id)

    /** Deletes the model and its key; if it was the default, the next one takes over. */
    suspend fun delete(model: AiModel) {
        dao.delete(model.id)
        keys.remove(keyId(model.id))
        if (model.isDefault) dao.ofKind(model.kind).firstOrNull()?.let { dao.setDefault(model.kind, it.id) }
    }

    private val migration = Mutex()
    @Volatile private var migrated = false

    /**
     * Moves the single model each kind used to have — endpoint, model and a
     * key stored in plain settings — into the list, as its default, and wipes
     * the old settings. Runs once, before anything reads the list.
     */
    suspend fun migrateOnce() {
        if (migrated) return
        migration.withLock {
            if (migrated) return
            val s = settings.current()
            legacyModels(s).forEach { (model, key) ->
                if (dao.count(model.kind) == 0) save(model, key.ifEmpty { null })
            }
            if (s.visionEndpoint.isNotBlank() || s.imageEndpoint.isNotBlank()) settings.clearLegacyModels()
            migrated = true
        }
    }

    companion object {
        fun keyId(modelId: String) = "ai-model:$modelId"

        /** The models the old single-model settings describe, with their keys. */
        fun legacyModels(s: Settings): List<Pair<AiModel, String>> = buildList {
            if (s.visionEndpoint.isNotBlank()) {
                add(
                    AiModel(
                        id = UUID.randomUUID().toString(),
                        kind = AiKind.ALT_TEXT,
                        name = s.visionModel,
                        endpoint = s.visionEndpoint,
                        model = s.visionModel,
                        isDefault = true,
                    ) to s.visionApiKey,
                )
            }
            if (s.imageEndpoint.isNotBlank()) {
                add(
                    AiModel(
                        id = UUID.randomUUID().toString(),
                        kind = AiKind.IMAGE_EDIT,
                        name = s.imageModel,
                        endpoint = s.imageEndpoint,
                        model = s.imageModel,
                        isDefault = true,
                        wireFormat = s.imageWireFormat,
                        maskPolarity = s.imageMaskPolarity,
                    ) to s.imageApiKey,
                )
            }
        }
    }
}
