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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import app.fediferry.api.ChannelDto
import app.fediferry.api.Platform
import coil3.compose.AsyncImage

/**
 * Settings → Channels: where the project's posts go — on a server or on this
 * phone. Each channel opens on a page of its own; a new one starts with a
 * name and gets its account there.
 */
@Composable
fun ChannelsSection(onOpenChannel: (String) -> Unit, viewModel: ChannelsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val resources = LocalResources.current
    var message by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { viewModel.messages.collect { message = resources.getString(R.string.channels_failed, it) } }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.channels_new)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.channel_name)) },
                    placeholder = { Text(stringResource(R.string.channel_name_example)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { naming = false; viewModel.create(name, onOpenChannel) }, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.channels_create))
                }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.delete_back)) } },
        )
    }

    Text(stringResource(R.string.channels_intro), style = MaterialTheme.typography.bodySmall)
    state.error?.let { Text(stringResource(R.string.channels_failed, it), color = MaterialTheme.colorScheme.error) }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    if (!state.loading && state.channels.isEmpty()) {
        Text(stringResource(R.string.channels_none), style = MaterialTheme.typography.bodyMedium)
    }
    state.channels.forEach { channel -> ChannelRow(channel, onClick = { onOpenChannel(channel.id) }) }

    OutlinedButton(onClick = { naming = true }) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(stringResource(R.string.channels_new), modifier = Modifier.padding(start = 8.dp))
    }

    if (state.localAccounts > 0) {
        Text(
            pluralStringResource(R.plurals.channels_local_accounts, state.localAccounts, state.localAccounts),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = viewModel::takeOverAccounts) { Text(stringResource(R.string.channels_take_over)) }
    }
}

@Composable
private fun ChannelRow(channel: ChannelDto, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (channel.isDefault) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val account = channel.account
            if (account != null) {
                AsyncImage(model = account.avatarUrl, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
            } else {
                Icon(Icons.Outlined.LinkOff, contentDescription = null, modifier = Modifier.size(40.dp).padding(8.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    account?.let { "@${it.acct}@${it.instance} · ${Platform.fromName(it.platform)?.label ?: it.platform}" }
                        ?: stringResource(R.string.channels_no_account),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (account == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (channel.isDefault) {
                    Text(stringResource(R.string.channels_default), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
