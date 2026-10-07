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

import app.fediferry.api.ChannelCapabilities
import app.fediferry.api.ChannelDto
import app.fediferry.api.MediaAssetDto
import app.fediferry.api.PostDto
import app.fediferry.api.PostMediaDto
import app.fediferry.channel.PostValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostValidatorTest {

    private val channel = ChannelDto("social/me", "MASTODON", "Me", "social", "me", "Me")
    private fun picture(position: Int = 0, mime: String = "image/png", bytes: Long = 1000, alt: String? = "A cat") =
        PostMediaDto(position, MediaAssetDto("a$position", "s", mime, bytes = bytes), altText = alt)
    private fun post(body: String = "{tags}\n\nvia https://example.org/x", tags: List<String> = listOf("#meme"), media: List<PostMediaDto> = listOf(picture()), cw: String? = null) =
        PostDto("p", "DRAFT", 1, channelId = channel.id, body = body, hashtags = tags, contentWarning = cw, media = media, createdAt = 0, updatedAt = 0)

    private fun codes(post: PostDto, channel: ChannelDto? = this.channel) = PostValidator.check(post, channel).map { it.code }

    @Test
    fun `a good post has nothing to say`() = assertEquals(emptyList<String>(), codes(post()))

    @Test
    fun `the final text has the hashtags in`() = assertEquals("#meme\n\nvia https://example.org/x", PostValidator.finalText(post()))

    @Test
    fun `no channel blocks`() = assertEquals(listOf(PostValidator.NO_CHANNEL), codes(post(), channel = null))

    @Test
    fun `too long counts links as 23 and the content warning`() {
        val small = channel.copy(capabilities = ChannelCapabilities(maxCharacters = 40))
        // "#meme\n\nvia " is 11, the link 23: 34 fits; with a 10-letter warning it does not.
        assertTrue(PostValidator.check(post(), small).isEmpty())
        val v = PostValidator.check(post(cw = "spiders!!!"), small).single()
        assertEquals(PostValidator.TOO_LONG, v.code)
        assertEquals("44", v.args["length"])
    }

    @Test
    fun `media the channel does not take blocks`() {
        val strict = channel.copy(capabilities = ChannelCapabilities(maxMediaAttachments = 1, imageMimeTypes = listOf("image/jpeg"), maxImageBytes = 500))
        val found = codes(post(media = listOf(picture(0, "image/png"), picture(1, "image/jpeg", bytes = 2000))), strict)
        assertEquals(listOf(PostValidator.TOO_MANY_MEDIA, PostValidator.MEDIA_TYPE, PostValidator.MEDIA_TOO_BIG), found)
    }

    @Test
    fun `missing alt text warns but does not block`() {
        val v = PostValidator.check(post(media = listOf(picture(alt = null))), channel).single()
        assertEquals(PostValidator.NO_ALT, v.code)
        assertFalse(v.blocking)
    }

    @Test
    fun `an empty post blocks`() = assertEquals(listOf(PostValidator.EMPTY), codes(post(body = "", tags = emptyList(), media = emptyList())))

    @Test
    fun `a text-only post on a picture channel blocks`() =
        assertEquals(listOf(PostValidator.NEEDS_MEDIA), codes(post(media = emptyList()), channel.copy(capabilities = ChannelCapabilities(textOnly = false))))

    @Test
    fun `a misspelt placeholder warns`() {
        val v = PostValidator.check(post(body = "{captoin} {tags}"), channel, listOf("caption")).single()
        assertEquals(PostValidator.UNKNOWN_PLACEHOLDER, v.code)
        assertEquals("{captoin}", v.args["names"])
    }
}
