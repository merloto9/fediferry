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

import app.fediferry.media.cleanup.EraseMask
import app.fediferry.media.cleanup.HttpImageEditProvider
import app.fediferry.media.cleanup.MaskPolarity
import app.fediferry.media.cleanup.Region
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The parts of the image-model adapter that do not need a model: how the mask
 * is drawn, and how a response is unpacked. What a given vendor actually sends
 * back cannot be pinned down here — see the caveat in the README.
 */
class ImageEditProviderTest {

    private val w = 200
    private val h = 100
    private val badge = Region(0.80f, 0.05f, 0.95f, 0.20f)

    // --- mask -------------------------------------------------------------

    @Test
    fun `transparent polarity punches a hole and keeps the rest opaque`() {
        val mask = EraseMask.build(w, h, listOf(badge), MaskPolarity.TRANSPARENT_HOLE)!!
        val inside = mask[(0.12f * h).toInt() * w + (0.87f * w).toInt()]
        val outside = mask[(0.9f * h).toInt() * w + 5]
        assertEquals("marked area must be fully transparent", 0, inside ushr 24)
        assertEquals("kept area must be fully opaque", 255, outside ushr 24)
    }

    @Test
    fun `white polarity marks the area white on black`() {
        val mask = EraseMask.build(w, h, listOf(badge), MaskPolarity.WHITE_ON_BLACK)!!
        val inside = mask[(0.12f * h).toInt() * w + (0.87f * w).toInt()]
        val outside = mask[(0.9f * h).toInt() * w + 5]
        assertEquals(0xFFFFFFFF.toInt(), inside)
        assertEquals(0xFF000000.toInt(), outside)
        assertNotEquals(inside, outside)
    }

    @Test
    fun `the marked area is grown slightly past the region`() {
        // A mask drawn exactly on a badge leaves its anti-aliased fringe behind.
        val mask = EraseMask.build(w, h, listOf(badge), MaskPolarity.WHITE_ON_BLACK)!!
        val justOutsideLeft = (0.80f * w).toInt() - 1
        val row = (0.12f * h).toInt()
        assertEquals(0xFFFFFFFF.toInt(), mask[row * w + justOutsideLeft])
    }

    @Test
    fun `several regions all end up marked`() {
        val second = Region(0.02f, 0.70f, 0.20f, 0.95f)
        val mask = EraseMask.build(w, h, listOf(badge, second), MaskPolarity.WHITE_ON_BLACK)!!
        assertEquals(0xFFFFFFFF.toInt(), mask[(0.12f * h).toInt() * w + (0.87f * w).toInt()])
        assertEquals(0xFFFFFFFF.toInt(), mask[(0.80f * h).toInt() * w + (0.10f * w).toInt()])
    }

    @Test
    fun `nothing marked means no mask at all`() {
        assertNull(EraseMask.build(w, h, emptyList(), MaskPolarity.TRANSPARENT_HOLE))
        assertNull(EraseMask.build(w, h, listOf(Region(0.5f, 0.5f, 0.5f, 0.5f)), MaskPolarity.TRANSPARENT_HOLE))
        assertNull(EraseMask.build(0, 0, listOf(badge), MaskPolarity.TRANSPARENT_HOLE))
    }

    // --- response unpacking ------------------------------------------------

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `raw image bytes come back as they are`() {
        val out = HttpImageEditProvider.decode(png, "image/png").getOrThrow()
        assertArrayEquals(png, out)
    }

    @Test
    fun `an OpenAI shaped response is unpacked`() {
        val body = """{"data":[{"b64_json":"${b64(png)}"}]}""".toByteArray()
        assertArrayEquals(png, HttpImageEditProvider.decode(body, "application/json").getOrThrow())
    }

    @Test
    fun `a Stable Diffusion shaped response is unpacked`() {
        val body = """{"images":["${b64(png)}"]}""".toByteArray()
        assertArrayEquals(png, HttpImageEditProvider.decode(body, "application/json").getOrThrow())
    }

    @Test
    fun `a data URI is unpacked`() {
        val body = """{"image":"data:image/png;base64,${b64(png)}"}""".toByteArray()
        assertArrayEquals(png, HttpImageEditProvider.decode(body, "application/json").getOrThrow())
    }

    @Test
    fun `a bare base64 string is unpacked`() {
        val body = """"${b64(png.copyOf(80))}"""".toByteArray()
        assertTrue(HttpImageEditProvider.decode(body, "application/json").isSuccess)
    }

    @Test
    fun `an empty or unusable response fails rather than producing junk`() {
        assertTrue(HttpImageEditProvider.decode(ByteArray(0), "image/png").isFailure)
        assertTrue(HttpImageEditProvider.decode("not json".toByteArray(), "text/plain").isFailure)
        assertTrue(HttpImageEditProvider.decode("""{"error":"nope"}""".toByteArray(), "application/json").isFailure)
    }
}
