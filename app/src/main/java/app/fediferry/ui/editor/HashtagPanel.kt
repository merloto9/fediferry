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
package app.fediferry.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.Item

/**
 * Picks this post's hashtags. Opens in the text flow below the template row, on
 * its own background, so it reads as part of the template's settings rather
 * than a separate screen.
 *
 * Offers the whole list from Settings, plus anything this post already has that
 * the list does not — the source's own tags, or one typed here — with the
 * post's current picks ticked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HashtagPanel(
    item: Item,
    hashtagList: List<String>,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Boolean,
    sourceHashtags: List<String>,
    onAddSourceHashtags: (Boolean) -> Unit,
    remembersNewHashtags: Boolean,
    /** Sent posts per hashtag, by [Hashtags.key]; the most used are offered first. */
    uses: Map<String, Int>,
    modifier: Modifier = Modifier,
) {
    // A neutral raised surface with an outline marks the subsection. Not an
    // accent container: those are what a wallpaper palette turns pink or red,
    // and a ticked chip is itself an accent container, so it vanished into one.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hashtags", style = MaterialTheme.typography.titleSmall)

            if (item.hashtags == null) {
                // A draft from before hashtags were picked per post.
                Text(
                    "This draft was written with its hashtags already in the text — edit them " +
                        "there, or pick a template again to choose them here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            val picked = item.hashtagList
            // Most used first. The counts only change once a post is sent, so
            // ticking a chip never moves it away from the finger.
            val offered = Hashtags.sortedByUse(Hashtags.union(hashtagList, picked), uses)
            Text(
                "Ticked ones replace {tags} when the post is sent. The template decides " +
                    "which start ticked.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (offered.isEmpty()) {
                Text(
                    "No hashtags yet — add some in Settings → Hashtags, or type one below.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                offered.forEach { tag ->
                    val on = Hashtags.contains(picked, tag)
                    FilterChip(
                        selected = on,
                        onClick = { onToggle(tag) },
                        label = { Text(tag) },
                        leadingIcon = if (on) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    )
                }
            }

            // Only worth offering when the post came from somewhere.
            item.origin?.let { origin ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = item.addSourceHashtags, onCheckedChange = onAddSourceHashtags)
                    Column(Modifier.weight(1f)) {
                        Text("Add ${origin.label}'s hashtags", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (sourceHashtags.isEmpty()) {
                                "None for this post — ${origin.label}'s {tags} recipe in Settings → Placeholders & sources gives nothing."
                            } else {
                                sourceHashtags.joinToString(" ")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            var typed by remember(item.id) { mutableStateOf("") }
            fun add() {
                if (onAdd(typed)) typed = ""
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Another hashtag for this post") },
                    placeholder = { Text("#politics") },
                    supportingText = {
                        Text(
                            if (remembersNewHashtags) {
                                "Joins Settings → Hashtags once the post is sent."
                            } else {
                                "For this post only."
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = ::add, enabled = Hashtags.normalize(typed) != null) {
                    Icon(Icons.Default.Add, contentDescription = "Add hashtag")
                }
            }
        }
    }
}
