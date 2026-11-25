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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The kinds of project settings the server keeps for its phones. Each object
 * is stored as the JSON the app keeps it in; adding a kind needs no new table.
 */
/** Names of the secrets a project keeps on the server; the phone stores its copies under the same names. */
object SecretIds {
    private const val AI_MODEL = "ai-model:"

    /** The API key of the AI model [modelId]. */
    fun aiModel(modelId: String) = AI_MODEL + modelId

    /** The model whose key [secretId] is, or null for another kind of secret. */
    fun aiModelOf(secretId: String): String? = secretId.takeIf { it.startsWith(AI_MODEL) }?.removePrefix(AI_MODEL)
}

object SettingsKinds {
    const val TEMPLATE = "template"
    const val PLACEHOLDER_KEY = "placeholder_key"
    const val HASHTAG = "hashtag"
    const val HASHTAG_USAGE = "hashtag_usage"
    const val AI_MODEL = "ai_model"
    const val CLEANUP_PROFILE = "cleanup_profile"
    const val CLEANUP_RULE = "cleanup_rule"
    const val SOURCE = "source"

    /** A project-wide switch, one [ProjectOption] per entry; ids in [ProjectOptions]. */
    const val OPTION = "option"

    val ALL = setOf(TEMPLATE, PLACEHOLDER_KEY, HASHTAG, HASHTAG_USAGE, AI_MODEL, CLEANUP_PROFILE, CLEANUP_RULE, SOURCE, OPTION)

    /** The change-feed type for a kind, e.g. `settings.template`. */
    fun changeType(kind: String) = "settings.$kind"
}

/** A switch that holds for the whole project, on the phone and on the server alike. */
@Serializable
data class ProjectOption(val id: String, val enabled: Boolean)

/** Ids of the [ProjectOption]s. */
object ProjectOptions {
    /** After a post goes out, hashtags it carried that are not on the list yet join it. Off when unset. */
    const val REMEMBER_SENT_HASHTAGS = "remember_sent_hashtags"
}

@Serializable
data class SettingsEntryDto(val id: String, val data: JsonObject, val updatedAt: Long)

/** `PUT /secrets/{id}`: a secret goes in and never comes back out. */
@Serializable
data class SecretInput(val value: String)
