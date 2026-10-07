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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.fediferry.R
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
        stringResource(R.string.placeholders_hashtags_intro),
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
                        contentDescription = stringResource(R.string.placeholders_remove_hashtag, tag),
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
            label = { Text(stringResource(R.string.placeholders_new_hashtag)) },
            placeholder = { Text(stringResource(R.string.placeholders_new_hashtag_example)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = ::add, enabled = Hashtags.normalize(typed) != null) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.placeholders_add_hashtag))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.placeholders_remember_sent))
            Text(
                stringResource(R.string.placeholders_remember_sent_explain),
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
        Text(stringResource(R.string.placeholders_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = viewModel::newPlaceholderKey) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.placeholders_new))
        }
    }
    Text(
        stringResource(R.string.placeholders_intro),
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
                stringResource(R.string.placeholders_tags_reserved),
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
        trimmed.isEmpty() -> stringResource(R.string.placeholders_problem_empty)
        trimmed in PlaceholderKey.RESERVED -> stringResource(R.string.placeholders_problem_reserved, trimmed)
        !PlaceholderKey.isValidName(trimmed) -> stringResource(R.string.placeholders_problem_chars)
        trimmed in takenNames -> stringResource(R.string.placeholders_problem_taken)
        else -> null
    }
    val filledBy = Modules.all.filter { key.recipeFor(it.source).isNotBlank() }.map { it.name }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.placeholders_name)) },
                isError = problem != null,
                supportingText = { Text(problem ?: stringResource(R.string.placeholders_write_hint, trimmed)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (filledBy.isEmpty()) {
                    stringResource(R.string.placeholders_no_source)
                } else {
                    stringResource(R.string.placeholders_filled_from, filledBy.joinToString())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.renamePlaceholderKey(key, name) },
                    enabled = problem == null && trimmed != key.name,
                ) { Text(stringResource(R.string.placeholders_rename)) }
                TextButton(onClick = { viewModel.deletePlaceholderKey(key.id) }) { Text(stringResource(R.string.placeholders_delete)) }
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
    Text(stringResource(R.string.placeholders_modules_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.placeholders_modules_intro),
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
                    Text(stringResource(module.summary), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { open = !open }) {
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(
                            if (open) R.string.placeholders_module_close else R.string.placeholders_module_open,
                            module.name,
                        ),
                    )
                }
            }
            if (!open) return@Column

            Text(stringResource(R.string.placeholders_recognises), style = MaterialTheme.typography.labelMedium)
            if (module.recognises.isNotEmpty()) {
                Text(module.recognises.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            module.recognisesNote?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }

            Text(stringResource(R.string.placeholders_sends), style = MaterialTheme.typography.labelMedium)
            module.fields.forEach { field ->
                Row {
                    Text(
                        "{${field.name}}  ",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        stringResource(field.description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.placeholders_takes_from, module.name), style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(R.string.placeholders_recipe_explain, module.name),
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
                    placeholder = {
                        Text(
                            if (key.isTags) {
                                stringResource(R.string.placeholders_no_tags_from, module.name)
                            } else {
                                stringResource(R.string.placeholders_empty)
                            },
                        )
                    },
                    isError = unknown.isNotEmpty(),
                    supportingText = if (unknown.isNotEmpty()) {
                        {
                            Text(
                                stringResource(
                                    R.string.placeholders_unknown_fields,
                                    unknown.joinToString { "{$it}" },
                                    module.name,
                                ),
                            )
                        }
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
                Text(stringResource(R.string.placeholders_none_defined), style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { viewModel.saveRecipes(module.source, draft) },
                enabled = draft != saved,
            ) { Text(stringResource(R.string.placeholders_save_module, module.name)) }
        }
    }
}

/** Adds a field at the end, with a space when the recipe already has text. */
private fun String.append(token: String): String =
    if (isEmpty() || last().isWhitespace()) this + token else "$this $token"
