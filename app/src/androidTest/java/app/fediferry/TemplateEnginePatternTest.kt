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

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fediferry.data.model.Template
import app.fediferry.template.TemplateEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Runs on a device, which is the whole point.
 *
 * Android implements `java.util.regex` over ICU; the host JVM uses OpenJDK's
 * engine. The two disagree about patterns like an unmatched `}` — ICU throws
 * where OpenJDK shrugs. A pattern built in a `TemplateEngine` field initialiser
 * therefore takes the whole object's `<clinit>` down on a device while every
 * host-side unit test stays green, which is exactly what happened once.
 */
@RunWith(AndroidJUnit4::class)
class TemplateEnginePatternTest {

    @Test
    fun templateEngineInitialisesOnDevice() {
        // Touching the object at all is the assertion: a bad pattern surfaces
        // here as ExceptionInInitializerError.
        val out = TemplateEngine.render(
            Template.seed().copy(body = "{tags}\n\nvia {link}", tags = "#meme"),
            emptyList(),
            TemplateEngine.Inputs(link = "https://instagram.com/p/abc"),
        )
        assertEquals("#meme\n\nvia https://instagram.com/p/abc", out)
    }

    @Test
    fun dropsEmptyPlaceholderLinesOnDevice() {
        val out = TemplateEngine.render(
            Template.seed().copy(body = "{tags}\n\nvia {link}", tags = "#meme"),
            emptyList(),
            TemplateEngine.Inputs(link = null),
        )
        assertEquals("#meme", out)
    }

    @Test
    fun collapsesBlankRunsOnDevice() {
        // Exercises the second pattern, {3,}, which ICU does accept: four
        // newlines collapse to the one blank line the template asked for.
        val out = TemplateEngine.render(
            Template.seed().copy(body = "{tags}\n\n\n\n{date}", tags = "#meme"),
            emptyList(),
            TemplateEngine.Inputs(now = Instant.parse("2026-01-15T10:00:00Z")),
        )
        assertEquals(listOf("#meme", "", "2026-01-15"), out.lines())
    }
}
