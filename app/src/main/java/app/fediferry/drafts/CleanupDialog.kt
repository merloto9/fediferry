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

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.fediferry.R
import app.fediferry.api.CleanupArea
import app.fediferry.media.cleanup.CleanupPipeline
import app.fediferry.media.cleanup.CleanupRule
import app.fediferry.media.cleanup.Region
import app.fediferry.media.cleanup.TreatmentKind
import app.fediferry.ui.ModelPicker
import app.fediferry.ui.StableTextField
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Clean up one picture: draw over what should go, choose what happens there,
 * and — for "Erase with AI" — tell the model what to do, for this picture
 * alone. Ticking Remember keeps the areas as the profile's, so the next
 * screenshot from the same app is cleaned without drawing.
 */
@Composable
internal fun CleanupDialog(
    model: Any,
    pictureKey: String,
    state: DraftEditorState,
    viewModel: DraftEditorViewModel,
    onApply: (areas: List<CleanupArea>, instruction: String, modelId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var areas by remember { mutableStateOf(listOf<CleanupArea>()) }
    var treatment by remember { mutableStateOf(TreatmentKind.FILL) }
    var instruction by remember { mutableStateOf(state.imageInstruction) }
    var modelId by remember { mutableStateOf<String?>(null) }
    var profileId by remember { mutableStateOf<String?>(null) }
    var keep by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }

    LaunchedEffect(pictureKey) {
        source = PictureTools.load(context, model)
        failed = source == null
        // The profile used last is almost always the one wanted again.
        profileId = viewModel.lastProfileId()
        profileId?.let { areas = viewModel.profileAreas(it) }
    }
    LaunchedEffect(source, areas) {
        val bitmap = source ?: return@LaunchedEffect
        preview = PictureTools.preview(bitmap, areas.map { it.toRule() })
    }

    if (naming) {
        NewProfileDialog(
            onDismiss = { naming = false },
            onConfirm = { name ->
                naming = false
                scope.launch { viewModel.createProfile(name)?.let { profileId = it.id; keep = true } }
            },
        )
    }

    val usesModel = treatment == TreatmentKind.AI_ERASE || areas.any { it.treatment == TreatmentKind.AI_ERASE.name }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
                Text(
                    stringResource(R.string.cleanup_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    stringResource(R.string.cleanup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                TreatmentPicker(treatment) { treatment = it }
                // Only in the way when it matters: once an area goes to the model.
                if (usesModel) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val configured = state.imageModels.isNotEmpty()
                        if (!configured) {
                            Text(
                                stringResource(R.string.cleanup_no_model),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        StableTextField(
                            key = pictureKey,
                            value = instruction,
                            onValueChange = { instruction = it },
                            label = stringResource(R.string.cleanup_instruction_label),
                            placeholder = CleanupPipeline.DEFAULT_INSTRUCTION,
                            enabled = configured,
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.cleanup_instruction_scope),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (configured) {
                            ModelPicker(
                                models = state.imageModels,
                                current = app.fediferry.data.model.AiModel.pick(state.imageModels, modelId),
                                onPick = { modelId = it },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                    val shown = preview
                    when {
                        shown != null -> DrawingCanvas(shown) { region ->
                            areas = areas + CleanupArea(region.left, region.top, region.right, region.bottom, treatment.name)
                        }
                        failed -> Text(stringResource(R.string.cleanup_no_image), Modifier.align(Alignment.Center))
                        else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
                ProfileRow(
                    state = state,
                    profileId = profileId,
                    keep = keep,
                    onPick = { id -> profileId = id; scope.launch { areas = viewModel.profileAreas(id) } },
                    onRemember = { keep = it },
                    onNewProfile = { naming = true },
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.delete_back)) }
                    TextButton(onClick = { areas = emptyList() }, enabled = areas.isNotEmpty()) { Text(stringResource(R.string.cleanup_clear)) }
                    Text(pluralStringResource(R.plurals.cleanup_areas, areas.size, areas.size), style = MaterialTheme.typography.labelMedium)
                    Button(
                        onClick = {
                            val id = profileId
                            if (keep && id != null) viewModel.saveProfile(id, areas)
                            if (areas.isEmpty()) onDismiss() else onApply(areas, instruction, modelId)
                        },
                        enabled = source != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.cleanup_use_this)) }
                }
            }
        }
    }
}

private fun CleanupArea.toRule() = CleanupRule(
    Region(left, top, right, bottom),
    runCatching { TreatmentKind.valueOf(treatment) }.getOrDefault(TreatmentKind.FILL),
)

