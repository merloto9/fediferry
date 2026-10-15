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
package app.fediferry.drafts

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import app.fediferry.media.ScreenshotCropper
import app.fediferry.media.cleanup.CleanupRule
import app.fediferry.media.cleanup.ImageCleaner
import app.fediferry.ui.crop.NormalisedCrop
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Work on a post's picture that happens on the phone's screen, whichever
 * backend keeps the picture: it is read through the image loader, which knows
 * the server's address and token as well as the phone's own files.
 */
internal object PictureTools {

    /** Longest edge for detection and previews; the phone analyses screenshots at this size too. */
    const val WORKING_EDGE = 720

    /** The picture behind [model], no longer than [maxEdge], as pixels the phone can read. */
    suspend fun load(context: Context, model: Any, maxEdge: Int = WORKING_EDGE): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(maxEdge)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        return result.image.toBitmap()
    }

    /**
     * Where the screenshot's picture is, found as on share: any certainty,
     * since here the person sees it and decides. Null when nothing is to trim.
     */
    suspend fun suggestedCrop(bitmap: Bitmap): NormalisedCrop? = withContext(Dispatchers.Default) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val crop = ScreenshotCropper.detect(pixels, w, h)?.crop ?: return@withContext null
        if (crop.width >= w - 2 && crop.height >= h - 2) return@withContext null
        NormalisedCrop(crop.left.toFloat() / w, crop.top.toFloat() / h, crop.right.toFloat() / w, crop.bottom.toFloat() / h)
    }

    /**
     * What [rules] will do, drawn on a copy. Offline: an area for the model
     * shows as its local fill rather than being sent on every stroke.
     */
    suspend fun preview(bitmap: Bitmap, rules: List<CleanupRule>): Bitmap = withContext(Dispatchers.Default) {
        if (rules.isEmpty()) return@withContext bitmap
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val result = ImageCleaner.apply(pixels, w, h, rules)
        createBitmap(result.width, result.height).apply {
            setPixels(result.pixels, 0, result.width, 0, 0, result.width, result.height)
        }
    }
}
