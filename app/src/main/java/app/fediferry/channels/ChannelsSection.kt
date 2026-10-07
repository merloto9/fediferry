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
package app.fediferry.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import app.fediferry.api.ChannelDto
import coil3.compose.AsyncImage

/**
 * Settings → channels, while connected to a FediFerry server: the accounts the
 * project posts to, added by signing in through the server.
 */
@Composable
fun ChannelsSection(viewModel: ChannelsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val resources = LocalResources.current
    var instance by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    // Back from the browser: show the channel that was just added.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { viewModel.messages.collect { message = resources.getString(R.string.channels_failed, it) } }

    Text(stringResource(R.string.channels_intro), style = MaterialTheme.typography.bodySmall)
    state.error?.let { Text(stringResource(R.string.channels_failed, it), color = MaterialTheme.colorScheme.error) }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    if (!state.loading && state.channels.isEmpty()) {
        Text(stringResource(R.string.channels_none), style = MaterialTheme.typography.bodyMedium)
    }
    state.channels.forEach { channel -> ChannelCard(channel, viewModel) }

    if (state.localAccounts > 0) {
        Text(
            pluralStringResource(R.plurals.channels_local_accounts, state.localAccounts, state.localAccounts),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = viewModel::takeOverAccounts) { Text(stringResource(R.string.channels_take_over)) }
    }

    Text(stringResource(R.string.channels_add_title), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = instance,
        onValueChange = { instance = it.trim() },
        label = { Text(stringResource(R.string.channels_instance)) },
        placeholder = { Text("mastodon.social") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { viewModel.signIn(instance) }, enabled = instance.contains('.')) {
        Text(stringResource(R.string.channels_sign_in))
    }
}

@Composable
private fun ChannelCard(channel: ChannelDto, viewModel: ChannelsViewModel) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    if (renaming) {
        var name by remember { mutableStateOf(channel.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.channels_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { viewModel.rename(channel, name); renaming = false }, enabled = name.isNotBlank()) { Text(stringResource(R.string.library_save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.delete_back)) } },
        )
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.channels_remove_title, channel.name)) },
            text = { Text(stringResource(R.string.channels_remove_text)) },
            confirmButton = { TextButton(onClick = { viewModel.remove(channel); removing = false }) { Text(stringResource(R.string.channels_remove), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.delete_back)) } },
        )
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (channel.isDefault) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = channel.avatarUrl, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleSmall)
                Text("@${channel.acct}@${channel.instance}", style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        R.string.channels_capabilities,
                        channel.capabilities.maxCharacters,
                        channel.capabilities.maxMediaAttachments,
                        channel.capabilities.altTextMaxLength,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (channel.isDefault) Text(stringResource(R.string.channels_default), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.channels_actions, channel.name)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!channel.isDefault) DropdownMenuItem(text = { Text(stringResource(R.string.channels_make_default)) }, onClick = { menu = false; viewModel.makeDefault(channel) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.channels_rename)) }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.channels_refresh)) }, onClick = { menu = false; viewModel.refreshCapabilities(channel) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.channels_remove)) }, onClick = { menu = false; removing = true })
                }
            }
        }
    }
}