@Composable
private fun TreatmentPicker(selected: TreatmentKind, onSelect: (TreatmentKind) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TreatmentKind.entries.forEach { kind ->
            FilterChip(selected = selected == kind, onClick = { onSelect(kind) }, label = { Text(kind.label()) })
        }
    }
}

@Composable
private fun TreatmentKind.label(): String = stringResource(
    when (this) {
        TreatmentKind.FILL -> R.string.cleanup_treatment_fill
        TreatmentKind.CROP_AWAY -> R.string.cleanup_treatment_crop_away
        TreatmentKind.BLUR -> R.string.cleanup_treatment_blur
        TreatmentKind.PIXELATE -> R.string.cleanup_treatment_pixelate
        TreatmentKind.AI_ERASE -> R.string.cleanup_treatment_ai_erase
    },
)

@Composable
private fun ProfileRow(
    state: DraftEditorState,
    profileId: String?,
    keep: Boolean,
    onPick: (String) -> Unit,
    onRemember: (Boolean) -> Unit,
    onNewProfile: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { open = true }) {
                val name = state.profiles.firstOrNull { it.id == profileId }?.name
                Text(stringResource(R.string.cleanup_profile, name ?: stringResource(R.string.cleanup_profile_none)))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.profiles.forEach { profile ->
                    DropdownMenuItem(text = { Text(profile.name) }, onClick = { open = false; onPick(profile.id) })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.cleanup_new_profile_more)) }, onClick = { open = false; onNewProfile() })
            }
        }
        if (profileId != null) {
            Checkbox(checked = keep, onCheckedChange = onRemember)
            Text(stringResource(R.string.cleanup_remember), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun NewProfileDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cleanup_new_profile)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.cleanup_name)) },
                placeholder = { Text("Instagram") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.cleanup_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cleanup_cancel)) } },
    )
}

/** Minimum drag, in fractions of the picture, before a box counts as meant. */
private const val MIN_DRAG_FRACTION = 0.01f

/** The preview with a drag-to-draw box over it; colours over the photo are fixed, as in the crop canvas. */
@Composable
private fun DrawingCanvas(preview: Bitmap, onRegion: (Region) -> Unit) {
    var container by remember { mutableStateOf(Size.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragNow by remember { mutableStateOf<Offset?>(null) }
    Box(Modifier.fillMaxSize().onSizeChanged { container = Size(it.width.toFloat(), it.height.toFloat()) }) {
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = stringResource(R.string.cleanup_image_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        val aspect = preview.width.toFloat() / preview.height
        if (container == Size.Zero || aspect <= 0f) return@Box
        val shown = fittedRect(container, aspect)
        Canvas(
            Modifier.fillMaxSize().pointerInput(shown) {
                detectDragGestures(
                    onDragStart = { dragStart = it; dragNow = it },
                    onDragEnd = {
                        val a = dragStart
                        val b = dragNow
                        if (a != null && b != null) regionOf(a, b, shown)?.let(onRegion)
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
                val r = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
                drawRect(Color.White.copy(alpha = 0.25f), topLeft = r.topLeft, size = r.size)
                drawRect(Color.White, topLeft = r.topLeft, size = r.size, style = Stroke(width = 3f))
            }
        }
    }
}

private fun fittedRect(container: Size, aspect: Float): Rect =
    if (container.width / container.height > aspect) {
        val w = container.height * aspect
        Rect(Offset((container.width - w) / 2f, 0f), Size(w, container.height))
    } else {
        val h = container.width / aspect
        Rect(Offset(0f, (container.height - h) / 2f), Size(container.width, h))
    }

/** A drag in view coordinates as a region in fractions of the picture. */
private fun regionOf(a: Offset, b: Offset, shown: Rect): Region? {
    if (shown.width <= 0f || shown.height <= 0f) return null
    val left = ((min(a.x, b.x) - shown.left) / shown.width).coerceIn(0f, 1f)
    val right = ((max(a.x, b.x) - shown.left) / shown.width).coerceIn(0f, 1f)
    val top = ((min(a.y, b.y) - shown.top) / shown.height).coerceIn(0f, 1f)
    val bottom = ((max(a.y, b.y) - shown.top) / shown.height).coerceIn(0f, 1f)
    if (right - left < MIN_DRAG_FRACTION || bottom - top < MIN_DRAG_FRACTION) return null
    return Region(left, top, right, bottom)
}
