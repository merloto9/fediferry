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
package app.fediferry.ui.cleanup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.media.cleanup.CleanupPipeline
import app.fediferry.media.cleanup.Region
import app.fediferry.ui.LoadingOverlay
import app.fediferry.ui.StableTextField
import app.fediferry.media.cleanup.TreatmentKind
import kotlin.math.max
import kotlin.math.min

/**
 * Draw over what should go, choose what should happen there, and optionally
 * keep the result as a profile so the next screenshot from the same place needs
 * no drawing at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(
    itemId: String,
    onDone: (String) -> Unit,
    viewModel: CleanupViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var naming by remember { mutableStateOf(false) }

    LaunchedEffect(itemId) { viewModel.load(itemId) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (naming) {
        NewProfileDialog(
            onDismiss = { naming = false },
            onConfirm = {
                viewModel.createProfile(it)
                naming = false
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Clean up") },
                actions = { TextButton(onClick = { onDone(itemId) }) { Text("Skip") } },
            )
        },
    ) { padding ->
        if (state.sourcePath == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loaded) Text("No image to clean up") else CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Drag over anything that should go. The treatment applies to the next " +
                    "box you draw, so different things can be handled differently.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            TreatmentPicker(state.treatment, viewModel::setTreatment)

            // Only in the way when it is relevant: the prompt appears once an
            // area is actually going to the model.
            if (state.treatment == TreatmentKind.AI_ERASE || viewModel.usesModel()) {
                AiPrompt(state, viewModel)
            }

            PreviewCanvas(
                state = state,
                onRegion = viewModel::addRule,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            )

            ProfileRow(state, viewModel, onNewProfile = { naming = true })

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = viewModel::clearRules,
                    enabled = state.rules.isNotEmpty(),
                ) { Text("Clear") }
                Text(
                    "${state.rules.size} area${if (state.rules.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Button(
                    onClick = { viewModel.apply(onDone) },
                    enabled = !state.applying,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.applying) "Applying…" else "Use this") }
            }
        }
    }

    // Only the model round trip is slow enough to need saying; a local fill
    // is done before an overlay could be read.
    if (state.applying && state.modelConfigured && viewModel.usesModel()) {
        LoadingOverlay(
            title = "Erasing with the image model",
            detail = "The picture and the areas you marked have been sent to your " +
                "image model. Waiting for it to send back the cleaned-up version.",
            icon = Icons.Outlined.AutoFixHigh,
        )
    }
}

@Composable
private fun TreatmentPicker(selected: TreatmentKind, onSelect: (TreatmentKind) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TreatmentKind.entries.forEach { kind ->
            FilterChip(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                label = { Text(kind.label()) },
            )
        }
    }
}

private fun TreatmentKind.label(): String = when (this) {
    TreatmentKind.FILL -> "Fill in"
    TreatmentKind.CROP_AWAY -> "Crop away"
    TreatmentKind.BLUR -> "Blur"
    TreatmentKind.PIXELATE -> "Pixelate"
    TreatmentKind.AI_ERASE -> "Erase with AI"
}

/**
 * The instruction sent with the AI regions, and an honest word about whether a
 * model is configured at all.
 */
@Composable
private fun AiPrompt(state: CleanupState, viewModel: CleanupViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!state.modelConfigured) {
            Text(
                "No image model is set up, so these areas will be filled in from " +
                    "their surroundings instead. Add one under Settings → Image model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        StableTextField(
            key = state.item?.id,
            value = state.instruction,
            onValueChange = viewModel::setInstruction,
            label = "Tell the model what to do",
            placeholder = CleanupPipeline.DEFAULT_INSTRUCTION,
            enabled = state.modelConfigured,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Applies to this image only. The default lives in Settings.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileRow(
    state: CleanupState,
    viewModel: CleanupViewModel,
    onNewProfile: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { open = true }) {
                Text("Profile: ${state.profileName ?: "none"}")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name) },
                        onClick = {
                            viewModel.selectProfile(profile.id)
                            open = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("New profile…") },
                    onClick = {
                        open = false
                        onNewProfile()
                    },
                )
            }
        }
        if (state.profileId != null) {
            Checkbox(
                checked = state.rememberForProfile,
                onCheckedChange = viewModel::setRememberForProfile,
            )
            Text("Remember", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun NewProfileDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Instagram") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Minimum drag, in fractions of the image, before a box counts as intentional. */
private const val MIN_DRAG_FRACTION = 0.01f

@Composable
private fun PreviewCanvas(
    state: CleanupState,
    onRegion: (Region) -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = state.preview
    var container by remember { mutableStateOf(Size.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragNow by remember { mutableStateOf<Offset?>(null) }

    Box(modifier.onSizeChanged { container = Size(it.width.toFloat(), it.height.toFloat()) }) {
        if (preview == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Box
        }

        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = "Image being cleaned up",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        val aspect = preview.width.toFloat() / preview.height
        if (container != Size.Zero && aspect > 0f) {
            val shown = fittedRect(container, aspect)

            Canvas(
                modifier = Modifier.fillMaxSize().pointerInput(shown) {
                    detectDragGestures(
                        onDragStart = { dragStart = it; dragNow = it },
                        onDragEnd = {
                            val a = dragStart
                            val b = dragNow
                            if (a != null && b != null) {
                                regionOf(a, b, shown)?.let(onRegion)
                            }
                            dragStart = null
                            dragNow = null
                        },
                        onDragCancel = { dragStart = null; dragNow = null },
                    ) { change, _ ->
                        change.consume()
                        dragNow = change.position
                    }
                },
            ) {
                val a = dragStart
                val b = dragNow
                if (a != null && b != null) {
                    val r = Rect(
                        min(a.x, b.x), min(a.y, b.y),
                        max(a.x, b.x), max(a.y, b.y),
                    )
                    drawRect(
                        Color.White.copy(alpha = 0.25f),
                        topLeft = Offset(r.left, r.top),
                        size = Size(r.width, r.height),
                    )
                    drawRect(
                        Color.White,
                        topLeft = Offset(r.left, r.top),
                        size = Size(r.width, r.height),
                        style = Stroke(width = 3f),
                    )
                }
            }
        }
    }
}

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

/** Turns a drag in view coordinates into a region in image fractions. */
private fun regionOf(a: Offset, b: Offset, shown: Rect): Region? {
    if (shown.width <= 0f || shown.height <= 0f) return null
    val left = ((min(a.x, b.x) - shown.left) / shown.width).coerceIn(0f, 1f)
    val right = ((max(a.x, b.x) - shown.left) / shown.width).coerceIn(0f, 1f)
    val top = ((min(a.y, b.y) - shown.top) / shown.height).coerceIn(0f, 1f)
    val bottom = ((max(a.y, b.y) - shown.top) / shown.height).coerceIn(0f, 1f)
    if (right - left < MIN_DRAG_FRACTION || bottom - top < MIN_DRAG_FRACTION) return null
    return Region(left, top, right, bottom)
}
