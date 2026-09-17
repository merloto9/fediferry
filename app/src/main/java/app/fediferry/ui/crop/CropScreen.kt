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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import java.io.File

/**
 * Approve or adjust the crop before the post is made.
 *
 * The suggestion comes from [app.fediferry.media.ScreenshotCropper]; this screen
 * exists because that suggestion is a heuristic and the user is the one who
 * knows where the meme actually ends.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    itemId: String,
    onDone: (String) -> Unit,
    viewModel: CropViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(itemId) { viewModel.load(itemId) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Trim the screenshot") },
                actions = {
                    TextButton(onClick = { onDone(itemId) }) { Text("Skip") }
                },
            )
        },
    ) { padding ->
        val path = state.sourcePath
        if (path == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loaded) Text("No image to trim") else CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = if (state.suggestion != null) {
                    "Drag the corners if this missed. The original is kept, so this can be undone."
                } else {
                    "Nothing obvious to trim — drag the corners to crop by hand."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            CropCanvas(
                path = path,
                crop = state.crop,
                onCropChange = viewModel::setCrop,
                aspect = state.imageAspect,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.suggestion != null) {
                    TextButton(onClick = viewModel::useSuggestion) { Text("Suggested") }
                }
                TextButton(onClick = viewModel::useWholeImage) { Text("Whole image") }
                Button(
                    onClick = { viewModel.apply(onDone) },
                    enabled = !state.applying,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.applying) "Trimming…" else "Use this")
                }
            }
        }
    }
}

/** How close a touch must be to a corner to grab it, in pixels. */
private const val HANDLE_GRAB_RADIUS = 64f

/** The crop can never be shrunk below this fraction of the image. */
private const val MIN_CROP_FRACTION = 0.05f

@Composable
private fun CropCanvas(
    path: String,
    crop: NormalisedCrop,
    onCropChange: (NormalisedCrop) -> Unit,
    aspect: Float,
    modifier: Modifier = Modifier,
) {
    var container by remember { mutableStateOf(Size.Zero) }
    var dragging by remember { mutableStateOf(Handle.NONE) }

    Box(modifier.onSizeChanged { container = Size(it.width.toFloat(), it.height.toFloat()) }) {
        AsyncImage(
            model = File(path),
            contentDescription = "Screenshot being trimmed",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        if (container != Size.Zero && aspect > 0f) {
            val shown = fittedRect(container, aspect)

            Canvas(
                modifier = Modifier.fillMaxSize().pointerInput(shown, crop) {
                    detectDragGestures(
                        onDragStart = { at -> dragging = handleAt(at, shown, crop) },
                        onDragEnd = { dragging = Handle.NONE },
                        onDragCancel = { dragging = Handle.NONE },
                    ) { change, delta ->
                        change.consume()
                        onCropChange(crop.moved(dragging, delta, shown))
                    }
                },
            ) {
                val r = crop.toRect(shown)
                val dim = Color.Black.copy(alpha = 0.55f)
                // Dim everything outside the selection.
                drawRect(dim, topLeft = Offset(0f, 0f), size = Size(size.width, r.top))
                drawRect(dim, topLeft = Offset(0f, r.bottom), size = Size(size.width, size.height - r.bottom))
                drawRect(dim, topLeft = Offset(0f, r.top), size = Size(r.left, r.height))
                drawRect(dim, topLeft = Offset(r.right, r.top), size = Size(size.width - r.right, r.height))

                drawRect(
                    color = Color.White,
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    style = Stroke(width = 3f),
                )
                for (corner in listOf(
                    Offset(r.left, r.top), Offset(r.right, r.top),
                    Offset(r.left, r.bottom), Offset(r.right, r.bottom),
                )) {
                    drawCircle(Color.White, radius = 18f, center = corner)
                    drawCircle(Color.Black.copy(alpha = 0.6f), radius = 18f, center = corner, style = Stroke(2f))
                }
            }
        }
    }
}

private enum class Handle { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, INSIDE }

/** Where the image actually lands inside the box under ContentScale.Fit. */
private fun fittedRect(container: Size, aspect: Float): Rect {
    val containerAspect = container.width / container.height
    return if (containerAspect > aspect) {
        val w = container.height * aspect
        Rect(Offset((container.width - w) / 2f, 0f), Size(w, container.height))
    } else {
        val h = container.width / aspect
        Rect(Offset(0f, (container.height - h) / 2f), Size(container.width, h))
    }
}

private fun NormalisedCrop.toRect(shown: Rect) = Rect(
    left = shown.left + left * shown.width,
    top = shown.top + top * shown.height,
    right = shown.left + right * shown.width,
    bottom = shown.top + bottom * shown.height,
)

private fun handleAt(at: Offset, shown: Rect, crop: NormalisedCrop): Handle {
    val r = crop.toRect(shown)
    val corners = mapOf(
        Handle.TOP_LEFT to Offset(r.left, r.top),
        Handle.TOP_RIGHT to Offset(r.right, r.top),
        Handle.BOTTOM_LEFT to Offset(r.left, r.bottom),
        Handle.BOTTOM_RIGHT to Offset(r.right, r.bottom),
    )
    val nearest = corners.minByOrNull { (_, c) -> (c - at).getDistance() }
    if (nearest != null && (nearest.value - at).getDistance() <= HANDLE_GRAB_RADIUS) return nearest.key
    return if (r.contains(at)) Handle.INSIDE else Handle.NONE
}

private fun NormalisedCrop.moved(handle: Handle, delta: Offset, shown: Rect): NormalisedCrop {
    if (handle == Handle.NONE || shown.width <= 0f || shown.height <= 0f) return this
    val dx = delta.x / shown.width
    val dy = delta.y / shown.height

    return when (handle) {
        Handle.TOP_LEFT -> copy(
            left = (left + dx).coerceIn(0f, right - MIN_CROP_FRACTION),
            top = (top + dy).coerceIn(0f, bottom - MIN_CROP_FRACTION),
        )
        Handle.TOP_RIGHT -> copy(
            right = (right + dx).coerceIn(left + MIN_CROP_FRACTION, 1f),
            top = (top + dy).coerceIn(0f, bottom - MIN_CROP_FRACTION),
        )
        Handle.BOTTOM_LEFT -> copy(
            left = (left + dx).coerceIn(0f, right - MIN_CROP_FRACTION),
            bottom = (bottom + dy).coerceIn(top + MIN_CROP_FRACTION, 1f),
        )
        Handle.BOTTOM_RIGHT -> copy(
            right = (right + dx).coerceIn(left + MIN_CROP_FRACTION, 1f),
            bottom = (bottom + dy).coerceIn(top + MIN_CROP_FRACTION, 1f),
        )
        // Slide the whole selection, stopping at the edges rather than shrinking.
        Handle.INSIDE -> {
            val shiftX = dx.coerceIn(-left, 1f - right)
            val shiftY = dy.coerceIn(-top, 1f - bottom)
            copy(
                left = left + shiftX, right = right + shiftX,
                top = top + shiftY, bottom = bottom + shiftY,
            )
        }
        Handle.NONE -> this
    }
}
