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
import java.io.ByteArrayOutputStream

/**
 * Runs a cleanup in two passes, because one of the treatments is a network call
 * and the rest are arithmetic.
 *
 * [TreatmentKind.AI_ERASE] regions go first, in a single request carrying the
 * whole picture and one mask — a model asked to paint out three badges at once
 * sees the context around all of them, and it is one call rather than three.
 * The local treatments then run over whatever came back.
 *
 * If the model is unconfigured, unreachable or unhappy, those regions are not
 * abandoned: they fall through to [TreatmentKind.FILL], which is what they
 * would have been without a model at all.
 */
object CleanupPipeline {

    /** Told to the model when the user has not written anything more specific. */
    const val DEFAULT_INSTRUCTION =
        "Remove the marked overlay and continue the surrounding image naturally. " +
            "Do not add anything new."

    data class Outcome(val bitmap: Bitmap, val aiUsed: Boolean, val aiFailure: String?)

    suspend fun run(
        source: Bitmap,
        rules: List<CleanupRule>,
        provider: ImageEditProvider,
        polarity: MaskPolarity,
        instruction: String,
    ): Outcome = withContext(Dispatchers.Default) {
        val aiRules = rules.filter { it.treatment == TreatmentKind.AI_ERASE }
        if (aiRules.isEmpty()) {
            return@withContext Outcome(apply(source, rules), aiUsed = false, aiFailure = null)
        }

        val edited = eraseWithModel(source, aiRules, provider, polarity, instruction)

        edited.fold(
            onSuccess = { bitmap ->
                // The model already handled those regions; do not fill them too.
                val remaining = rules.filterNot { it.treatment == TreatmentKind.AI_ERASE }
                val out = apply(bitmap, remaining)
                if (out !== bitmap) bitmap.recycle()
                Outcome(out, aiUsed = true, aiFailure = null)
            },
            onFailure = { error ->
                // ImageCleaner fills anything still marked AI_ERASE.
                Outcome(apply(source, rules), aiUsed = false, aiFailure = error.message)
            },
        )
    }

    private suspend fun eraseWithModel(
        source: Bitmap,
        aiRules: List<CleanupRule>,
        provider: ImageEditProvider,
        polarity: MaskPolarity,
        instruction: String,
    ): Result<Bitmap> {
        val maskPixels = EraseMask.build(
            source.width,
            source.height,
            aiRules.map { it.region },
            polarity,
        ) ?: return Result.failure(IllegalStateException("nothing marked to erase"))

        val mask = createBitmap(source.width, source.height)
        mask.setPixels(maskPixels, 0, source.width, 0, 0, source.width, source.height)

        val request = EraseRequest(
            image = source.toPng(),
            mimeType = "image/png",
            mask = mask.toPng(),
            instruction = instruction.ifBlank { DEFAULT_INSTRUCTION },
        )
        mask.recycle()

        return provider.erase(request).mapCatching { bytes ->
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("the model returned something that is not an image")
            check(decoded.width == source.width && decoded.height == source.height) {
                // A different size would silently invalidate every other rule's
                // coordinates, which are fractions of the picture.
                "the model returned a ${decoded.width}x${decoded.height} image, " +
                    "expected ${source.width}x${source.height}"
            }
            decoded
        }
    }

    private fun apply(source: Bitmap, rules: List<CleanupRule>): Bitmap {
        if (rules.isEmpty()) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val result = ImageCleaner.apply(pixels, width, height, rules)
        val out = createBitmap(result.width, result.height)
        out.setPixels(result.pixels, 0, result.width, 0, 0, result.width, result.height)
        return out
    }

    private fun Bitmap.toPng(): ByteArray = ByteArrayOutputStream().use { buffer ->
        compress(Bitmap.CompressFormat.PNG, 100, buffer)
        buffer.toByteArray()
    }
}
