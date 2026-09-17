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
package app.fediferry.media.cleanup

/**
 * Builds the mask that tells a model which pixels to replace.
 *
 * Pure, so the awkward parts — polarity, clamping, feathering the edge — are
 * tested on the host rather than against a paid endpoint.
 */
object EraseMask {

    /**
     * How far the marked area is grown, as a fraction of the image's smaller
     * side. A mask drawn exactly on a badge tends to leave its anti-aliased
     * fringe behind, which the model then treats as something to preserve.
     */
    private const val GROW = 0.004f

    /**
     * @return ARGB pixels for a mask of [width] x [height] in the given
     *   [polarity], or null when nothing is marked.
     */
    fun build(
        width: Int,
        height: Int,
        regions: List<Region>,
        polarity: MaskPolarity,
    ): IntArray? {
        if (width <= 0 || height <= 0) return null
        val usable = regions.filter { it.isSane() }
        if (usable.isEmpty()) return null

        val keep = if (polarity == MaskPolarity.TRANSPARENT_HOLE) OPAQUE_BLACK else BLACK
        val replace = if (polarity == MaskPolarity.TRANSPARENT_HOLE) TRANSPARENT else WHITE

        val pixels = IntArray(width * height) { keep }
        val grow = (minOf(width, height) * GROW).toInt().coerceAtLeast(1)

        for (region in usable) {
            val left = ((region.left * width).toInt() - grow).coerceIn(0, width - 1)
            val right = ((region.right * width).toInt() + grow).coerceIn(1, width)
            val top = ((region.top * height).toInt() - grow).coerceIn(0, height - 1)
            val bottom = ((region.bottom * height).toInt() + grow).coerceIn(1, height)
            for (y in top until bottom) {
                java.util.Arrays.fill(pixels, y * width + left, y * width + right, replace)
            }
        }
        return pixels
    }

    private const val TRANSPARENT = 0x00000000
    private const val OPAQUE_BLACK = -0x1000000 // 0xFF000000
    private const val BLACK = -0x1000000
    private const val WHITE = -0x1 // 0xFFFFFFFF
}
