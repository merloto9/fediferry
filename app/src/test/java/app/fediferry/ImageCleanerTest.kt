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

import app.fediferry.media.cleanup.CleanupRule
import app.fediferry.media.cleanup.ImageCleaner
import app.fediferry.media.cleanup.Region
import app.fediferry.media.cleanup.TreatmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImageCleanerTest {

    private val w = 120
    private val h = 200

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val BG = rgb(240, 240, 240)

    private fun flat(colour: Int = BG) = IntArray(w * h) { colour }

    private fun noisy(seed: Int = 3): IntArray {
        val rng = Random(seed)
        return IntArray(w * h) { rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)) }
    }

    private fun IntArray.at(x: Int, y: Int, width: Int = w) = this[y * width + x]

    private fun badge(treatment: TreatmentKind) = CleanupRule(
        // a small badge in the top-right, like a carousel indicator
        Region(left = 0.80f, top = 0.02f, right = 0.97f, bottom = 0.08f),
        treatment,
    )

    // --- fill -------------------------------------------------------------

    @Test
    fun `fill over a flat background is invisible`() {
        val px = flat()
        // A badge strictly inside the region. Anything the region fails to
        // cover is sampled by the fill and smeared inward, which is a real
        // property worth not hiding behind a lenient test.
        for (y in 5..14) for (x in 97..114) px[y * w + x] = rgb(0, 0, 0)

        val out = ImageCleaner.apply(px, w, h, listOf(badge(TreatmentKind.FILL)))
        assertEquals(w, out.width)
        assertEquals(h, out.height)
        for (y in 5..14) for (x in 97..114) {
            assertEquals("pixel ($x,$y) not restored", BG, out.pixels.at(x, y))
        }
    }

    @Test
    fun `fill leaves everything outside the region alone`() {
        val px = noisy()
        val out = ImageCleaner.apply(px, w, h, listOf(badge(TreatmentKind.FILL)))
        for (y in 0 until h) for (x in 0 until w) {
            val inside = x >= (0.80f * w) && x < (0.97f * w) && y >= (0.02f * h) && y < (0.08f * h)
            if (!inside) assertEquals("($x,$y) changed", px.at(x, y), out.pixels.at(x, y))
        }
    }

    // --- blur / pixelate --------------------------------------------------

    @Test
    fun `blur flattens the region without resizing`() {
        val px = noisy()
        val out = ImageCleaner.apply(px, w, h, listOf(badge(TreatmentKind.BLUR)))
        assertEquals(w, out.width)
        assertEquals(h, out.height)
        assertTrue("blur should reduce variance", variance(out.pixels) < variance(px))
    }

    @Test
    fun `pixelate makes uniform blocks`() {
        val px = noisy()
        val region = Region(0.1f, 0.1f, 0.9f, 0.5f)
        val out = ImageCleaner.apply(px, w, h, listOf(CleanupRule(region, TreatmentKind.PIXELATE)))
        // neighbouring pixels well inside a block must now match
        val x = (0.3f * w).toInt()
        val y = (0.3f * h).toInt()
        assertEquals(out.pixels.at(x, y), out.pixels.at(x + 1, y))
        assertNotEquals(px.at(x, y), out.pixels.at(x, y))
    }

    // --- crop away --------------------------------------------------------

    @Test
    fun `crop away shaves the nearest edge`() {
        val px = flat()
        val rule = CleanupRule(Region(0.0f, 0.94f, 1.0f, 1.0f), TreatmentKind.CROP_AWAY)
        val out = ImageCleaner.apply(px, w, h, listOf(rule))
        assertEquals(w, out.width)
        assertEquals("should have lost the bottom strip", (0.94f * h).toInt(), out.height)
    }

    @Test
    fun `crop away picks the top when the region sits there`() {
        val out = ImageCleaner.apply(
            flat(), w, h,
            listOf(CleanupRule(Region(0f, 0f, 1f, 0.1f), TreatmentKind.CROP_AWAY)),
        )
        assertEquals(w, out.width)
        assertEquals(h - (0.1f * h).toInt(), out.height)
    }

    @Test
    fun `crop away never removes the whole image`() {
        val out = ImageCleaner.apply(
            flat(), w, h,
            listOf(CleanupRule(Region(0f, 0f, 1f, 1f), TreatmentKind.CROP_AWAY)),
        )
        assertEquals(w, out.width)
        assertEquals(h, out.height)
    }

    // --- combinations and edges -------------------------------------------

    @Test
    fun `in-place treatments run before a crop shifts the coordinates`() {
        val px = flat()
        for (y in 5..14) for (x in 97..114) px[y * w + x] = rgb(0, 0, 0)

        val out = ImageCleaner.apply(
            px, w, h,
            listOf(
                badge(TreatmentKind.FILL),
                CleanupRule(Region(0f, 0.94f, 1f, 1f), TreatmentKind.CROP_AWAY),
            ),
        )
        assertEquals((0.94f * h).toInt(), out.height)
        // the badge was still filled, at its original coordinates
        assertEquals(BG, out.pixels.at(100, 8, out.width))
    }

    @Test
    fun `no rules leaves the image untouched`() {
        val px = noisy()
        val out = ImageCleaner.apply(px, w, h, emptyList())
        assertTrue(px.contentEquals(out.pixels))
    }

    @Test
    fun `nonsense regions are ignored rather than throwing`() {
        val px = noisy()
        val out = ImageCleaner.apply(
            px, w, h,
            listOf(
                CleanupRule(Region(0.5f, 0.5f, 0.5f, 0.5f), TreatmentKind.FILL),
                CleanupRule(Region(-1f, 0f, 2f, 1f), TreatmentKind.BLUR),
                CleanupRule(Region(0.9f, 0.9f, 0.1f, 0.1f), TreatmentKind.PIXELATE),
            ),
        )
        assertTrue(px.contentEquals(out.pixels))
    }

    @Test
    fun `malformed input is returned as-is`() {
        val out = ImageCleaner.apply(IntArray(4), 100, 100, listOf(badge(TreatmentKind.FILL)))
        assertEquals(100, out.width)
    }

    private fun variance(px: IntArray): Double {
        val values = px.map { ((it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF)) / 3.0 }
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }
}
