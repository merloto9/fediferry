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

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What to do with a marked region.
 *
 * The treatment travels with the region rather than with the profile, because
 * the *kind* of thing being removed varies by source as much as its position
 * does: a translucent badge over a photo, a logo bar along an edge and a
 * username you would rather not publish each want a different answer.
 *
 * Adding a kind here is the only code change a new kind of artefact needs;
 * a new *source* needs no code at all, just rules.
 */
enum class TreatmentKind {
    /** Paint the region over from the pixels around it. Invisible on flat areas. */
    FILL,

    /** Shave the region off the nearest edge. Costs content, never leaves a smear. */
    CROP_AWAY,

    /** Blur the region. For a handle or a face, where "obviously hidden" is the point. */
    BLUR,

    /** Coarse blocks. Same purpose as blur, harder to reverse. */
    PIXELATE,
}

/** A rectangle in fractions of the image, so one rule fits every screen size. */
data class Region(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun isSane(): Boolean =
        left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f &&
            width > 0.0005f && height > 0.0005f
}

data class CleanupRule(val region: Region, val treatment: TreatmentKind)

/**
 * Applies cleanup rules to raw pixels.
 *
 * Works on an ARGB array rather than a Bitmap so every operation is testable on
 * the host. [CROP_AWAY] changes the image bounds, so the result carries its own
 * dimensions.
 */
object ImageCleaner {

    data class Result(val pixels: IntArray, val width: Int, val height: Int) {
        override fun equals(other: Any?): Boolean =
            other is Result && width == other.width && height == other.height &&
                pixels.contentEquals(other.pixels)

        override fun hashCode(): Int =
            (width * 31 + height) * 31 + pixels.contentHashCode()
    }

    fun apply(pixels: IntArray, width: Int, height: Int, rules: List<CleanupRule>): Result {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return Result(pixels, width, height)
        }
        val usable = rules.filter { it.region.isSane() }
        if (usable.isEmpty()) return Result(pixels.copyOf(), width, height)

        val out = pixels.copyOf()

        // In-place treatments first: cropping afterwards would otherwise shift
        // every other rule's coordinates out from under it.
        for (rule in usable) {
            val r = rule.region.toPixels(width, height) ?: continue
            when (rule.treatment) {
                TreatmentKind.FILL -> fill(out, width, height, r)
                TreatmentKind.BLUR -> blur(out, width, height, r)
                TreatmentKind.PIXELATE -> pixelate(out, width, r)
                TreatmentKind.CROP_AWAY -> Unit
            }
        }

