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
package app.fediferry.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import app.fediferry.api.ReviewFolderDto
import app.fediferry.drafts.ServerMessages
import app.fediferry.library.FolderPicker
import app.fediferry.library.NameDialog
import app.fediferry.ui.DeletePostsDialog
import app.fediferry.ui.LoadingScreen
import app.fediferry.ui.Space
import app.fediferry.ui.SpaceBar
import coil3.compose.AsyncImage

/** Ready posts: frozen, sorted into folders and labels, waiting to be scheduled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onOpenPost: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchSpace: (Space) -> Unit,
    viewModel: ReviewViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var moving by remember { mutableStateOf(false) }
    var labelling by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Naming?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.start()
        onPauseOrDispose { viewModel.stop() }
    }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(ServerMessages.describe(resources, it)) } }
    // Posts that went back to draft elsewhere drop out of the selection.
    LaunchedEffect(state.posts) { selection = selection.filter { id -> state.posts.any { it.id == id } }.toSet() }

    if (moving) {
        FolderPicker(
            folders = state.folders.map { it.id to it.name },
            onPick = { viewModel.move(selection, it); selection = emptySet(); moving = false },
            onNewFolder = { moving = false; naming = Naming.Create(selection) },
            onDismiss = { moving = false },
        )
    }
    if (labelling) {
        val chosen = state.posts.filter { it.id in selection }
        LabelSheet(
            known = (state.labels.map { it.name } + chosen.flatMap { it.labels }).distinctBy { it.lowercase() }.sortedBy { it.lowercase() },
            onAll = chosen.map { it.labels }.reduceOrNull { a, b -> a.filter { l -> b.any { it.equals(l, ignoreCase = true) } } }.orEmpty(),
            onToggle = { label, on -> viewModel.setLabel(selection, label, on) },
            onDismiss = { labelling = false },
        )
    }
    if (deleting) {
        DeletePostsDialog(
            count = selection.size,
            onConfirm = { viewModel.delete(selection); selection = emptySet(); deleting = false },
            onDismiss = { deleting = false },
        )
    }
    naming?.let { task ->
        NameDialog(
            title = stringResource(if (task is Naming.Rename) R.string.library_rename_folder else R.string.library_new_folder),
            initial = (task as? Naming.Rename)?.folder?.name.orEmpty(),
            onConfirm = { name ->
                when (task) {
                    is Naming.Create -> viewModel.createFolder(name, task.moveIds).also { selection = emptySet() }
                    is Naming.Rename -> viewModel.renameFolder(task.folder, name)
                }
                naming = null
            },
            onDismiss = { naming = null },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { SpaceBar(Space.REVIEW, onSwitchSpace) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selection.isEmpty()) stringResource(R.string.review_title)
                        else pluralStringResource(R.plurals.library_selected, selection.size, selection.size),
                    )
                },
                navigationIcon = {
                    if (selection.isNotEmpty()) {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.library_clear_selection))
                        }
                    }
                },
                actions = {
                    if (selection.isNotEmpty()) {
                        IconButton(onClick = {
                            viewModel.planIntoSlots(selection) { count ->
                                scope.launch { snackbar.showSnackbar(resources.getQuantityString(R.plurals.review_planned, count, count)) }
                            }
                            selection = emptySet()
                        }) {
                            Icon(Icons.Outlined.EventAvailable, contentDescription = stringResource(R.string.review_plan_into_slots))
                        }
                        IconButton(onClick = { moving = true }) {
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = stringResource(R.string.library_move))
                        }
                        IconButton(onClick = { labelling = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = stringResource(R.string.review_labels))
                        }
                        IconButton(onClick = { deleting = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.drafts_delete))
                        }
                    } else {
                        IconButton(onClick = { naming = Naming.Create() }) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(R.string.library_new_folder))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.queue_settings))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loadedOnce) {
            LoadingScreen(
                title = stringResource(R.string.review_loading),
                detail = stringResource(R.string.library_loading_detail),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Filters(state, viewModel::setFilter, onRename = { naming = Naming.Rename(it) }, onDeleteFolder = viewModel::deleteFolder) }
            state.error?.let { code -> item { Notice(Icons.Outlined.Warning, ServerMessages.describe(resources, code)) } }
            if (state.shown.isEmpty() && state.error == null) {
                item {
                    Notice(
                        Icons.Outlined.TaskAlt,
                        stringResource(if (state.filter == ReviewFilter.All) R.string.review_empty else R.string.review_empty_filtered),
                    )
                }
            }
            items(state.shown, key = { it.id }) { post ->
                ReviewRow(
                    post = post,
                    channel = state.channels.firstOrNull { it.id == post.channelId },
                    folder = state.folders.firstOrNull { it.id == post.reviewFolderId }?.name,
                    thumbnailUrl = post.media.firstOrNull()?.let { viewModel.thumbnailUrl(it.asset.id) },
                    selected = post.id in selection,
                    onClick = { if (selection.isNotEmpty()) selection = selection.toggle(post.id) else onOpenPost(post.id) },
                    onLongClick = { selection = selection.toggle(post.id) },
                )
            }
        }
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

private sealed interface Naming {
    data class Create(val moveIds: Set<String> = emptySet()) : Naming
    data class Rename(val folder: ReviewFolderDto) : Naming
}

@Composable
private fun Filters(state: ReviewState, onFilter: (ReviewFilter) -> Unit, onRename: (ReviewFolderDto) -> Unit, onDeleteFolder: (ReviewFolderDto) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state.filter == ReviewFilter.All, { onFilter(ReviewFilter.All) }, { Text(stringResource(R.string.library_all)) })
            FilterChip(state.filter == ReviewFilter.Unsorted, { onFilter(ReviewFilter.Unsorted) }, { Text(stringResource(R.string.library_unsorted)) })
            state.folders.forEach { folder ->
                var menu by remember { mutableStateOf(false) }
                val on = state.filter == ReviewFilter.Folder(folder.id)
                Box {
                    FilterChip(
                        selected = on,
                        onClick = { onFilter(ReviewFilter.Folder(folder.id)) },
                        label = { Text(folder.name) },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = if (on) { { Text("⋯", modifier = Modifier.clickable { menu = true }) } } else null,
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_rename_folder)) }, onClick = { menu = false; onRename(folder) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_delete_folder)) }, onClick = { menu = false; onDeleteFolder(folder) })
                    }
                }
            }
        }
        if (state.labels.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.labels.forEach { label ->
                    val on = state.filter == ReviewFilter.Label(label.name)
                    FilterChip(on, { onFilter(if (on) ReviewFilter.All else ReviewFilter.Label(label.name)) }, { Text("${label.name} · ${label.uses}") })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewRow(
    post: PostDto,
    channel: ChannelDto?,
    folder: String?,
    thumbnailUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnailUrl != null) {
                    AsyncImage(model = thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Outlined.Notes, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    post.finalText.orEmpty().ifBlank { stringResource(R.string.drafts_no_text) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(channel?.let { "@${it.acct}" }, folder).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (post.labels.isNotEmpty()) {
                    Text(
                        post.labels.joinToString("  ") { "◦ $it" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
