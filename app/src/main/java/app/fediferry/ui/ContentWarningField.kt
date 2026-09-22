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
package app.fediferry.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import app.fediferry.data.model.ContentWarningPresets
import app.fediferry.data.model.ContentWarningPresets.Preset

/**
 * The content warning field, with the preset list one tap away.
 *
 * Preset and hand-written warning are the same text: picking from the list only
 * writes into the field, which stays editable, so a preset can be reworded or
 * thrown away like anything typed. Picking a second one appends rather than
 * replaces — see [ContentWarningPresets].
 */
@Composable
fun ContentWarningField(
    key: Any?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Content warning",
) {
    val focus = LocalFocusManager.current
    var sheetOpen by remember { mutableStateOf(false) }
    // A preset rewrites the text from outside the field, which StableTextField
    // ignores while it has focus. Bumping the key re-seeds it for that one edit.
    var revision by remember(key) { mutableIntStateOf(0) }

    StableTextField(
        key = key to revision,
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        trailingIcon = {
            IconButton(
                onClick = {
                    // The sheet settles back to hidden if the keyboard closes
                    // underneath it, so it goes away before the sheet arrives.
                    focus.clearFocus()
                    sheetOpen = true
                },
            ) {
                Icon(Icons.Outlined.Warning, contentDescription = "Pick a content warning")
            }
        },
        modifier = modifier,
    )

    if (sheetOpen) {
        ContentWarningSheet(
            initial = value,
            onClose = {
                if (it != value) {
                    onValueChange(it)
                    revision++
                }
                sheetOpen = false
            },
        )
    }
}

/**
 * The preset list, holding its own copy of the warning while it is open.
 *
 * Writing every pick straight back into the field would recompose the screen
 * under the sheet and settle the sheet closed again, which made picking a second
 * warning impossible. So the sheet edits its own copy and hands the result back
 * once, when it closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentWarningSheet(initial: String, onClose: (String) -> Unit) {
    var current by remember { mutableStateOf(initial) }

    ModalBottomSheet(
        onDismissRequest = { onClose(current) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Content warning",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (current.isNotBlank()) {
                        TextButton(onClick = { current = "" }) { Text("Clear") }
                    }
                }
                Text(
                    text = current.ifBlank { "No warning — the post shows uncovered." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }

            ContentWarningPresets.groups.forEach { group ->
                item(key = group.title) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(group.presets, key = { it.text }) { preset ->
                    PresetRow(
                        preset = preset,
                        applied = ContentWarningPresets.isApplied(current, preset),
                        onClick = { current = ContentWarningPresets.toggle(current, preset) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetRow(preset: Preset, applied: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(preset.de, style = MaterialTheme.typography.bodyLarge)
            Text(
                preset.en,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (applied) {
            Icon(
                Icons.Default.Check,
                contentDescription = "In use",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
