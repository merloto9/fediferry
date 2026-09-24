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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.PlaceholderKey
import app.fediferry.module.Modules
import app.fediferry.module.SourceModule
import app.fediferry.template.TemplateEngine

// --- hashtags -------------------------------------------------------------------

/** The hashtags every template picks from and the editor offers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HashtagsSection(state: SettingsState, viewModel: SettingsViewModel) {
    Text(
        "The hashtags you post with, one list for everything. Each template ticks the ones " +
            "its topic uses; the editor shows the whole list, so any post can take more.",
        style = MaterialTheme.typography.bodySmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Hashtags.sortedAlphabetically(state.hashtags).forEach { tag ->
            InputChip(
                selected = false,
                onClick = {},
                label = { Text(tag) },
                // A chip-sized icon, not an IconButton: the button's 48dp touch
                // box stretches the chip and pushes its label off centre.
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove $tag",
                        modifier = Modifier
                            .size(InputChipDefaults.IconSize)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { viewModel.deleteHashtag(tag) },
                    )
                },
            )
        }
    }
    var typed by remember { mutableStateOf("") }
    fun add() {
        if (viewModel.addHashtag(typed)) typed = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("New hashtag") },
            placeholder = { Text("#politics") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = ::add, enabled = Hashtags.normalize(typed) != null) {
            Icon(Icons.Default.Add, contentDescription = "Add hashtag")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Add new hashtags when a post is sent")
            Text(
                "Every hashtag a sent post carried that is not on the list yet joins it — " +
                    "typed in the editor, added by a source, or written into the text. Only " +
                    "posts that actually went out count.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = state.settings.rememberSentHashtags,
            onCheckedChange = viewModel::setRememberSentHashtags,
        )
    }
}

// --- placeholders ---------------------------------------------------------------

/**
 * The placeholders themselves: their names. What fills them is per source, and
 * lives with each source under Source modules.
 */
@Composable
internal fun PlaceholdersSection(state: SettingsState, viewModel: SettingsViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Placeholders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = viewModel::newPlaceholderKey) {
            Icon(Icons.Default.Add, contentDescription = "New placeholder")
        }
    }
    Text(
        "Names a template body can use. What each one says is set per source under Source " +
            "modules. {link} and {date} are built in and always filled from the share.",
        style = MaterialTheme.typography.bodySmall,
    )

    state.placeholderKeys.sortedByDescending { it.isTags }.forEach { key ->
        if (key.isTags) {
            ReservedTagsCard()
        } else {
            PlaceholderNameCard(
                key = key,
                takenNames = state.placeholderKeys.filter { it.id != key.id }.map { it.name }.toSet(),
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun ReservedTagsCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("{tags}", style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
            Text(
                "Reserved. Becomes the post's hashtags when it is sent — the template's picks " +
                    "plus any a source adds — and stays as {tags} in the editor until then, so " +
                    "the hashtags can still change. It cannot be renamed or deleted; each " +
                    "source's hashtags are set below, under Source modules.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PlaceholderNameCard(key: PlaceholderKey, takenNames: Set<String>, viewModel: SettingsViewModel) {
    var name by remember(key) { mutableStateOf(key.name) }
    val trimmed = name.trim()
    val problem = when {
        trimmed.isEmpty() -> "Give it a name"
        trimmed in PlaceholderKey.RESERVED -> "{$trimmed} is reserved"
        !PlaceholderKey.isValidName(trimmed) -> "Letters, digits and _ only"
        trimmed in takenNames -> "Another placeholder is already called that"
        else -> null
    }
    val filledBy = Modules.all.filter { key.recipeFor(it.source).isNotBlank() }.map { it.name }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                isError = problem != null,
                supportingText = { Text(problem ?: "Write {$trimmed} in a template body") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (filledBy.isEmpty()) "No source fills it yet." else "Filled from " + filledBy.joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.renamePlaceholderKey(key, name) },
                    enabled = problem == null && trimmed != key.name,
                ) { Text("Rename") }
                TextButton(onClick = { viewModel.deletePlaceholderKey(key.id) }) { Text("Delete") }
            }
        }
    }
}

// --- source modules -------------------------------------------------------------

/**
 * One card per loaded module, carrying everything about that source: what it
 * does, what it recognises, what it sends, and what each placeholder takes
 * from it.
 */
@Composable
internal fun ModulesSection(state: SettingsState, viewModel: SettingsViewModel) {
    Text("Source modules", style = MaterialTheme.typography.titleMedium)
    Text(
        "Each source the app can fetch from is a module of its own. Open one to see what it " +
            "sends and to say what each placeholder takes from it.",
        style = MaterialTheme.typography.bodySmall,
    )
    Modules.all.forEach { module -> ModuleCard(module, state.placeholderKeys, viewModel) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleCard(module: SourceModule, keys: List<PlaceholderKey>, viewModel: SettingsViewModel) {
    var open by remember { mutableStateOf(false) }
    val saved = keys.associate { it.id to it.recipeFor(module.source) }
    var draft by remember(saved) { mutableStateOf(saved) }
    val ordered = keys.sortedByDescending { it.isTags }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(module.name, style = MaterialTheme.typography.titleSmall)
                    Text(module.summary, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { open = !open }) {
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (open) "Close ${module.name}" else "Open ${module.name}",
                    )
                }
            }
            if (!open) return@Column

            Text("Recognises", style = MaterialTheme.typography.labelMedium)
            Text(module.recognises.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)

            Text("Sends", style = MaterialTheme.typography.labelMedium)
            module.fields.forEach { field ->
                Row {
                    Text(
                        "{${field.name}}  ",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        field.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()
            Text("What each placeholder takes from ${module.name}", style = MaterialTheme.typography.labelMedium)
            Text(
                "Written with the fields above. Empty means the placeholder is empty for " +
                    "${module.name} posts. For {tags}, whatever this comes to is split into " +
                    "hashtags and ticked in the editor, next to the template's.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ordered.forEach { key ->
                val recipe = draft[key.id].orEmpty()
                val unknown = TemplateEngine.unknownIn(recipe, module.fields.map { it.name })
                OutlinedTextField(
                    value = recipe,
                    onValueChange = { draft = draft + (key.id to it) },
                    label = { Text("{${key.name}}", fontFamily = FontFamily.Monospace) },
                    placeholder = { Text(if (key.isTags) "No hashtags from ${module.name}" else "Empty") },
                    isError = unknown.isNotEmpty(),
                    supportingText = if (unknown.isNotEmpty()) {
                        { Text(unknown.joinToString { "{$it}" } + " — not something ${module.name} sends") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    module.fields.forEach { field ->
                        AssistChip(
                            onClick = { draft = draft + (key.id to recipe.append("{${field.name}}")) },
                            label = { Text("{${field.name}}", fontFamily = FontFamily.Monospace) },
                        )
                    }
                }
            }
            if (keys.isEmpty()) {
                Text("No placeholders defined yet.", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { viewModel.saveRecipes(module.source, draft) },
                enabled = draft != saved,
            ) { Text("Save ${module.name}") }
        }
    }
}

/** Adds a field at the end, with a space when the recipe already has text. */
private fun String.append(token: String): String =
    if (isEmpty() || last().isWhitespace()) this + token else "$this $token"
