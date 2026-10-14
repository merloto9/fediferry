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
package app.fediferry.backend

import java.util.concurrent.ConcurrentHashMap

/**
 * What each list screen showed last, kept for as long as the app runs, so
 * switching tabs shows it at once and reads again in the background instead
 * of starting from a loading screen. Kept per project ([PostBackend.key]):
 * one project's posts never show in another's.
 */
object ScreenCache {
    private val states = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(backend: PostBackend, screen: String): T? = states["${backend.key}/$screen"] as T?

    fun put(backend: PostBackend, screen: String, state: Any) {
        states["${backend.key}/$screen"] = state
    }
}

/** Whether the backend answered lately, and when it last did. */
data class Reachability(val ok: Boolean = true, val lastReachedAt: Long? = null)
