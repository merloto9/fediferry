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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderDelete
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import app.fediferry.ui.DeletePostsDialog
import app.fediferry.ui.FolderTile
import app.fediferry.ui.LoadingScreen
import app.fediferry.ui.OfflineBanner
import app.fediferry.ui.PostTile
import app.fediferry.ui.Space
import app.fediferry.ui.SpaceBar
import app.fediferry.ui.TileColumns

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
    // Posts that went back to draft elsewhere, or out of sight, drop out of the selection.
    LaunchedEffect(state.shown) { selection = selection.filter { id -> state.shown.any { it.id == id } }.toSet() }
    val openFolder = state.folder
    // Back from a folder or a label goes up to the top, as in a file manager.
    BackHandler(enabled = selection.isEmpty() && state.filter != ReviewFilter.All) { viewModel.setFilter(ReviewFilter.All) }
    var folderMenu by remember { mutableStateOf<ReviewFolderDto?>(null) }
    folderMenu?.let { folder ->
        FolderActions(
            folder = folder,
            onRename = { folderMenu = null; naming = Naming.Rename(folder) },
            onDelete = { folderMenu = null; viewModel.deleteFolder(folder) },
            onDismiss = { folderMenu = null },
        )
    }

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
            title = stringResource(if (task is Naming.Rename) R.string.folder_rename else R.string.folder_new),
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
            Column {
            TopAppBar(
                title = {
                    Text(
                        when {
                            selection.isNotEmpty() -> pluralStringResource(R.plurals.selection_count, selection.size, selection.size)
                            openFolder != null -> openFolder.name
                            else -> stringResource(R.string.review_title)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (selection.isNotEmpty()) {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.selection_clear))
                        }
                    } else if (openFolder != null) {
                        IconButton(onClick = { viewModel.setFilter(ReviewFilter.All) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.folder_back))
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
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = stringResource(R.string.folder_move))
                        }
                        IconButton(onClick = { labelling = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = stringResource(R.string.review_labels))
                        }
                        IconButton(onClick = { deleting = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.drafts_delete))
                        }
                    } else if (openFolder != null) {
                        IconButton(onClick = { naming = Naming.Rename(openFolder) }) {
                            Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = stringResource(R.string.folder_rename))
                        }
                        IconButton(onClick = { viewModel.deleteFolder(openFolder); viewModel.setFilter(ReviewFilter.All) }) {
                            Icon(Icons.Outlined.FolderDelete, contentDescription = stringResource(R.string.folder_delete))
                        }
                    } else {
                        IconButton(onClick = { naming = Naming.Create() }) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(R.string.folder_new))
                        }
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
                title = stringResource(R.string.review_loading),
                detail = stringResource(R.string.server_loading_detail),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        val atTop = state.filter == ReviewFilter.All
        val folderTiles = if (atTop) state.folders else emptyList()
        LazyVerticalGrid(
            columns = TileColumns,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.labels.isNotEmpty()) {
                item(key = "labels", span = { GridItemSpan(maxLineSpan) }) { Labels(state, viewModel::setFilter) }
            }
            state.error?.takeIf { it != "client.unreachable" }?.let { code ->
                item(span = { GridItemSpan(maxLineSpan) }) { Notice(Icons.Outlined.Warning, ServerMessages.describe(resources, code)) }
            }
            if (state.shown.isEmpty() && folderTiles.isEmpty() && state.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Notice(Icons.Outlined.TaskAlt, stringResource(if (atTop) R.string.review_empty else R.string.review_empty_filtered))
                }
            }
            items(folderTiles, key = { "folder-${it.id}" }) { folder ->
                val inside = state.postsIn(folder)
                FolderTile(
                    name = folder.name,
                    count = inside.size,
                    thumbnailUrls = inside.mapNotNull { p -> p.media.firstOrNull()?.let { viewModel.thumbnailUrl(it.asset.id) } },
                    onClick = { selection = emptySet(); viewModel.setFilter(ReviewFilter.Folder(folder.id)) },
                    onLongClick = { folderMenu = folder },
                )
            }
            if (folderTiles.isNotEmpty() && state.shown.isNotEmpty()) {
                item(key = "unsorted", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.folder_not_in_any),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
            }
            items(state.shown, key = { it.id }) { post ->
                ReviewTile(
                    post = post,
                    channel = state.channels.firstOrNull { it.id == post.channelId },
                    // Inside a folder its name is in the title already.
                    folder = state.folders.firstOrNull { it.id == post.reviewFolderId }?.name?.takeIf { state.filter is ReviewFilter.Label },
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

/** The labels, as chips; one picked shows its posts from every folder. */
@Composable
private fun Labels(state: ReviewState, onFilter: (ReviewFilter) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.labels.forEach { label ->
            val on = state.filter == ReviewFilter.Label(label.name)
            FilterChip(
                selected = on,
                onClick = { onFilter(if (on) ReviewFilter.All else ReviewFilter.Label(label.name)) },
                label = { Text("${label.name} · ${label.uses}") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/** What a long press on a folder tile offers. */
@Composable
private fun FolderActions(folder: ReviewFolderDto, onRename: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.name) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.folder_rename)) },
                    leadingContent = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onRename),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.folder_delete)) },
                    leadingContent = { Icon(Icons.Outlined.FolderDelete, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onDelete),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.delete_back)) } },
    )
}

@Composable
private fun ReviewTile(
    post: PostDto,
    channel: ChannelDto?,
    folder: String?,
    thumbnailUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    PostTile(
        thumbnailUrl = thumbnailUrl,
        text = post.finalText.orEmpty().ifBlank { stringResource(R.string.drafts_no_text) },
        caption = listOfNotNull(channel?.label, folder).joinToString(" · "),
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
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

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
    }
}
