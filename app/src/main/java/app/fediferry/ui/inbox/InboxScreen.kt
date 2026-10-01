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
package app.fediferry.ui.inbox

import kotlinx.coroutines.launch
import app.fediferry.work.ScheduleFormat
import app.fediferry.ui.SchedulePicker
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.data.model.InboxStack
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.template.TemplateEngine
import app.fediferry.ui.Space
import app.fediferry.ui.SpaceBar
import coil3.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenItem: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchSpace: (Space) -> Unit = {},
    viewModel: InboxViewModel = viewModel(),
) {
    val items by viewModel.items.collectAsState()
    val sections by viewModel.sections.collectAsState()
    var selection by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selection.isNotEmpty()
    // New folds too, but it is not a stored stack; remembered for the session.
    var newCollapsed by rememberSaveable { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<StackNaming?>(null) }
    var scheduling by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    scheduling?.let { id ->
        SchedulePicker(
            title = "Schedule post",
            confirmLabel = "Schedule",
            initial = ScheduleFormat.suggested(),
            onPick = { at ->
                viewModel.schedule(id, at)
                scheduling = null
                selection = emptySet()
                scope.launch {
                    snackbar.showSnackbar("Handing it to Mastodon to post ${ScheduleFormat.whenText(at)}. It'll show in Queue.")
                }
            },
            onDismiss = { scheduling = null },
        )
    }

    if (moving) {
        MoveSheet(
            stacks = sections.mapNotNull { it.stack },
            onPick = { stackId ->
                viewModel.move(selection, stackId)
                selection = emptySet()
                moving = false
            },
            onNewStack = {
                moving = false
                naming = StackNaming.Create(moveIds = selection)
            },
            onDismiss = { moving = false },
        )
    }
    naming?.let { task ->
        StackNameDialog(
            title = if (task is StackNaming.Rename) "Rename stack" else "New stack",
            initial = (task as? StackNaming.Rename)?.stack?.name.orEmpty(),
            onConfirm = { name ->
                when (task) {
                    is StackNaming.Create -> {
                        viewModel.createStack(name, task.moveIds)
                        selection = emptySet()
                    }
                    is StackNaming.Rename -> viewModel.renameStack(task.stack, name)
                }
                naming = null
            },
            onDismiss = { naming = null },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { SpaceBar(Space.INBOX, onSwitchSpace) },
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selection.size} selected" else "Inbox") },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        // One post at a time: posts are never sent as a batch.
                        val single = selection.singleOrNull()?.let { id -> items.firstOrNull { it.id == id } }
                        if (single != null && InboxViewModel.canPost(single)) {
                            IconButton(onClick = { scheduling = single.id }) {
                                Icon(Icons.Default.Schedule, contentDescription = "Schedule post")
                            }
                        }
                        IconButton(onClick = { moving = true }) {
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = "Move to stack")
                        }
                        IconButton(onClick = {
                            viewModel.delete(selection)
                            selection = emptySet()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    } else {
                        IconButton(onClick = { naming = StackNaming.Create() }) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New stack")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val hasStacks = sections.any { it.stack != null }
        if (items.isEmpty() && !hasStacks) {
            EmptyInbox(Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { section ->
                    val stack = section.stack
                    // With no stacks at all, the inbox looks as it always has.
                    if (stack == null && !hasStacks) {
                        items(section.items, key = { it.id }) { item ->
                            ItemCard(
                                item = item,
                                selected = item.id in selection,
                                onClick = { if (selecting) selection = selection.toggle(item.id) else onOpenItem(item.id) },
                                onLongClick = { selection = selection.toggle(item.id) },
                            )
                        }
                        return@forEach
                    }
                    val collapsed = stack?.collapsed ?: newCollapsed
                    item(key = "header-${stack?.id ?: "new"}", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(
                            title = stack?.name ?: "New",
                            count = section.items.size,
                            collapsed = collapsed,
                            onToggle = {
                                if (stack == null) newCollapsed = !newCollapsed
                                else viewModel.setCollapsed(stack, !collapsed)
                            },
                            menu = stack?.let {
                                StackMenu(
                                    onRename = { naming = StackNaming.Rename(it) },
                                    onDelete = { viewModel.deleteStack(it) },
                                )
                            },
                        )
                    }
                    if (collapsed) return@forEach
                    if (section.items.isEmpty()) {
                        item(key = "empty-${stack?.id ?: "new"}", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                if (stack == null) {
                                    "Nothing new. Shared posts land here."
                                } else {
                                    "Empty. Select posts with a long press, then move them here."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                            )
                        }
                    }
                    items(section.items, key = { it.id }) { item ->
                        ItemCard(
                            item = item,
                            selected = item.id in selection,
                            onClick = { if (selecting) selection = selection.toggle(item.id) else onOpenItem(item.id) },
                            onLongClick = { selection = selection.toggle(item.id) },
                        )
                    }
                }
            }
        }
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(
    item: Item,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                // A theme colour, not 6 % black: that vanished on a dark background.
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (item.mediaPath != null) {
                AsyncImage(
                    model = File(item.mediaPath),
                    contentDescription = item.altText ?: "Shared image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("Text only", style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(item.status)
            }
            Text(
                text = TemplateEngine.postTextOf(item).ifBlank { "(no text)" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            item.failureReason?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: Status) {
    val label = when (status) {
        Status.DRAFT -> "Draft"
        Status.QUEUED -> "Queued"
        Status.POSTING -> "Sending"
        Status.POSTED -> "Posted"
        Status.FAILED -> "Failed"
    }
    // A label, not a disabled chip: disabled content is drawn at 38 % opacity
    // by design, which is exactly what made these hard to read. Failed is the
    // one warning, and so the one in the error colours.
    val (container, content) = when (status) {
        Status.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        Status.POSTED -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun EmptyInbox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing saved yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Screenshot a meme, share it here, and pick Post now, Compose, " +
                    "or Save for later.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** What the stack-name dialog is for. */
private sealed interface StackNaming {
    /** A new stack, with [moveIds] put on it once it exists. */
    data class Create(val moveIds: Set<String> = emptySet()) : StackNaming
    data class Rename(val stack: InboxStack) : StackNaming
}

/** The actions on one stack, offered from its header. */
private class StackMenu(
    val onRename: () -> Unit,
    val onDelete: () -> Unit,
)

/** A section's name, how many posts it holds, and a fold arrow; a stack adds its menu. */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    menu: StackMenu?,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = if (collapsed) "Open $title" else "Fold $title", onClick = onToggle)
            .padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "  $count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (menu != null) {
            Box {
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Stack actions for $title")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; menu.onRename() })
                    DropdownMenuItem(
                        text = { Text("Delete stack — posts go back to New") },
                        onClick = { open = false; menu.onDelete() },
                    )
                }
            }
        }
    }
}

/** Where to put the selected posts: a stack, back to New, or a stack made on the spot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveSheet(
    stacks: List<InboxStack>,
    onPick: (String?) -> Unit,
    onNewStack: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Move to",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("New") },
                supportingContent = { Text("On no stack") },
                leadingContent = { Icon(Icons.Outlined.Inbox, contentDescription = null) },
                modifier = Modifier.clickable { onPick(null) },
            )
            stacks.forEach { stack ->
                ListItem(
                    headlineContent = { Text(stack.name) },
                    leadingContent = { Icon(Icons.Outlined.Layers, contentDescription = null) },
                    modifier = Modifier.clickable { onPick(stack.id) },
                )
            }
            ListItem(
                headlineContent = { Text("New stack…") },
                leadingContent = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onNewStack),
            )
        }
    }
}

@Composable
private fun StackNameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Ready to post") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(if (initial.isEmpty()) "Create" else "Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

