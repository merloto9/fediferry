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
package app.fediferry.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.fediferry.api.FolderDto
import app.fediferry.api.LibraryItemDto
import app.fediferry.api.LibraryItemPatch
import app.fediferry.api.LibraryKinds
import app.fediferry.data.model.ContentSource
import app.fediferry.ui.FullscreenImage
import app.fediferry.ui.LoadingScreen
import app.fediferry.ui.Space
import app.fediferry.ui.SpaceBar
import coil3.compose.AsyncImage
import java.io.File

/**
 * The project's library on the FediFerry server: everything shared or
 * uploaded, newest first, in folders and with tags. Shares not yet uploaded
 * wait at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenSettings: () -> Unit,
    onSwitchSpace: (Space) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var opened by remember { mutableStateOf<LibraryItemDto?>(null) }
    var naming by remember { mutableStateOf<FolderNaming?>(null) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.start()
        onPauseOrDispose { viewModel.stop() }
    }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(resources.getString(R.string.library_action_failed, it)) } }
    // Keep the open sheet in step with changes from other phones.
    val shown = opened?.let { o -> state.items.firstOrNull { it.id == o.id } ?: o }

    shown?.let { item ->
        ItemSheet(
            item = item,
            folders = state.folders,
            mediaUrl = item.asset?.let { viewModel.mediaUrl(it.id) },
            onPatch = { viewModel.patch(item.id, it) },
            onDelete = { viewModel.delete(listOf(item.id)); opened = null },
            onDismiss = { opened = null },
        )
    }
    naming?.let { task ->
        NameDialog(
            title = stringResource(if (task is FolderNaming.Rename) R.string.library_rename_folder else R.string.library_new_folder),
            initial = (task as? FolderNaming.Rename)?.folder?.name.orEmpty(),
            onConfirm = { name ->
                when (task) {
                    is FolderNaming.Create -> viewModel.createFolder(name, task.moveIds).also { selection = emptySet() }
                    is FolderNaming.Rename -> viewModel.renameFolder(task.folder, name)
                }
                naming = null
            },
            onDismiss = { naming = null },
        )
    }
    if (moving) {
        FolderPicker(
            folders = state.folders,
            onPick = { folderId -> viewModel.move(selection, folderId); selection = emptySet(); moving = false },
            onNewFolder = { moving = false; naming = FolderNaming.Create(selection) },
            onDismiss = { moving = false },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(pluralStringResource(R.plurals.library_delete_title, selection.size, selection.size)) },
            text = { Text(stringResource(R.string.library_delete_text)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(selection); selection = emptySet(); deleting = false }) {
                    Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.delete_back)) } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { SpaceBar(Space.LIBRARY, onSwitchSpace) },
        topBar = {
            TopAppBar(
                title = {
                    if (selection.isNotEmpty()) {
                        Text(pluralStringResource(R.plurals.library_selected, selection.size, selection.size))
                    } else {
                        Column {
                            Text(stringResource(R.string.library_title))
                            state.connection?.let {
                                Text(it.projectName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
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
                        IconButton(onClick = { moving = true }) {
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = stringResource(R.string.library_move))
                        }
                        IconButton(onClick = { deleting = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.library_delete))
                        }
                    } else {
                        IconButton(onClick = { searching = !searching; if (!searching) viewModel.setQuery("") }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.library_search))
                        }
                        IconButton(onClick = { naming = FolderNaming.Create() }) {
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
                title = stringResource(R.string.library_loading_title),
                detail = stringResource(R.string.library_loading_detail),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (searching) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    var query by rememberSaveable { mutableStateOf(state.query) }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; viewModel.setQuery(it) },
                        label = { Text(stringResource(R.string.library_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Filters(state, onFilter = viewModel::setFilter, onRename = { naming = FolderNaming.Rename(it) }, onDeleteFolder = viewModel::deleteFolder)
            }
            state.error?.let { code ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Notice(
                        Icons.Outlined.Warning,
                        if (code == "client.unreachable") stringResource(R.string.library_offline) else stringResource(R.string.library_unreachable, code),
                    )
                }
            }
            if (pending.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PendingRow(pending, onRetry = viewModel::retryUploads, onDiscard = viewModel::discardPending)
                }
            }
            if (state.items.isEmpty() && state.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Notice(Icons.Outlined.Inbox, stringResource(if (state.filter == LibraryFilter.All && state.query.isBlank()) R.string.library_empty else R.string.library_empty_filtered))
                }
            }
            items(state.items, key = { it.id }) { item ->
                ItemTile(
                    item = item,
                    thumbnailUrl = item.asset?.let { viewModel.thumbnailUrl(it.id) },
                    selected = item.id in selection,
                    onClick = { if (selection.isNotEmpty()) selection = selection.toggle(item.id) else opened = item },
                    onLongClick = { selection = selection.toggle(item.id) },
                )
            }
            if (state.nextBefore != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LaunchedEffect(state.nextBefore) { viewModel.loadMore() }
                }
            }
        }
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

private sealed interface FolderNaming {
    data class Create(val moveIds: Set<String> = emptySet()) : FolderNaming
    data class Rename(val folder: FolderDto) : FolderNaming
}

@Composable
private fun Filters(
    state: LibraryState,
    onFilter: (LibraryFilter) -> Unit,
    onRename: (FolderDto) -> Unit,
    onDeleteFolder: (FolderDto) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state.filter == LibraryFilter.All, { onFilter(LibraryFilter.All) }, { Text(stringResource(R.string.library_all)) })
            FilterChip(state.filter == LibraryFilter.Unsorted, { onFilter(LibraryFilter.Unsorted) }, { Text(stringResource(R.string.library_unsorted)) })
            state.folders.forEach { folder ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    FilterChip(
                        selected = state.filter == LibraryFilter.Folder(folder.id),
                        onClick = { onFilter(LibraryFilter.Folder(folder.id)) },
                        label = { Text(folder.name) },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = if (state.filter == LibraryFilter.Folder(folder.id)) {
                            { Text("⋯", modifier = Modifier.clickable { menu = true }) }
                        } else {
                            null
                        },
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_rename_folder)) }, onClick = { menu = false; onRename(folder) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_delete_folder)) }, onClick = { menu = false; onDeleteFolder(folder) })
                    }
                }
            }
        }
        if (state.tags.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.tags.forEach { tag ->
                    val on = state.filter == LibraryFilter.Tag(tag.name)
                    FilterChip(on, { onFilter(if (on) LibraryFilter.All else LibraryFilter.Tag(tag.name)) }, { Text("#${tag.name} · ${tag.uses}") })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemTile(item: LibraryItemDto, thumbnailUrl: String?, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = item.title ?: stringResource(R.string.media_shared_image),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(if (selected) 6.dp else 0.dp).clip(RoundedCornerShape(if (selected) 8.dp else 0.dp)),
                )
            } else {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Link, contentDescription = null)
                    Text(
                        item.title ?: item.sourceUrl ?: item.text.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.linkMayIdentify) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = stringResource(R.string.library_link_may_identify),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun PendingRow(pending: List<PendingShare>, onRetry: () -> Unit, onDiscard: (PendingShare) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { app.fediferry.di.ServiceLocator.pendingShares(context) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                Text(
                    pluralStringResource(R.plurals.library_pending, pending.size, pending.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.library_retry)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pending.forEach { share ->
                    var menu by remember { mutableStateOf(false) }
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)
                            .clickable { menu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (share.hasFile) {
                            AsyncImage(model = File(store.file(share).path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Outlined.Link, contentDescription = null)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            share.lastError?.let { DropdownMenuItem(text = { Text(stringResource(R.string.library_pending_error, it)) }, onClick = { menu = false }, enabled = false) }
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_pending_discard)) }, onClick = { menu = false; onDiscard(share) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemSheet(
    item: LibraryItemDto,
    folders: List<FolderDto>,
    mediaUrl: String?,
    onPatch: (LibraryItemPatch) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var fullScreen by remember { mutableStateOf(false) }
    var title by remember(item.id) { mutableStateOf(item.title.orEmpty()) }
    var newTag by remember(item.id) { mutableStateOf("") }
    var folderMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (fullScreen && mediaUrl != null) FullscreenImage(mediaUrl, item.title, onDismiss = { fullScreen = false })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.library_delete_title, 1, 1)) },
            text = { Text(stringResource(R.string.library_delete_text)) },
            confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.delete_back)) } },
        )
    }

    ModalBottomSheet(onDismissRequest = {
        if (title.trim() != item.title.orEmpty()) onPatch(LibraryItemPatch(title = title))
        onDismiss()
    }) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (mediaUrl != null) {
                AsyncImage(
                    model = mediaUrl,
                    contentDescription = item.title ?: stringResource(R.string.media_shared_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = stringResource(R.string.media_show_full_screen)) { fullScreen = true },
                )
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.library_item_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            item.text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            item.sourceUrl?.let { url ->
                Column {
                    Text(
                        item.origin?.let { ContentSource.fromName(it)?.label } ?: stringResource(R.string.library_link),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(url, style = MaterialTheme.typography.bodySmall)
                    if (item.linkMayIdentify) {
                        Text(stringResource(R.string.library_link_may_identify), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item.sourceFields.filterKeys { it != "title" }.forEach { (key, value) ->
                Text("$key: $value", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }

            Text(stringResource(R.string.library_tags), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { onPatch(LibraryItemPatch(tags = item.tags - tag)) },
                        label = { Text("#$tag") },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.library_remove_tag, tag), modifier = Modifier.size(16.dp)) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it.replace(" ", "") },
                    label = { Text(stringResource(R.string.library_add_tag)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onPatch(LibraryItemPatch(tags = item.tags + newTag.trim().removePrefix("#"))); newTag = "" },
                    enabled = newTag.isNotBlank(),
                ) { Text(stringResource(R.string.library_add)) }
            }

            Box {
                AssistChip(
                    onClick = { folderMenu = true },
                    label = { Text(folders.firstOrNull { it.id == item.folderId }?.name ?: stringResource(R.string.library_unsorted)) },
                    leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_unsorted)) }, onClick = { folderMenu = false; onPatch(LibraryItemPatch(folderId = "")) })
                    folders.forEach { f ->
                        DropdownMenuItem(text = { Text(f.name) }, onClick = { folderMenu = false; onPatch(LibraryItemPatch(folderId = f.id)) })
                    }
                }
            }
            TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPicker(folders: List<FolderDto>, onPick: (String?) -> Unit, onNewFolder: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.library_move_to), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.library_unsorted)) },
                leadingContent = { Icon(Icons.Outlined.Inbox, contentDescription = null) },
                modifier = Modifier.clickable { onPick(null) },
            )
            folders.forEach { f ->
                ListItem(
                    headlineContent = { Text(f.name) },
                    leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    modifier = Modifier.clickable { onPick(f.id) },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.library_new_folder_more)) },
                leadingContent = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onNewFolder),
            )
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.library_folder_name)) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.library_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.delete_back)) } },
    )
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
    }
}
