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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import app.fediferry.api.ChannelDto
import app.fediferry.api.Platform
import app.fediferry.api.PlatformLimits
import app.fediferry.ui.settings.PostSettingsFields
import app.fediferry.work.ScheduleWords
import coil3.compose.AsyncImage

/**
 * One channel: its name, the fediverse account it posts as, what that
 * account's instance takes, and how posts on it start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSettingsScreen(channelId: String, onBack: () -> Unit, viewModel: ChannelSettingsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf(false) }

    LaunchedEffect(channelId) { viewModel.load(channelId) }
    // Back from signing in in the browser: show the account now connected.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    LaunchedEffect(state.message) {
        state.message?.let { (code, args) ->
            snackbar.showSnackbar(ChannelMessages.describe(resources, code, args))
            viewModel.clearMessage()
        }
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.channels_remove_title, state.channel?.name.orEmpty())) },
            text = { Text(stringResource(R.string.channels_remove_text)) },
            confirmButton = {
                TextButton(onClick = { removing = false; viewModel.remove() }) {
                    Text(stringResource(R.string.channels_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.delete_back)) } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.channel?.name ?: stringResource(R.string.settings_page_channels)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back_to_settings))
                    }
                },
                actions = {
                    if (state.channel != null) {
                        IconButton(onClick = { removing = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.channels_remove))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val channel = state.channel ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.channel_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.channels_default), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.channel_default_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // One channel is always the default: switching another on moves it there, this one cannot be switched off.
                Switch(checked = channel.isDefault, onCheckedChange = { if (it) viewModel.makeDefault() }, enabled = !state.busy)
            }

            HorizontalDivider()
            AccountPart(channel, busy = state.busy, onConnect = viewModel::connect)

            HorizontalDivider()
            LimitsPart(channel, busy = state.busy, onRefresh = viewModel::refreshLimits)

            HorizontalDivider()
            Text(stringResource(R.string.channel_post_settings), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.channel_post_settings_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PostSettingsFields(
                key = channel.id,
                value = state.post,
                onChange = viewModel::setPost,
                visionConfigured = state.visionConfigured,
                placeholderNames = state.placeholderNames,
                hashtags = state.hashtags,
            )
            Button(onClick = viewModel::save, enabled = state.unsaved && state.name.isNotBlank() && !state.busy) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

/** The account the channel posts as, and connecting one — another, or the first. */
@Composable
private fun AccountPart(channel: ChannelDto, busy: Boolean, onConnect: (String) -> Unit) {
    var instance by remember(channel.id) { mutableStateOf("") }
    var changing by remember(channel.id) { mutableStateOf(false) }
    val account = channel.account
    Text(stringResource(R.string.channel_account), style = MaterialTheme.typography.titleMedium)
    if (account != null) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = account.avatarUrl, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(account.displayName.ifBlank { account.acct }, style = MaterialTheme.typography.titleSmall)
                    Text("@${account.acct}@${account.instance}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.channel_platform, Platform.fromName(account.platform)?.label ?: account.platform),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (!changing) {
            TextButton(onClick = { changing = true }) { Text(stringResource(R.string.channel_change_account)) }
            return
        }
    } else {
        Text(stringResource(R.string.channels_no_account), style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedTextField(
        value = instance,
        onValueChange = { instance = it.trim() },
        label = { Text(stringResource(R.string.channels_instance)) },
        placeholder = { Text("mastodon.social") },
        supportingText = { Text(stringResource(R.string.channel_instance_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onConnect(instance) }, enabled = instance.contains('.') && !busy) {
            Text(stringResource(R.string.channels_sign_in))
        }
        if (account != null) TextButton(onClick = { changing = false }) { Text(stringResource(R.string.delete_back)) }
    }
}

/** What the channel's instance takes, as read from it, or the platform's usual limits until then. */
@Composable
private fun LimitsPart(channel: ChannelDto, busy: Boolean, onRefresh: () -> Unit) {
    val resources = LocalResources.current
    val account = channel.account
    val limits: PlatformLimits = channel.limits
    Text(
        account?.let { stringResource(R.string.channel_limits_of, it.instance) } ?: stringResource(R.string.channel_limits),
        style = MaterialTheme.typography.titleMedium,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(
            stringResource(R.string.channel_limit_characters, limits.maxCharacters),
            stringResource(R.string.channel_limit_pictures, limits.maxMediaAttachments),
            stringResource(R.string.channel_limit_alt, limits.altTextMaxLength),
            stringResource(if (limits.contentWarning) R.string.channel_limit_cw else R.string.channel_limit_no_cw),
            stringResource(if (limits.textOnly) R.string.channel_limit_text_only else R.string.channel_limit_needs_picture),
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
    Text(
        if (limits.readAt > 0) stringResource(R.string.channel_limits_read, ScheduleWords.fullText(resources, limits.readAt))
        else stringResource(R.string.channel_limits_usual),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (account != null) OutlinedButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.channels_refresh)) }
}
