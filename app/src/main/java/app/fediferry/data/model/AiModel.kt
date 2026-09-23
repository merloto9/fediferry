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
package app.fediferry.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** What a model is for. Each kind keeps its own list and its own default. */
enum class AiKind {
    /** Describes a picture, for alt text. An OpenAI-compatible chat model. */
    ALT_TEXT,

    /** Erases marked areas of a picture, for "Erase with AI". An image-edit model. */
    IMAGE_EDIT,
}

/**
 * One configured AI model: a server, the model it runs, and how to talk to it.
 *
 * The API key is not here. It lives encrypted beside the Mastodon tokens,
 * keyed by [id], so a database export never carries it.
 */
@Entity(tableName = "ai_models", indices = [Index("kind")])
data class AiModel(
    @PrimaryKey val id: String,
    val kind: AiKind,
    /** What the user calls it — "Gemini Flash", "Local Ollama". */
    val name: String,
    val endpoint: String,
    val model: String,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    /** Image models only: the request shape, an [app.fediferry.media.cleanup.EditWireFormat] name. */
    val wireFormat: String = "MULTIPART",
    /** Image models only: how the mask marks the area, a [app.fediferry.media.cleanup.MaskPolarity] name. */
    val maskPolarity: String = "TRANSPARENT_HOLE",
) {
    /** A name to show even when the user left theirs empty. */
    val displayName: String
        get() = name.ifBlank { model.ifBlank { runCatching { java.net.URI(endpoint).host }.getOrNull() ?: "Unnamed model" } }

    /** Whether [endpoint] is a web address a request can go to at all. */
    val hasValidEndpoint: Boolean get() = isValidEndpoint(endpoint)

    companion object {
        /**
         * An endpoint or model name never contains whitespace, but a keyboard's
         * autocorrect happily splits one ("generative language.googleapis.com").
         */
        fun clean(value: String): String = value.filterNot { it.isWhitespace() }

        fun isValidEndpoint(endpoint: String): Boolean {
            val uri = runCatching { java.net.URI(endpoint) }.getOrNull() ?: return false
            return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        }

        /**
         * The model to use: the one asked for if it still exists, else the
         * default, else the first. Null only when none is configured.
         */
        fun pick(models: List<AiModel>, id: String? = null): AiModel? =
            models.firstOrNull { it.id == id }
                ?: models.firstOrNull { it.isDefault }
                ?: models.firstOrNull()
    }
}
