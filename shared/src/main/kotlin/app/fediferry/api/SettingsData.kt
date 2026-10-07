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
package app.fediferry.api

import app.fediferry.data.model.ContentSource
import app.fediferry.template.PlaceholderSpec
import app.fediferry.template.TemplateSpec
import app.fediferry.data.model.Hashtags
import kotlinx.serialization.Serializable

/**
 * The project settings as the server reads them from the JSON the app syncs.
 * Field names match the app's stored objects; anything unknown is ignored.
 */
@Serializable
data class TemplateData(
    val id: String,
    val name: String = "",
    override val body: String = "",
    val tags: String = "",
    val visibility: String = "PUBLIC",
    val contentWarning: String? = null,
    /** NONE, STATIC or VISION. */
    val altTextMode: String = "NONE",
    val staticAltText: String? = null,
    val accountId: String? = null,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val excludedSources: Set<ContentSource> = emptySet(),
    override val addSourceHashtags: Boolean = true,
) : TemplateSpec {
    override val hashtagList: List<String> get() = Hashtags.parse(tags)
    override fun usesSource(source: ContentSource): Boolean = source !in excludedSources

    companion object {
        /** What a project with no template of its own starts with. */
        val FALLBACK = TemplateData(id = "fallback", name = "Meme", body = "{tags}\n\nvia {link}")
    }
}

@Serializable
data class PlaceholderKeyData(
    val id: String,
    override val name: String,
    val mappings: Map<String, String> = emptyMap(),
    val sortOrder: Int = 0,
) : PlaceholderSpec {
    override val isTags: Boolean get() = id == PlaceholderSpec.TAGS
    override fun recipeFor(source: ContentSource): String = mappings[source.name].orEmpty()
}

@Serializable
data class AiModelData(
    val id: String,
    /** ALT_TEXT or IMAGE_EDIT. */
    val kind: String,
    val name: String = "",
    val endpoint: String = "",
    val model: String = "",
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
)

@Serializable
data class ProfileRuleData(
    val id: String,
    val profileId: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val treatment: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)
