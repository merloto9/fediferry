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

import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.PlaceholderKey
import app.fediferry.module.Modules
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModulesTest {

    @Test
    fun `every source has exactly one module`() {
        assertEquals(ContentSource.entries.toSet(), Modules.all.map { it.source }.toSet())
        assertEquals(ContentSource.entries.size, Modules.all.size)
    }

    @Test
    fun `a module's resolver reports the module's own source`() {
        val http = OkHttpClient()
        Modules.all.forEach { module ->
            module.resolver(http)?.let { assertEquals(module.name, module.source, it.source) }
        }
    }

    @Test
    fun `default recipes only use fields the module sends`() {
        Modules.all.forEach { module ->
            val names = module.fields.map { it.name }
            module.defaultRecipes.values.forEach { recipe ->
                val used = Regex("""\{(\w+)\}""").findAll(recipe).map { it.groupValues[1] }.toList()
                assertTrue("${module.name}: $recipe", names.containsAll(used))
            }
        }
    }

    @Test
    fun `caption is seeded from the modules, still without Pinterest`() {
        val seed = PlaceholderKey.seed().mappings

        assertEquals(
            mapOf("NINEGAG" to "{title}", "REDDIT" to "{title}", "YOUTUBE" to "{text}"),
            seed,
        )
    }

    @Test
    fun `tags starts with no source adding hashtags`() {
        assertEquals(emptyMap<String, String>(), PlaceholderKey.tagsSeed().mappings)
    }
}
