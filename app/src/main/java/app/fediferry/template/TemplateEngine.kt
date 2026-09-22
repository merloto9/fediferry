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

import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.Item
import app.fediferry.data.model.PlaceholderKey
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
 *
 * Two layers feed it. `{link}` and `{date}` are built in. Every other
 * placeholder is a [PlaceholderKey] the user defined, filled from the raw
 * fields of the source the item came from through that key's recipe for the
 * source — and only when the template reads that source at all.
 *
 * `{tags}` is the exception, and resolves in two steps. Rendering a draft leaves
 * it in the text as written; [finish] fills it with the post's hashtags when the
 * post is sent. Hashtags therefore stay editable after the draft is written,
 * and a hand-written `{tags}` in the editor works like the template's own.
 */
object TemplateEngine {

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    data class Inputs(
        val link: String? = null,
        /** Where the item's data came from; null leaves every user-defined placeholder empty. */
        val source: ContentSource? = null,
        /** The source's raw fields, by [app.fediferry.data.model.SourceField.name]. */
        val fields: Map<String, String> = emptyMap(),
        val now: Instant = Instant.now(),
    )

    /** Everything an existing item can tell the engine about itself. */
    fun inputsOf(item: Item) = Inputs(link = item.sourceUrl, source = item.origin, fields = item.sourceFields)

    fun render(template: Template, keys: List<PlaceholderKey>, inputs: Inputs): String {
        val values = keys.filterNot { it.isTags }.associate { it.name to valueOf(it, template, inputs) } + mapOf(
            // After the keys, so a built-in always wins. Settings refuses the
            // clash anyway; this only keeps an old row from shadowing {link}.
            "link" to inputs.link.orEmpty(),
            "date" to dateFormat.format(inputs.now.atZone(ZoneId.systemDefault())),
            // Left for [finish]. Standing in for itself also counts as filled,
            // so its line survives until the hashtags are known.
            PlaceholderKey.TAGS to TAGS_TOKEN,
        )
        return renderText(template.body, values)
    }

    /**
     * The text that is actually posted: [body] with `{tags}` replaced by
     * [hashtags]. Nothing else is touched — every other placeholder was filled
     * when the draft was written, and whatever the user typed since is theirs.
     * A line that held only `{tags}` disappears when there are none.
     */
    fun finish(body: String, hashtags: List<String>): String =
        renderText(body, mapOf(PlaceholderKey.TAGS to Hashtags.format(hashtags)))

    /** What [item] will actually post. */
    fun postTextOf(item: Item): String = finish(item.bodyText, item.hashtagList)

    /**
     * The hashtags a post starts with: the template's picks, then — when
     * [withSource] — whatever the source's `{tags}` recipe adds, the source's
     * own tags, say. A tag in both is kept once, spelled as the template has it.
     */
    fun hashtagsFor(
        template: Template,
        keys: List<PlaceholderKey>,
        inputs: Inputs,
        withSource: Boolean = template.addSourceHashtags,
    ): List<String> {
        val fromSource = if (withSource) sourceHashtags(template, keys, inputs) else emptyList()
        return Hashtags.union(template.hashtagList, fromSource)
    }

    /** What the source's `{tags}` recipe yields for this post, as hashtags. */
    fun sourceHashtags(template: Template, keys: List<PlaceholderKey>, inputs: Inputs): List<String> =
        keys.firstOrNull { it.isTags }?.let { Hashtags.parse(valueOf(it, template, inputs)) }.orEmpty()

    /**
     * What [key] comes to for this item. Best-effort only: no downstream code
     * may assume it resolved.
     */
    fun valueOf(key: PlaceholderKey, template: Template, inputs: Inputs): String {
        val source = inputs.source ?: return ""
        if (!template.usesSource(source)) return ""
        val recipe = key.recipeFor(source)
        if (recipe.isBlank()) return ""
        // Every field the source declares is known, so a missing one empties
        // its line instead of leaking "{title}" into a post.
        val fields = source.fields.associate { it.name to "" } + inputs.fields
        return renderText(recipe, fields)
    }

    /** Placeholders in [text] that nothing fills, in order of first use. */
    fun unknownIn(text: String, known: Collection<String>): List<String> =
        PLACEHOLDER.findAll(text).map { it.groupValues[1] }.filter { it !in known }.distinct().toList()

    private fun renderText(text: String, values: Map<String, String>): String =
        text.lines()
            .mapNotNull { line -> renderLine(line, values) }
            .joinToString("\n")
            .replace(BLANK_RUN, "\n\n")
            .trim()

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
    private const val TAGS_TOKEN = "{${PlaceholderKey.TAGS}}"

    private val PLACEHOLDER = Regex("""\{(\w+)\}""")
    private val BLANK_RUN = Regex("""\n{3,}""")
}
