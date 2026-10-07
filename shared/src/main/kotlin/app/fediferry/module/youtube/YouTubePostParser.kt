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
package app.fediferry.module.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads community posts out of the JSON blob a channel's posts page carries.
 *
 * YouTube publishes no API for community posts — the Data API covers videos,
 * channels, playlists and comments and stops there — so the page's own
 * `ytInitialData` is the only route. That makes this the most fragile thing in
 * the app: it reads a private shape that can change without notice.
 *
 * Written accordingly. It searches for the renderer by name at any depth rather
 * than following a fixed path, so a layer added around the list does not break
 * it, and it returns an empty list rather than a wrong one. The caller reports
 * emptiness to the user instead of showing a blank screen, because "YouTube
 * changed something" and "this channel has no posts" must not look alike.
 */
object YouTubePostParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The author's avatar rides in the same structure; it is not a post image. */
    private const val MIN_IMAGE_WIDTH = 200

    /**
     * Pulls `ytInitialData` out of a channel page.
     *
     * The blob is assigned inside a script tag, and the assignment is written
     * more than one way depending on the page, so both spellings are tried.
     */
    fun extractInitialData(html: String): String? {
        for (pattern in INITIAL_DATA) {
            for (match in pattern.findAll(html)) {
                var i = match.range.last + 1
                while (i < html.length && html[i].isWhitespace()) i++
                if (i >= html.length) continue

                val blob = when (html[i]) {
                    // Desktop serves the object literally.
                    '{' -> balancedObjectAt(html, i)
                    // Mobile serves it as a quoted JavaScript string whose
                    // braces and quotes are hex-escaped, and parses it at
                    // runtime. Looking for a brace here finds one belonging to
                    // something else entirely.
                    '\'', '"' -> jsStringAt(html, i)?.let(::unescapeJs)
                    else -> null
                }
                if (blob != null && blob.startsWith("{")) return blob
            }
        }
        return null
    }

    /** Reads one quoted JavaScript string, respecting backslash escapes. */
    private fun jsStringAt(text: String, from: Int): String? {
        val quote = text.getOrNull(from) ?: return null
        if (quote != '\'' && quote != '"') return null
        val sb = StringBuilder()
        var i = from + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= text.length) return null
                    sb.append(c).append(text[i + 1])
                    i += 2
                }
                c == quote -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /**
     * Decodes a JavaScript string literal to the text it stands for.
     *
     * The decoded text *is* the JSON document, so every escape becomes the
     * character it denotes. Re-emitting them as JSON escapes was the first
     * attempt and it corrupted the document: `\\` in the source is one
     * backslash, and turning it back into two made the `\"` inside the JSON
     * into `\\"`, which stops parsing dead partway through.
     */
    private fun unescapeJs(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                sb.append(c)
                i++
                continue
            }
            when (val next = raw[i + 1]) {
                'x' -> {
                    val code = raw.substring(i + 2, minOf(i + 4, raw.length)).toIntOrNull(16)
                    if (code != null) { sb.append(code.toChar()); i += 4 } else { sb.append(c); i++ }
                }
                'u' -> {
                    val code = raw.substring(i + 2, minOf(i + 6, raw.length)).toIntOrNull(16)
                    if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(c); i++ }
                }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                else -> { sb.append(next); i += 2 }
            }
        }
        return sb.toString()
    }

    fun parse(initialData: String): List<SourcePost> = runCatching {
        val root = json.parseToJsonElement(initialData)
        collect(root, "backstagePostRenderer").mapNotNull { it.toPost() }
    }.getOrDefault(emptyList())

    private fun JsonElement.toPost(): SourcePost? {
        val post = this as? JsonObject ?: return null
        val id = post["postId"]?.jsonPrimitive?.contentOrNull ?: return null

        // Images come only from the attachment; the author's avatar lives
        // elsewhere in the same object and is not part of the post.
        val attachment = post["backstageAttachment"]
        val images = attachment?.let { imageUrlsOf(it) }.orEmpty()

        return SourcePost(
            id = id,
            author = post["authorText"]?.readText(),
            text = post["contentText"]?.readText(),
            publishedText = post["publishedTimeText"]?.readText(),
            imageUrls = images,
        )
    }

    /** The biggest thumbnail of each image in the attachment, in page order. */
    private fun imageUrlsOf(attachment: JsonElement): List<String> =
        collect(attachment, "thumbnails").mapNotNull { set ->
            val candidates = (set as? JsonArray).orEmpty().mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val width = obj["width"]?.jsonPrimitive?.intOrNull ?: 0
                width to url
            }
            candidates.maxByOrNull { it.first }
                ?.takeIf { it.first >= MIN_IMAGE_WIDTH }
                ?.second
                ?.let { if (it.startsWith("//")) "https:$it" else it }
        }.distinct()

    /** Text in these blobs is either `runs` of fragments or a single string. */
    private fun JsonElement.readText(): String? {
        val obj = this as? JsonObject ?: return null
        obj["simpleText"]?.jsonPrimitive?.contentOrNull?.let { return it.ifBlank { null } }
        val runs = obj["runs"] as? JsonArray ?: return null
        return runs.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            .joinToString("")
            .ifBlank { null }
    }

    /** Every value stored under [key], at any depth. */
    private fun collect(node: JsonElement, key: String, into: MutableList<JsonElement> = mutableListOf()): List<JsonElement> {
        when (node) {
            is JsonObject -> node.forEach { (k, v) ->
                if (k == key) into += v
                collect(v, key, into)
            }
            is JsonArray -> node.forEach { collect(it, key, into) }
            is JsonPrimitive -> Unit
        }
        return into
    }

    /**
     * Reads one balanced JSON object out of [text] starting at [from].
     *
     * A regex cannot do this: the blob contains braces inside strings, and
     * stopping at the first `}` truncates it. Quotes and escapes are tracked so
     * a `}` inside a caption does not end the object early.
     */
    private fun balancedObjectAt(text: String, from: Int): String? {
        if (from < 0 || from >= text.length || text[from] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in from until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(from, i + 1)
                }
            }
        }
        return null
    }

    private val INITIAL_DATA = listOf(
        Regex("""ytInitialData"]\s*=\s*"""),
        Regex("""ytInitialData\s*=\s*"""),
    )
}
