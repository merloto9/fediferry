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
package app.fediferry.connect

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import app.fediferry.api.SettingsKinds
import app.fediferry.data.model.AiKind

/**
 * Settings → Project: which project this phone works on — its own, or one on
 * a FediFerry server, joined with the server's address and a project token.
 * Settings that belong to the project as a whole go here as they come.
 */
@Composable
fun ServerSection(viewModel: ConnectViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val connection = state.connection

    state.pendingJoin?.let { pending ->
        JoinWarning(pending, state.busy, state.error, viewModel)
        return
    }

    if (connection == null) {
        Text(stringResource(R.string.project_this_phone), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.project_local_intro), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.project_join_server), style = MaterialTheme.typography.titleMedium)
    }
    Text(stringResource(R.string.server_intro), style = MaterialTheme.typography.bodySmall)

    if (connection != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.server_connected_to, connection.projectName), style = MaterialTheme.typography.titleMedium)
            Text(connection.address, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.server_device_id, connection.deviceId.take(8)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.server_sync_note), style = MaterialTheme.typography.bodySmall)
        if (state.joining) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.server_joining), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (state.waitingToImport > 0) {
            Text(
                pluralStringResource(R.plurals.server_import_waiting, state.waitingToImport, state.waitingToImport),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = viewModel::importPosts) { Text(stringResource(R.string.server_import)) }
        }
        state.justImported?.let {
            Text(pluralStringResource(R.plurals.server_imported, it, it), style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = viewModel::disconnect) { Text(stringResource(R.string.server_disconnect)) }
        return
    }

    var address by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    // Asked only for a server in the local network; connecting goes ahead
    // either way, and a refusal shows up in the error message.
    val askLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.connect(address, token)
    }
    OutlinedTextField(
        value = address,
        onValueChange = { address = it.trim() },
        label = { Text(stringResource(R.string.server_address)) },
        placeholder = { Text("fediferry.example.org") },
        supportingText = { Text(stringResource(R.string.server_address_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it.trim() },
        label = { Text(stringResource(R.string.server_token)) },
        placeholder = { Text("ffp_…") },
        supportingText = { Text(stringResource(R.string.server_token_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = {
            if (LocalNetwork.mustAsk(context, address)) {
                askLocalNetwork.launch(LocalNetwork.PERMISSION)
            } else {
                viewModel.connect(address, token)
            }
        },
        enabled = !state.busy && address.isNotBlank() && token.isNotBlank(),
    ) {
        Text(stringResource(if (state.busy) R.string.server_connecting else R.string.server_connect))
    }
}

/**
 * Before joining a project with a phone that has one of its own: what will be
 * copied, a choice for everything both sides have differently, and a way back.
 */
@Composable
private fun JoinWarning(pending: PendingJoin, busy: Boolean, error: String?, viewModel: ConnectViewModel) {
    val plan = pending.plan
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.join_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.join_intro, pending.connection.projectName), style = MaterialTheme.typography.bodyMedium)

            val lines = listOf(
                R.plurals.join_drafts to plan.drafts,
                R.plurals.join_in_review to plan.inReview,
                R.plurals.join_planned to plan.planned,
                R.plurals.join_channels to plan.channels,
                R.plurals.join_schedules to plan.schedules,
                R.plurals.join_hashtags to plan.hashtags,
                R.plurals.join_ai_models to plan.aiModels,
                R.plurals.join_other_settings to plan.otherSettings,
            ).filter { it.second > 0 }
            if (lines.isNotEmpty()) {
                Text(stringResource(R.string.join_copies), style = MaterialTheme.typography.titleSmall)
                lines.forEach { (res, n) -> Text("• " + pluralStringResource(res, n, n), style = MaterialTheme.typography.bodySmall) }
            }
            if (!plan.serverKeepsStages && plan.drafts + plan.inReview + plan.planned > 0) {
                Text(stringResource(R.string.join_server_too_old), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (plan.fromServer > 0) {
                Text(pluralStringResource(R.plurals.join_from_server, plan.fromServer, plan.fromServer), style = MaterialTheme.typography.bodySmall)
            }

            if (plan.conflicts.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.join_conflicts), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.chooseAll(MergeSide.PHONE) }) { Text(stringResource(R.string.join_all_phone)) }
                    TextButton(onClick = { viewModel.chooseAll(MergeSide.SERVER) }) { Text(stringResource(R.string.join_all_server)) }
                }
                plan.conflicts.forEach { conflict ->
                    ConflictChoice(conflict, pending.choices[conflict.key] ?: MergeSide.SERVER) { viewModel.choose(conflict.key, it) }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::cancelJoin, enabled = !busy) { Text(stringResource(R.string.join_back)) }
                Button(onClick = viewModel::confirmJoin, enabled = !busy) {
                    Text(stringResource(if (busy) R.string.server_joining else R.string.join_confirm))
                }
            }
        }
    }
}

@Composable
private fun ConflictChoice(conflict: MergeConflict, chosen: MergeSide, onChoose: (MergeSide) -> Unit) {
    val area = stringResource(areaLabel(conflict.area))
    val title = if (conflict.isDefault) {
        stringResource(R.string.join_default_of, aiKindLabel(conflict.name)?.let { stringResource(it) } ?: area)
    } else {
        "$area: ${conflict.name}"
    }
    Column(Modifier.selectableGroup()) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        SideRow(stringResource(R.string.join_keep_phone), conflict.phone, chosen == MergeSide.PHONE) { onChoose(MergeSide.PHONE) }
        SideRow(stringResource(R.string.join_keep_server), conflict.server, chosen == MergeSide.SERVER) { onChoose(MergeSide.SERVER) }
    }
}

@Composable
private fun SideRow(side: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(side) },
        supportingContent = detail.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    )
}

private fun areaLabel(area: String): Int = when (area) {
    SettingsKinds.TEMPLATE -> R.string.join_area_template
    SettingsKinds.PLACEHOLDER_KEY -> R.string.join_area_placeholder_key
    SettingsKinds.HASHTAG, SettingsKinds.HASHTAG_USAGE -> R.string.join_area_hashtag
    SettingsKinds.AI_MODEL -> R.string.join_area_ai_model
    SettingsKinds.CLEANUP_PROFILE -> R.string.join_area_cleanup_profile
    SettingsKinds.CLEANUP_RULE -> R.string.join_area_cleanup_rule
    SettingsKinds.SOURCE -> R.string.join_area_source
    else -> R.string.join_area_channel
}

/** For a default AI model's conflict, whose group is the model's kind. */
private fun aiKindLabel(group: String): Int? = when (group) {
    AiKind.ALT_TEXT.name -> R.string.join_ai_alt_text
    AiKind.IMAGE_EDIT.name -> R.string.join_ai_image_edit
    else -> null
}
