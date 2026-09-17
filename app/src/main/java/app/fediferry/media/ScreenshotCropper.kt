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
package app.fediferry.media

import kotlin.math.abs
import kotlin.math.min

/**
 * Finds the post image inside a full-screen screenshot of a feed.
 *
 * A feed screenshot is a stack of horizontal bands: status bar, app header,
 * the picture, an action row, the caption, the navigation bar. Everything that
 * is not the picture is mostly flat app background with sparse text and icons
 * on it, while the picture covers its rows edge to edge. So the picture is the
 * tallest run of rows whose pixels are largely *not* the background colour.
 *
 * Deliberately conservative: it reports a confidence and returns null rather
 * than guessing, because the crop it proposes is always shown to the user for
 * approval. A wrong suggestion the user has to undo is worse than none.
 *
 * Works on a raw ARGB array so it can be tested without a device.
 */
object ScreenshotCropper {

    data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class Detection(val crop: Crop, val confidence: Float)

    /** Colour distance beyond which a pixel counts as "not the background". */
    private const val COLOUR_TOLERANCE = 26

    /** A row is part of the picture when this much of it is non-background. */
    private const val ROW_COVERED = 0.62f

    /** A row is outside the picture when it is this sparse. */
    private const val ROW_EMPTY = 0.34f

    /** Anything shorter than this fraction of the screen is not the picture. */
    private const val MIN_BAND_FRACTION = 0.14f

    /**
     * @return the detected picture bounds, or null when nothing stands out
     *   clearly enough to be worth proposing.
     */
    fun detect(pixels: IntArray, width: Int, height: Int): Detection? {
        if (width < 16 || height < 16 || pixels.size < width * height) return null

        val background = estimateBackground(pixels, width, height) ?: return null
        val coverage = FloatArray(height) { y -> rowCoverage(pixels, width, y, background) }

        val band = longestRun(coverage, height) ?: return null
        val (top, bottom) = band
        if ((bottom - top) < height * MIN_BAND_FRACTION) return null

        val (left, right) = horizontalBounds(pixels, width, top, bottom, background)
        if (right - left < width / 4) return null

        return Detection(
            crop = Crop(left, top, left + (right - left), bottom),
            confidence = confidenceOf(coverage, top, bottom, height),
        )
    }

    /**
     * The app background, taken from the top and bottom slices of the screen —
     * status bar, header, caption and navigation bar all sit on it, and the
     * picture does not reach there.
     */
    private fun estimateBackground(pixels: IntArray, width: Int, height: Int): Int? {
        val margin = (height * 0.06f).toInt().coerceAtLeast(2)
        val counts = HashMap<Int, Int>()

        fun tally(yFrom: Int, yTo: Int) {
            for (y in yFrom until yTo) {
                var x = 0
                while (x < width) {
                    counts.merge(quantise(pixels[y * width + x]), 1, Int::plus)
                    x += 3
                }
            }
        }
        tally(0, min(margin, height))
        tally((height - margin).coerceAtLeast(0), height)

        return counts.maxByOrNull { it.value }?.key
    }

    private fun rowCoverage(pixels: IntArray, width: Int, y: Int, background: Int): Float {
        var covered = 0
        var sampled = 0
        var x = 0
        while (x < width) {
            if (!matches(pixels[y * width + x], background)) covered++
            sampled++
            x += 2
        }
        return if (sampled == 0) 0f else covered.toFloat() / sampled
    }

    /**
     * The tallest run of covered rows. Rows between [ROW_EMPTY] and
     * [ROW_COVERED] are ambiguous and neither start nor end a run, which stops a
     * single busy caption line from splitting the picture in two.
     */
    private fun longestRun(coverage: FloatArray, height: Int): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestLength = 0
        var start = -1

        for (y in 0 until height) {
            val c = coverage[y]
            if (c >= ROW_COVERED) {
                if (start < 0) start = y
            } else if (c < ROW_EMPTY && start >= 0) {
                val length = y - start
                if (length > bestLength) {
                    bestLength = length
                    best = start to y
                }
                start = -1
            }
        }
        if (start >= 0 && height - start > bestLength) best = start to height
        return best
    }

    /** Columns the picture actually spans; normally the full width. */
    private fun horizontalBounds(
        pixels: IntArray,
        width: Int,
        top: Int,
        bottom: Int,
        background: Int,
    ): Pair<Int, Int> {
        val rows = (bottom - top).coerceAtLeast(1)
        val step = (rows / 24).coerceAtLeast(1)

        fun columnCovered(x: Int): Boolean {
            var covered = 0
            var sampled = 0
            var y = top
            while (y < bottom) {
                if (!matches(pixels[y * width + x], background)) covered++
                sampled++
                y += step
            }
            return sampled > 0 && covered.toFloat() / sampled >= ROW_COVERED
        }

        var left = 0
        while (left < width && !columnCovered(left)) left++
        var right = width
        while (right > left && !columnCovered(right - 1)) right--
        return left to right
    }

    /**
     * How cleanly the band is separated from what surrounds it. A picture with
     * flat app background immediately above and below scores high; a band that
     * fades into its neighbours scores low and the user is more likely to be
     * shown the whole image instead.
     */
    private fun confidenceOf(coverage: FloatArray, top: Int, bottom: Int, height: Int): Float {
        val above = coverage.getOrNull(top - 2) ?: 0f
        val below = coverage.getOrNull(bottom + 1) ?: 0f
        val edges = ((1f - above) + (1f - below)) / 2f
        val size = ((bottom - top).toFloat() / height).coerceAtMost(0.9f) / 0.9f
        return (edges * 0.75f + size * 0.25f).coerceIn(0f, 1f)
    }

    private fun matches(pixel: Int, background: Int): Boolean {
        val p = quantise(pixel)
        if (p == background) return true
        return abs(((p shr 16) and 0xFF) - ((background shr 16) and 0xFF)) <= 1 &&
            abs(((p shr 8) and 0xFF) - ((background shr 8) and 0xFF)) <= 1 &&
            abs((p and 0xFF) - (background and 0xFF)) <= 1
    }

    /** Buckets colour so JPEG noise and gradients in flat UI do not count. */
    private fun quantise(pixel: Int): Int {
        val r = ((pixel shr 16) and 0xFF) / COLOUR_TOLERANCE
        val g = ((pixel shr 8) and 0xFF) / COLOUR_TOLERANCE
        val b = (pixel and 0xFF) / COLOUR_TOLERANCE
        return (r shl 16) or (g shl 8) or b
    }
}
