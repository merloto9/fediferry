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

import app.fediferry.media.ScreenshotCropper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Synthetic feed screenshots: flat background, sparse UI bands, and one
 * full-width picture. These pin the detector's behaviour; they are not a claim
 * that it is tuned against real Instagram output, which needs real samples.
 */
class ScreenshotCropperTest {

    private val width = 360
    private val height = 800

    private fun screenshot(
        background: Int = WHITE,
        pictureTop: Int = 180,
        pictureBottom: Int = 540,
        pictureLeft: Int = 0,
        pictureRight: Int = 360,
        uiInkEvery: Int = 9,
        picture: (x: Int, y: Int) -> Int = { x, y -> rgb(x * 7 % 256, y * 3 % 256, 90) },
    ): IntArray {
        val px = IntArray(width * height) { background }
        // Sparse text/icons on the UI bands, as a real header and caption have.
        for (y in 0 until height) {
            if (y in pictureTop until pictureBottom) continue
            for (x in 0 until width step uiInkEvery) {
                if ((y / 7 + x / 11) % 3 == 0) px[y * width + x] = DARK
            }
        }
        for (y in pictureTop until pictureBottom) {
            for (x in pictureLeft until pictureRight) {
                px[y * width + x] = picture(x, y)
            }
        }
        return px
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val WHITE = rgb(255, 255, 255)
    private val DARK = rgb(20, 20, 20)
    private val BLACK = rgb(0, 0, 0)

    @Test
    fun `finds a full-width picture band on a light background`() {
        val d = ScreenshotCropper.detect(screenshot(), width, height)
        assertNotNull(d)
        d!!
        assertTrue("top ${d.crop.top}", abs(d.crop.top - 180) <= 3)
        assertTrue("bottom ${d.crop.bottom}", abs(d.crop.bottom - 540) <= 3)
        assertEquals(0, d.crop.left)
        assertEquals(width, d.crop.right)
    }

    @Test
    fun `finds the picture on a dark background too`() {
        val px = screenshot(background = BLACK)
        val d = ScreenshotCropper.detect(px, width, height)
        assertNotNull(d)
        assertTrue(abs(d!!.crop.top - 180) <= 3)
    }

    @Test
    fun `reports the taller band when the screen holds two`() {
        val px = screenshot(pictureTop = 100, pictureBottom = 200)
        // A second, taller picture lower down.
        for (y in 400 until 700) for (x in 0 until width) {
            px[y * width + x] = rgb(x % 256, 40, y % 256)
        }
        val d = ScreenshotCropper.detect(px, width, height)
        assertNotNull(d)
        assertTrue("top ${d!!.crop.top}", abs(d.crop.top - 400) <= 3)
        assertTrue("bottom ${d.crop.bottom}", abs(d.crop.bottom - 700) <= 3)
    }

    @Test
    fun `respects a picture inset from the screen edges`() {
        val d = ScreenshotCropper.detect(
            screenshot(pictureLeft = 40, pictureRight = 320),
            width,
            height,
        )
        assertNotNull(d)
        assertTrue("left ${d!!.crop.left}", abs(d.crop.left - 40) <= 2)
        assertTrue("right ${d.crop.right}", abs(d.crop.right - 320) <= 2)
    }

    @Test
    fun `declines an image that is already cropped`() {
        // Edge to edge picture, no UI bands: nothing to cut away.
        val px = IntArray(width * height) { rgb(it % 256, (it / 3) % 256, 70) }
        val d = ScreenshotCropper.detect(px, width, height)
        // Either no suggestion, or one that covers essentially the whole image.
        if (d != null) {
            assertTrue("height ${d.crop.height}", d.crop.height >= height - 4)
        }
    }

    @Test
    fun `declines a blank image`() {
        assertNull(ScreenshotCropper.detect(IntArray(width * height) { WHITE }, width, height))
    }

    @Test
    fun `declines a band too short to be a picture`() {
        assertNull(ScreenshotCropper.detect(screenshot(pictureTop = 300, pictureBottom = 340), width, height))
    }

    @Test
    fun `is more confident when the picture is cleanly separated`() {
        val clean = ScreenshotCropper.detect(screenshot(uiInkEvery = 30), width, height)!!
        val noisy = ScreenshotCropper.detect(screenshot(uiInkEvery = 3), width, height)!!
        assertTrue(
            "clean ${clean.confidence} noisy ${noisy.confidence}",
            clean.confidence >= noisy.confidence,
        )
    }

    @Test
    fun `survives a noisy photograph`() {
        val rng = Random(7)
        val d = ScreenshotCropper.detect(
            screenshot(picture = { _, _ -> rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)) }),
            width,
            height,
        )
        assertNotNull(d)
        assertTrue(abs(d!!.crop.top - 180) <= 3)
    }

    @Test
    fun `rejects input that is too small or malformed`() {
        assertNull(ScreenshotCropper.detect(IntArray(4), 2, 2))
        assertNull(ScreenshotCropper.detect(IntArray(10), width, height))
    }
}
