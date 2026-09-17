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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Runs [ScreenshotCropper] against a stored file.
 *
 * Detection happens on a downscaled copy — the band layout it looks for is
 * unaffected by resolution, and a full 1080x2400 screenshot is a lot of pixels
 * to walk for no gain. The result is scaled back to full-resolution coordinates
 * so the crop itself stays lossless.
 */
object ScreenshotAnalyzer {

    /** Longest edge used for detection. */
    private const val ANALYSIS_MAX_EDGE = 720

    data class Suggestion(
        val crop: ScreenshotCropper.Crop,
        val confidence: Float,
        val sourceWidth: Int,
        val sourceHeight: Int,
    ) {
        /** True when the suggestion would remove a meaningful amount. */
        val trimsAnything: Boolean
            get() = crop.width < sourceWidth - 2 || crop.height < sourceHeight - 2
    }

    suspend fun suggest(path: String): Suggestion? = withContext(Dispatchers.Default) {
        val file = File(path).takeIf { it.isFile } ?: return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val fullWidth = bounds.outWidth
        val fullHeight = bounds.outHeight
        if (fullWidth <= 0 || fullHeight <= 0) return@withContext null

        val sample = sampleSizeFor(fullWidth, fullHeight)
        val small = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext null

        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val detection = ScreenshotCropper.detect(pixels, small.width, small.height)
        val scaleX = fullWidth.toFloat() / small.width
        val scaleY = fullHeight.toFloat() / small.height
        small.recycle()

        detection ?: return@withContext null

        Suggestion(
            crop = ScreenshotCropper.Crop(
                left = (detection.crop.left * scaleX).toInt().coerceIn(0, fullWidth),
                top = (detection.crop.top * scaleY).toInt().coerceIn(0, fullHeight),
                right = (detection.crop.right * scaleX).toInt().coerceIn(0, fullWidth),
                bottom = (detection.crop.bottom * scaleY).toInt().coerceIn(0, fullHeight),
            ),
            confidence = detection.confidence,
            sourceWidth = fullWidth,
            sourceHeight = fullHeight,
        )
    }

    /** Decoded size of a stored image, without decoding it. */
    suspend fun sizeOf(path: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }

    /** Applies [crop] to [path], returning the cropped bitmap for the caller to store. */
    suspend fun crop(path: String, crop: ScreenshotCropper.Crop): Bitmap? =
        withContext(Dispatchers.Default) {
            val source = BitmapFactory.decodeFile(path) ?: return@withContext null
            val left = crop.left.coerceIn(0, source.width - 1)
            val top = crop.top.coerceIn(0, source.height - 1)
            val width = crop.width.coerceIn(1, source.width - left)
            val height = crop.height.coerceIn(1, source.height - top)
            if (left == 0 && top == 0 && width == source.width && height == source.height) {
                return@withContext source
            }
            Bitmap.createBitmap(source, left, top, width, height)
                .also { if (it !== source) source.recycle() }
        }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= ANALYSIS_MAX_EDGE) {
            sample *= 2
            longest /= 2
        }
        return sample
    }
}
