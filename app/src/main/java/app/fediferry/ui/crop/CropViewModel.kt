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
package app.fediferry.ui.crop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.data.model.Item
import app.fediferry.di.ServiceLocator
import app.fediferry.media.ScreenshotAnalyzer
import app.fediferry.media.ScreenshotCropper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The crop rectangle in fractions of the image, so it survives any scaling. */
data class NormalisedCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    fun toPixels(width: Int, height: Int) = ScreenshotCropper.Crop(
        left = (left * width).toInt().coerceIn(0, width - 1),
        top = (top * height).toInt().coerceIn(0, height - 1),
        right = (right * width).toInt().coerceIn(1, width),
        bottom = (bottom * height).toInt().coerceIn(1, height),
    )

    val isWholeImage: Boolean
        get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f
}

data class CropState(
    val item: Item? = null,
    val sourcePath: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val crop: NormalisedCrop = NormalisedCrop(),
    val suggestion: NormalisedCrop? = null,
    val loaded: Boolean = false,
    val applying: Boolean = false,
    val message: String? = null,
) {
    /** Derived from the file's real dimensions, never from what Coil decoded. */
    val imageAspect: Float
        get() = if (imageWidth > 0 && imageHeight > 0) {
            imageWidth.toFloat() / imageHeight
        } else {
            0f
        }
}

class CropViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.items(app)

    private val _state = MutableStateFlow(CropState())
    val state: StateFlow<CropState> = _state.asStateFlow()

    fun load(itemId: String) = viewModelScope.launch {
        val item = repo.byId(itemId)
        if (item == null) {
            _state.update { it.copy(loaded = true) }
            return@launch
        }
        // Always work from the untouched screenshot, so re-cropping does not
        // cut into an earlier crop.
        val source = item.originalMediaPath ?: item.mediaPath
        // The file's true resolution, and the only thing the crop is ever
        // measured against. Coil hands back a bitmap scaled to the view, so
        // nothing from the UI layer is allowed to define this.
        val size = source?.let { ScreenshotAnalyzer.sizeOf(it) }
        val suggestion = repo.suggestCrop(item)

        val normalised = suggestion?.let {
            NormalisedCrop(
                left = it.crop.left.toFloat() / it.sourceWidth,
                top = it.crop.top.toFloat() / it.sourceHeight,
                right = it.crop.right.toFloat() / it.sourceWidth,
                bottom = it.crop.bottom.toFloat() / it.sourceHeight,
            )
        }

        _state.update {
            it.copy(
                item = item,
                sourcePath = source,
                imageWidth = size?.first ?: suggestion?.sourceWidth ?: 0,
                imageHeight = size?.second ?: suggestion?.sourceHeight ?: 0,
                crop = normalised ?: NormalisedCrop(),
                suggestion = normalised,
                loaded = true,
            )
        }
    }

    fun setCrop(crop: NormalisedCrop) = _state.update { it.copy(crop = crop) }

    fun useSuggestion() = _state.update { it.copy(crop = it.suggestion ?: NormalisedCrop()) }

    fun useWholeImage() = _state.update { it.copy(crop = NormalisedCrop()) }

    /** Writes the crop and hands back the item id, or skips when nothing changed. */
    fun apply(onDone: (String) -> Unit) = viewModelScope.launch {
        val s = _state.value
        val item = s.item ?: return@launch
        if (s.imageWidth <= 0 || s.imageHeight <= 0) {
            _state.update { it.copy(message = "Could not read the image size") }
            return@launch
        }
        if (s.crop.isWholeImage) {
            // Nothing to cut: leave the item exactly as it is.
            onDone(item.id)
            return@launch
        }
        _state.update { it.copy(applying = true) }
        repo.applyCrop(item, s.crop.toPixels(s.imageWidth, s.imageHeight)).fold(
            onSuccess = { onDone(it.id) },
            onFailure = { e ->
                _state.update { it.copy(applying = false, message = "Could not crop: ${e.message}") }
            },
        )
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
