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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Runs [ImageCleaner] against a stored file or an in-memory bitmap. */
object BitmapCleaner {

    /** Longest edge for the live preview. Full resolution is wasted on a phone screen. */
    private const val PREVIEW_MAX_EDGE = 900

    suspend fun clean(path: String, rules: List<CleanupRule>): Bitmap? =
        withContext(Dispatchers.Default) {
            if (rules.isEmpty()) return@withContext null
            val source = BitmapFactory.decodeFile(path) ?: return@withContext null
            val cleaned = clean(source, rules)
            if (cleaned !== source) source.recycle()
            cleaned
        }

    /** A downscaled render, for showing the user what the rules will do. */
    suspend fun preview(path: String, rules: List<CleanupRule>): Bitmap? =
        withContext(Dispatchers.Default) {
            val file = File(path).takeIf { it.isFile } ?: return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return@withContext null

            var sample = 1
            var longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / 2 >= PREVIEW_MAX_EDGE) {
                sample *= 2
                longest /= 2
            }

            val small = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@withContext null

            if (rules.isEmpty()) return@withContext small
            val cleaned = clean(small, rules)
            if (cleaned !== small) small.recycle()
            cleaned
        }

    private fun clean(source: Bitmap, rules: List<CleanupRule>): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val result = ImageCleaner.apply(pixels, width, height, rules)
        val out = createBitmap(result.width, result.height)
        out.setPixels(result.pixels, 0, result.width, 0, 0, result.width, result.height)
        return out
    }
}
