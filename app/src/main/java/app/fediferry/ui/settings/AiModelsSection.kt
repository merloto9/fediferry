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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.fediferry.R
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
            stringResource(R.string.models_none),
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
        Text(stringResource(R.string.models_add), modifier = Modifier.padding(start = 6.dp))
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
                        stringResource(R.string.models_default),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text(stringResource(R.string.models_name)) },
                placeholder = { Text(stringResource(R.string.models_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.endpoint,
                onValueChange = { draft = draft.copy(endpoint = AiModel.clean(it)) },
                label = { Text(stringResource(R.string.models_endpoint)) },
                isError = draft.endpoint.isNotEmpty() && !draft.hasValidEndpoint,
                supportingText = if (draft.endpoint.isNotEmpty() && !draft.hasValidEndpoint) {
                    { Text(stringResource(R.string.models_endpoint_invalid)) }
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
                label = { Text(stringResource(R.string.models_model)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newKey.orEmpty(),
                onValueChange = { newKey = it },
                label = { Text(stringResource(R.string.models_api_key)) },
                placeholder = { Text(stringResource(if (hasKey) R.string.models_key_saved_hint else R.string.models_key_none)) },
                supportingText = if (hasKey && newKey == null) {
                    { Text(stringResource(R.string.models_key_stored)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (model.kind == AiKind.IMAGE_EDIT) {
                Text(stringResource(R.string.models_request_format), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditWireFormat.entries.forEach { format ->
                        FilterChip(
                            selected = draft.wireFormat == format.name,
                            onClick = { draft = draft.copy(wireFormat = format.name) },
                            label = { Text(if (format == EditWireFormat.MULTIPART) "Multipart" else "JSON") },
                        )
                    }
                }
                Text(stringResource(R.string.models_mask_marks), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaskPolarity.entries.forEach { polarity ->
                        FilterChip(
                            selected = draft.maskPolarity == polarity.name,
                            onClick = { draft = draft.copy(maskPolarity = polarity.name) },
                            label = {
                                Text(
                                    stringResource(
                                        if (polarity == MaskPolarity.TRANSPARENT_HOLE) {
                                            R.string.models_mask_transparent
                                        } else {
                                            R.string.models_mask_white
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Button(onClick = { viewModel.saveAiModel(draft, newKey) }, enabled = changed) { Text(stringResource(R.string.models_save)) }
                // Tests what is saved, so an unsaved edit cannot pass for tested.
                OutlinedButton(
                    onClick = { viewModel.testAiModel(model) },
                    enabled = !testing && !changed && model.hasValidEndpoint,
                ) { Text(stringResource(if (testing) R.string.models_testing else R.string.models_test)) }
                if (!model.isDefault && !onlyOne) {
                    TextButton(onClick = { viewModel.setDefaultAiModel(model) }) { Text(stringResource(R.string.models_make_default)) }
                }
                TextButton(onClick = { viewModel.deleteAiModel(model) }) { Text(stringResource(R.string.models_delete)) }
            }
            if (changed) {
                Text(
                    stringResource(R.string.models_save_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (testing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.models_sending_test),
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
                Text(stringResource(R.string.models_report_detail, label, value), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
