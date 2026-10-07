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
package app.fediferry.channel

import app.fediferry.api.ChannelDto
import app.fediferry.api.PostDto
import app.fediferry.api.Violation
import app.fediferry.mastodon.MastodonText
import app.fediferry.template.TemplateEngine

/**
 * Checks a post against what its channel takes. The server runs it before a
 * post may become ready; the editor runs the same code while writing, so the
 * person sees a problem before pressing "Ready" rather than after.
 */
object PostValidator {
    const val NO_CHANNEL = "ready.no_channel"
    const val EMPTY = "ready.empty"
    const val TOO_LONG = "ready.too_long"
    const val TOO_MANY_MEDIA = "ready.too_many_media"
    const val NEEDS_MEDIA = "ready.needs_media"
    const val MEDIA_TYPE = "ready.media_type"
    const val MEDIA_TOO_BIG = "ready.media_too_big"
    const val ALT_TOO_LONG = "ready.alt_too_long"
    const val NO_CONTENT_WARNING = "ready.no_content_warning"
    const val UNKNOWN_PLACEHOLDER = "ready.unknown_placeholder"
    const val NO_ALT = "ready.no_alt"
    const val LINK_MAY_IDENTIFY = "ready.link_may_identify"

    /** The text as it will be posted: `{tags}` filled in. */
    fun finalText(post: PostDto): String = TemplateEngine.finish(post.body, post.hashtags).trim()

    /** What the channel counts: Mastodon counts the content warning with the text. */
    fun length(post: PostDto): Int = MastodonText.length(finalText(post)) + post.contentWarning.orEmpty().length

    /**
     * Every problem, blocking ones first. [knownPlaceholders] are the names the
     * project defines; a `{name}` left in the text that is none of them was
     * most likely a typo, and would be posted as written.
     */
    fun check(post: PostDto, channel: ChannelDto?, knownPlaceholders: Collection<String> = emptyList()): List<Violation> {
        val found = mutableListOf<Violation>()
        val text = finalText(post)
        if (channel == null) found += Violation(NO_CHANNEL)
        if (text.isBlank() && post.media.isEmpty()) found += Violation(EMPTY)

        val caps = channel?.capabilities
        if (caps != null) {
            val length = length(post)
            if (length > caps.maxCharacters) found += Violation(TOO_LONG, mapOf("length" to "$length", "max" to "${caps.maxCharacters}"))
            if (post.media.size > caps.maxMediaAttachments) {
                found += Violation(TOO_MANY_MEDIA, mapOf("count" to "${post.media.size}", "max" to "${caps.maxMediaAttachments}"))
            }
            if (post.media.isEmpty() && !caps.textOnly && text.isNotBlank()) found += Violation(NEEDS_MEDIA)
            if (!post.contentWarning.isNullOrBlank() && !caps.contentWarning) found += Violation(NO_CONTENT_WARNING)
            post.media.forEach { m ->
                val number = "${m.position + 1}"
                val video = m.asset.mime.startsWith("video/")
                val allowed = if (video) caps.videoAllowed else m.asset.mime in caps.imageMimeTypes
                if (!allowed) found += Violation(MEDIA_TYPE, mapOf("picture" to number, "type" to m.asset.mime))
                val max = if (video) caps.maxVideoBytes else caps.maxImageBytes
                if (allowed && m.asset.bytes > max) {
                    found += Violation(MEDIA_TOO_BIG, mapOf("picture" to number, "mb" to megabytes(m.asset.bytes), "max" to megabytes(max)))
                }
                val alt = m.altText.orEmpty().length
                if (alt > caps.altTextMaxLength) {
                    found += Violation(ALT_TOO_LONG, mapOf("picture" to number, "length" to "$alt", "max" to "${caps.altTextMaxLength}"))
                }
            }
        }

        val unknown = TemplateEngine.unknownIn(text, knownPlaceholders + BUILT_IN)
        if (unknown.isNotEmpty()) found += Violation(UNKNOWN_PLACEHOLDER, mapOf("names" to unknown.joinToString(", ") { "{$it}" }), blocking = false)
        post.media.filter { it.altText.isNullOrBlank() }.forEach {
            found += Violation(NO_ALT, mapOf("picture" to "${it.position + 1}"), blocking = false)
        }
        if (post.linkMayIdentify && post.sourceUrl != null && post.sourceUrl in text) found += Violation(LINK_MAY_IDENTIFY, blocking = false)
        return found.sortedByDescending { it.blocking }
    }

    private val BUILT_IN = listOf("link", "date", "tags")

    private fun megabytes(bytes: Long) = "%.1f".format(java.util.Locale.ROOT, bytes / 1_048_576.0)
}
