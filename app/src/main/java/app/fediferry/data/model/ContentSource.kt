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

import app.fediferry.module.Modules

/** One raw value a source delivers, as a placeholder mapping refers to it. */
data class SourceField(val name: String, val description: String)

/**
 * The stable id of a source module — what items and templates store. Everything
 * else about a source, its fields included, lives in its module under
 * [app.fediferry.module]; this only names it.
 *
 * Stored by [name], so the order here can change freely.
 */
enum class ContentSource {
    NINEGAG,
    PINTEREST,
    REDDIT,
    YOUTUBE,
    ;

    val label: String get() = Modules.of(this).name

    val fields: List<SourceField> get() = Modules.of(this).fields

    companion object {
        fun fromName(name: String?): ContentSource? = entries.firstOrNull { it.name == name }
    }
}
