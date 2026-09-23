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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.fediferry.ai.ModelTestReport
import app.fediferry.data.model.AiKind
import app.fediferry.data.model.AiModel
import app.fediferry.media.cleanup.EditWireFormat
import app.fediferry.media.cleanup.MaskPolarity

/**
 * Every model of one kind, each with its own endpoint, model name and key,
 * one of them the default. The editor and the Clean up screen can pick any
 * of them for a single picture.
 */
@Composable
internal fun AiModelsSection(kind: AiKind, state: SettingsState, viewModel: SettingsViewModel) {
    val models = state.aiModels.filter { it.kind == kind }
    if (models.isEmpty()) {
        Text(
            "No model yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    models.forEach { model ->
        AiModelCard(
            model = model,
            hasKey = model.id in state.aiModelsWithKey,
            onlyOne = models.size == 1,
            testing = model.id in state.aiTesting,
            report = state.aiTests[model.id],
            viewModel = viewModel,
        )
    }
    OutlinedButton(onClick = { viewModel.addAiModel(kind) }) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("Add a model", modifier = Modifier.padding(start = 6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiModelCard(
    model: AiModel,
    hasKey: Boolean,
    onlyOne: Boolean,
    testing: Boolean,
    report: ModelTestReport?,
    viewModel: SettingsViewModel,
) {
    var draft by remember(model) { mutableStateOf(model) }
    // Null until typed: the saved key is never shown, only replaced.
    var newKey by remember(model) { mutableStateOf<String?>(null) }
    val changed = draft != model || newKey != null

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (model.isDefault) {
                    Text(
                        "Default",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                placeholder = { Text("What you call it, e.g. Gemini Flash") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.endpoint,
                onValueChange = { draft = draft.copy(endpoint = AiModel.clean(it)) },
                label = { Text("Endpoint URL") },
                isError = draft.endpoint.isNotEmpty() && !draft.hasValidEndpoint,
                supportingText = if (draft.endpoint.isNotEmpty() && !draft.hasValidEndpoint) {
                    { Text("Not a web address — it should start with https://") }
                } else {
                    null
                },
                // Autocorrect split words and dropped letters in addresses.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                placeholder = {
                    Text(
                        if (model.kind == AiKind.ALT_TEXT) {
                            "https://api.example.com/v1/chat/completions"
                        } else {
                            "https://api.example.com/v1/images/edits"
                        },
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.model,
                onValueChange = { draft = draft.copy(model = AiModel.clean(it)) },
                label = { Text("Model") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newKey.orEmpty(),
                onValueChange = { newKey = it },
                label = { Text("API key") },
                placeholder = { Text(if (hasKey) "Saved — type to replace" else "None") },
                supportingText = if (hasKey && newKey == null) {
                    { Text("Stored encrypted on this phone. It is never shown again.") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (model.kind == AiKind.IMAGE_EDIT) {
                Text("Request format", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditWireFormat.entries.forEach { format ->
                        FilterChip(
                            selected = draft.wireFormat == format.name,
                            onClick = { draft = draft.copy(wireFormat = format.name) },
                            label = { Text(if (format == EditWireFormat.MULTIPART) "Multipart" else "JSON") },
                        )
                    }
                }
                Text("Mask marks the area to replace as", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaskPolarity.entries.forEach { polarity ->
                        FilterChip(
                            selected = draft.maskPolarity == polarity.name,
                            onClick = { draft = draft.copy(maskPolarity = polarity.name) },
                            label = { Text(if (polarity == MaskPolarity.TRANSPARENT_HOLE) "Transparent" else "White") },
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Button(onClick = { viewModel.saveAiModel(draft, newKey) }, enabled = changed) { Text("Save") }
                // Tests what is saved, so an unsaved edit cannot pass for tested.
                OutlinedButton(
                    onClick = { viewModel.testAiModel(model) },
                    enabled = !testing && !changed && model.hasValidEndpoint,
                ) { Text(if (testing) "Testing…" else "Test") }
                if (!model.isDefault && !onlyOne) {
                    TextButton(onClick = { viewModel.setDefaultAiModel(model) }) { Text("Make default") }
                }
                TextButton(onClick = { viewModel.deleteAiModel(model) }) { Text("Delete") }
            }
            if (changed) {
                Text(
                    "Save first to test the changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (testing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "Sending a tiny test picture…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            report?.let { TestReport(it) }
        }
    }
}

/** A test result: the verdict, then whatever the server told us. */
@Composable
private fun TestReport(report: ModelTestReport) {
    Surface(
        color = if (report.ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (report.ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(report.summary, style = MaterialTheme.typography.bodyMedium)
            report.details.forEach { (label, value) ->
                Text("$label: $value", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
