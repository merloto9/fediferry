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
package app.fediferry.template

import app.fediferry.data.model.Template
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders a template body into post text.
 *
 * Every placeholder resolves to the empty string when its input is missing, and
 * a line whose placeholders *all* came back empty is dropped whole. A template
 * written as `{tags}\n\nvia {link}` must not post a dangling "via" when the user
 * shared a screenshot without the permalink.
 *
 * An unknown placeholder is left verbatim: that is an authoring mistake, and
 * silently swallowing it would hide it.
 */
object TemplateEngine {

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    data class Inputs(
        val link: String? = null,
        val caption: String? = null,
        val now: Instant = Instant.now(),
    )

    fun render(template: Template, inputs: Inputs): String {
        val values = mapOf(
            "link" to inputs.link.orEmpty(),
            "tags" to template.tags.trim(),
            "date" to dateFormat.format(inputs.now.atZone(ZoneId.systemDefault())),
            // Best-effort only. No downstream code may assume this resolved.
            "caption" to inputs.caption.orEmpty(),
        )

        val rendered = template.body.lines().mapNotNull { line -> renderLine(line, values) }
        return rendered.joinToString("\n")
            .replace(BLANK_RUN, "\n\n")
            .trim()
    }

    /** Returns null when the line existed only to carry placeholders that are empty. */
    private fun renderLine(line: String, values: Map<String, String>): String? {
        var known = 0
        var filled = 0

        val substituted = PLACEHOLDER.replace(line) { match ->
            val value = values[match.groupValues[1]] ?: return@replace match.value
            known++
            if (value.isNotEmpty()) filled++
            value
        }

        return if (known > 0 && filled == 0) null else substituted.trimEnd()
    }

    // Both braces are escaped. Android's java.util.regex is ICU-backed and
    // rejects an unmatched `}` outright, where the OpenJDK engine the unit tests
    // run on accepts it — so an unescaped brace compiles on the host and throws
    // PatternSyntaxException on a device.
    private val PLACEHOLDER = Regex("""\{(\w+)\}""")
    private val BLANK_RUN = Regex("""\n{3,}""")
}
