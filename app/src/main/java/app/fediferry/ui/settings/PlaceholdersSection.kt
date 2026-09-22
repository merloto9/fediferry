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
package app.fediferry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.PlaceholderKey
import app.fediferry.template.TemplateEngine

/**
 * The placeholders the user defines, and for each one how every source fills
 * it. This is the layer between what a service sends and what a template body
 * says: a template writes `{caption}`, and this decides that `{caption}` is the
 * title on Reddit and nothing at all on Pinterest.
 */
@Composable
internal fun PlaceholdersSection(state: SettingsState, viewModel: SettingsViewModel) {
    var showFields by remember { mutableStateOf(false) }
    if (showFields) SourceFieldsDialog(onDismiss = { showFields = false })

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Placeholders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = viewModel::newPlaceholderKey) {
            Icon(Icons.Default.Add, contentDescription = "New placeholder")
        }
    }
    Text(
        "Define your own placeholders for template bodies, and say for each source what " +
            "fills them — using the fields that source sends. A source left empty leaves " +
            "the placeholder empty, and its line drops out of the post. {link}, {tags} and " +
            "{date} are built in.",
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = { showFields = true }) { Text("What does each source send?") }

    if (state.placeholderKeys.isEmpty()) {
        Text(
            "No placeholders yet. Templates can still use {link}, {tags} and {date}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    state.placeholderKeys.forEach { key ->
        PlaceholderCard(
            key = key,
            takenNames = state.placeholderKeys.filter { it.id != key.id }.map { it.name }.toSet(),
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceholderCard(key: PlaceholderKey, takenNames: Set<String>, viewModel: SettingsViewModel) {
    var draft by remember(key) { mutableStateOf(key) }
    val name = draft.name.trim()
    val nameProblem = when {
        name.isEmpty() -> "Give it a name"
        name in PlaceholderKey.BUILT_IN -> "{$name} is built in"
        !PlaceholderKey.isValidName(name) -> "Letters, digits and _ only"
        name in takenNames -> "Another placeholder is already called that"
        else -> null
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                isError = nameProblem != null,
                supportingText = { Text(nameProblem ?: "Write {$name} in a template body") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ContentSource.entries.forEach { source ->
                val recipe = draft.recipeFor(source)
                val unknown = TemplateEngine.unknownIn(recipe, source.fields.map { it.name })
                OutlinedTextField(
                    value = recipe,
                    onValueChange = { draft = draft.withRecipe(source, it) },
                    label = { Text("From ${source.label}") },
                    placeholder = { Text("Empty — nothing from ${source.label}") },
                    isError = unknown.isNotEmpty(),
                    supportingText = if (unknown.isNotEmpty()) {
                        {
                            Text(
                                unknown.joinToString { "{$it}" } +
                                    " ${if (unknown.size == 1) "is not a field" else "are not fields"} " +
                                    "${source.label} sends",
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    source.fields.forEach { field ->
                        AssistChip(
                            onClick = { draft = draft.withRecipe(source, recipe.append("{${field.name}}")) },
                            label = { Text("{${field.name}}", fontFamily = FontFamily.Monospace) },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.savePlaceholderKey(draft) },
                    enabled = nameProblem == null && draft != key,
                ) { Text("Save") }
                TextButton(onClick = { viewModel.deletePlaceholderKey(key.id) }) { Text("Delete") }
            }
        }
    }
}

private fun PlaceholderKey.withRecipe(source: ContentSource, recipe: String) =
    copy(mappings = mappings + (source.name to recipe))

/** Adds a field at the end, with a space when the recipe already has text. */
private fun String.append(token: String): String =
    if (isEmpty() || last().isWhitespace()) this + token else "$this $token"

/** Every source's fields, and what is actually in them. */
@Composable
private fun SourceFieldsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What each source sends") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "These are the fields a placeholder's recipe can use. A field the source " +
                        "left empty for a particular post comes out empty, and so does any " +
                        "recipe line built only from empty fields.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ContentSource.entries.forEach { source ->
                    HorizontalDivider()
                    Text(source.label, style = MaterialTheme.typography.titleSmall)
                    source.fields.forEach { field ->
                        Column {
                            Text(
                                "{${field.name}}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                field.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    "Screenshots, and links from Instagram or anywhere else FediFerry cannot " +
                        "fetch, send nothing — every placeholder is empty for them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
