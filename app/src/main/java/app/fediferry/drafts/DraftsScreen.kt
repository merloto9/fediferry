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
package app.fediferry.drafts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import app.fediferry.api.ChannelDto
import app.fediferry.api.PostDto
import app.fediferry.template.TemplateEngine
import app.fediferry.ui.DeletePostsDialog
import app.fediferry.ui.LoadingScreen
import app.fediferry.ui.OfflineBanner
import app.fediferry.ui.PostTile
import app.fediferry.ui.Space
import app.fediferry.ui.SpaceBar
import app.fediferry.ui.TileColumns

/** The project's drafts: what is being written, on which channel, and who is at it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(
    onOpenDraft: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchSpace: (Space) -> Unit,
    viewModel: DraftsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.start()
        onPauseOrDispose { viewModel.stop() }
    }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(ServerMessages.describe(resources, it)) } }

    if (deleting) {
        DeletePostsDialog(
            count = selection.size,
            onConfirm = { viewModel.delete(selection); selection = emptySet(); deleting = false },
            onDismiss = { deleting = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { SpaceBar(Space.DRAFTS, onSwitchSpace) },
        topBar = {
            Column {
            TopAppBar(
                title = {
                    Text(
                        if (selection.isEmpty()) stringResource(R.string.drafts_title)
                        else pluralStringResource(R.plurals.selection_count, selection.size, selection.size),
                    )
                },
                navigationIcon = {
                    if (selection.isNotEmpty()) {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.selection_clear))
                        }
                    }
                },
                actions = {
                    if (selection.isNotEmpty()) {
                        IconButton(onClick = { deleting = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.drafts_delete))
                        }
                    } else {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.queue_settings))
                        }
                    }
                },
            )
            OfflineBanner()
            }
        },
    ) { padding ->
        if (!state.loadedOnce) {
            LoadingScreen(
                title = stringResource(R.string.drafts_loading),
                detail = stringResource(R.string.server_loading_detail),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = TileColumns,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.takeIf { it != "client.unreachable" }?.let { code ->
                item(span = { GridItemSpan(maxLineSpan) }) { Notice(Icons.Outlined.Warning, ServerMessages.describe(resources, code)) }
            }
            if (pending.isNotEmpty()) {
                item(key = "pending", span = { GridItemSpan(maxLineSpan) }) {
                    PendingSharesRow(pending, onRetry = viewModel::retryUploads, onDiscard = viewModel::discardPending)
                }
            }
            if (state.drafts.isEmpty() && pending.isEmpty() && state.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) { Notice(Icons.Outlined.EditNote, stringResource(R.string.drafts_empty)) }
            }
            items(state.drafts, key = { it.id }) { draft ->
                DraftTile(
                    draft = draft,
                    channel = state.channels.firstOrNull { it.id == draft.channelId },
                    thumbnailUrl = draft.media.firstOrNull()?.let { viewModel.thumbnailUrl(it.asset.id) },
                    editedElsewhere = draft.lock?.takeIf { it.deviceId != viewModel.deviceId && it.expiresAt > System.currentTimeMillis() }?.deviceName,
                    selected = draft.id in selection,
                    onClick = { if (selection.isNotEmpty()) selection = selection.toggle(draft.id) else onOpenDraft(draft.id) },
                    onLongClick = { selection = selection.toggle(draft.id) },
                )
            }
        }
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

@Composable
private fun DraftTile(
    draft: PostDto,
    channel: ChannelDto?,
    thumbnailUrl: String?,
    editedElsewhere: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    PostTile(
        thumbnailUrl = thumbnailUrl,
        text = TemplateEngine.finish(draft.body, draft.hashtags).ifBlank { stringResource(R.string.drafts_no_text) },
        caption = channel?.label ?: stringResource(R.string.drafts_no_channel),
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        editedElsewhere?.let { device ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.drafts_being_edited, device),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
    }
}