        val keep = cropBounds(usable, width, height)
        return if (keep == null) {
            Result(out, width, height)
        } else {
            crop(out, width, keep)
        }
    }

    // --- treatments -------------------------------------------------------

    /**
     * Paints the region from its own edges: every pixel becomes a blend of the
     * pixels level with it on each side. Exact on a flat background, plausible
     * on a gradient, a smear over detail — which is why it is one option and
     * not the only one.
     */
    private fun fill(px: IntArray, width: Int, height: Int, r: Rect) {
        val left = (r.left - 1).takeIf { it >= 0 }
        val right = r.right.takeIf { it < width }
        val top = (r.top - 1).takeIf { it >= 0 }
        val bottom = r.bottom.takeIf { it < height }

        for (y in r.top until r.bottom) {
            val tx = if (r.width > 1) 1f / (r.width - 1) else 0f
            for (x in r.left until r.right) {
                val horizontal = blendAcross(
                    px.getOrNull(left?.let { y * width + it }),
                    px.getOrNull(right?.let { y * width + it }),
                    (x - r.left) * tx,
                )
                val ty = if (r.height > 1) 1f / (r.height - 1) else 0f
                val vertical = blendAcross(
                    px.getOrNull(top?.let { it * width + x }),
                    px.getOrNull(bottom?.let { it * width + x }),
                    (y - r.top) * ty,
                )
                px[y * width + x] = when {
                    horizontal != null && vertical != null -> mix(horizontal, vertical, 0.5f)
                    horizontal != null -> horizontal
                    vertical != null -> vertical
                    else -> px[y * width + x]
                }
            }
        }
    }

    /**
     * Box blur, several passes, sampling from outside the region so the result
     * blends into its surroundings instead of smearing only itself.
     */
    private fun blur(px: IntArray, width: Int, height: Int, r: Rect) {
        val radius = max(2, min(r.width, r.height) / 6)
        repeat(3) {
            val snapshot = px.copyOf()
            for (y in r.top until r.bottom) {
                for (x in r.left until r.right) {
                    var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                    for (dy in -radius..radius) {
                        val sy = (y + dy).coerceIn(0, height - 1)
                        for (dx in -radius..radius) {
                            val sx = (x + dx).coerceIn(0, width - 1)
                            val p = snapshot[sy * width + sx]
                            a += (p ushr 24) and 0xFF
                            rr += (p shr 16) and 0xFF
                            g += (p shr 8) and 0xFF
                            b += p and 0xFF
                            n++
                        }
                    }
                    px[y * width + x] = argb(a / n, rr / n, g / n, b / n)
                }
            }
        }
    }

    /** Average of each block, written back across the block. */
    private fun pixelate(px: IntArray, width: Int, r: Rect) {
        val block = max(4, min(r.width, r.height) / 6)
        var by = r.top
        while (by < r.bottom) {
            var bx = r.left
            while (bx < r.right) {
                val yEnd = min(by + block, r.bottom)
                val xEnd = min(bx + block, r.right)
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (y in by until yEnd) for (x in bx until xEnd) {
                    val p = px[y * width + x]
                    a += (p ushr 24) and 0xFF
                    rr += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
                if (n > 0) {
                    val avg = argb(a / n, rr / n, g / n, b / n)
                    for (y in by until yEnd) for (x in bx until xEnd) px[y * width + x] = avg
                }
                bx += block
            }
            by += block
        }
    }

    // --- cropping ---------------------------------------------------------

    /**
     * The area left after every [CROP_AWAY] rule has shaved its nearest edge.
     * Null when no rule asks for a crop.
     */
    private fun cropBounds(rules: List<CleanupRule>, width: Int, height: Int): Rect? {
        var keep = Rect(0, 0, width, height)
        var cropped = false

        for (rule in rules.filter { it.treatment == TreatmentKind.CROP_AWAY }) {
            val r = rule.region.toPixels(width, height) ?: continue
            cropped = true
            // Shave whichever edge costs the least image. Distance-to-edge is
            // the obvious rule and the wrong one: a full-width strip along the
            // bottom touches the left, right *and* bottom edges at distance
            // zero, and shaving the left would take the entire picture.
            val cost = listOf(
                Edge.LEFT to r.right.toLong() * height,
                Edge.TOP to width.toLong() * r.bottom,
                Edge.RIGHT to (width - r.left).toLong() * height,
                Edge.BOTTOM to width.toLong() * (height - r.top),
            )
            keep = when (cost.minBy { it.second }.first) {
                Edge.LEFT -> keep.copy(left = max(keep.left, r.right))
                Edge.TOP -> keep.copy(top = max(keep.top, r.bottom))
                Edge.RIGHT -> keep.copy(right = min(keep.right, r.left))
                Edge.BOTTOM -> keep.copy(bottom = min(keep.bottom, r.top))
            }
        }
        if (!cropped) return null
        // Never crop away everything, however the rules were drawn.
        if (keep.width < 8 || keep.height < 8) return null
        return keep
    }

    private fun crop(px: IntArray, width: Int, keep: Rect): Result {
        val out = IntArray(keep.width * keep.height)
        for (y in 0 until keep.height) {
            System.arraycopy(px, (keep.top + y) * width + keep.left, out, y * keep.width, keep.width)
        }
        return Result(out, keep.width, keep.height)
    }

    private enum class Edge { LEFT, TOP, RIGHT, BOTTOM }

    // --- plumbing ---------------------------------------------------------

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private fun Region.toPixels(width: Int, height: Int): Rect? {
        val r = Rect(
            left = (left * width).roundToInt().coerceIn(0, width - 1),
            top = (top * height).roundToInt().coerceIn(0, height - 1),
            right = (right * width).roundToInt().coerceIn(1, width),
            bottom = (bottom * height).roundToInt().coerceIn(1, height),
        )
        return r.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun IntArray.getOrNull(index: Int?): Int? =
        if (index != null && index in indices) this[index] else null

    private fun blendAcross(a: Int?, b: Int?, t: Float): Int? = when {
        a != null && b != null -> mix(a, b, t.coerceIn(0f, 1f))
        a != null -> a
        b != null -> b
        else -> null
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val inv = 1f - t
        return argb(
            (((a ushr 24) and 0xFF) * inv + ((b ushr 24) and 0xFF) * t).roundToInt(),
            (((a shr 16) and 0xFF) * inv + ((b shr 16) and 0xFF) * t).roundToInt(),
            (((a shr 8) and 0xFF) * inv + ((b shr 8) and 0xFF) * t).roundToInt(),
            ((a and 0xFF) * inv + (b and 0xFF) * t).roundToInt(),
        )
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
