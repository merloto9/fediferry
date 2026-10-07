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
package app.fediferry.ui.queue

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.data.model.Account
import app.fediferry.ui.LoadingOverlay
import app.fediferry.ui.LoadingScreen
import app.fediferry.ui.SchedulePicker
import app.fediferry.ui.Space
import app.fediferry.ui.SpaceBar
import app.fediferry.work.ScheduleFormat
import coil3.compose.AsyncImage

/**
 * What Mastodon will post later, read live from the server. FediFerry keeps
 * no copy of the schedule, so this is the one place to see or change it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    onOpenSettings: () -> Unit,
    onSwitchSpace: (Space) -> Unit,
    viewModel: QueueViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var moving by remember { mutableStateOf<QueuedPost?>(null) }
    var cancelling by remember { mutableStateOf<QueuedPost?>(null) }
    var drafting by remember { mutableStateOf<QueuedPost?>(null) }

    // Every visit reads afresh, including the return from a reconnect.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    moving?.let { post ->
        SchedulePicker(
            title = "Move to",
            confirmLabel = "Move",
            initial = post.at.coerceAtLeast(ScheduleFormat.suggested()),
            onPick = { at -> viewModel.reschedule(post, at); moving = null },
            onDismiss = { moving = null },
        )
    }
    cancelling?.let { post ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text("Cancel this post?") },
            text = {
                Text(
                    "Mastodon takes it out of the queue and won't post it. It can't be " +
                        "brought back afterwards.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.cancel(post); cancelling = null }) { Text("Cancel post") }
            },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text("Keep it") } },
        )
    }

    drafting?.let { post ->
        AlertDialog(
            onDismissRequest = { drafting = null },
            title = { Text("Make a draft?") },
            text = {
                Text(
                    "FediFerry copies this post — text, hashtags, picture and alt text — into a " +
                        "new draft in the inbox. Mastodon can't change a scheduled post's text or " +
                        "picture, so to edit it: make a draft, cancel this one, and schedule the " +
                        "draft again when it's ready.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { viewModel.makeDraft(post, cancelAfter = true); drafting = null }) {
                        Text("Make draft, cancel this")
                    }
                    TextButton(onClick = { viewModel.makeDraft(post, cancelAfter = false); drafting = null }) {
                        Text("Make draft, keep this")
                    }
                    TextButton(onClick = { drafting = null }) { Text("Back") }
                }
            },
        )
    }
    if (state.drafting) {
        LoadingOverlay(
            title = "Making a draft",
            detail = "Downloading the picture from your server and copying the post into the inbox.",
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { SpaceBar(Space.QUEUE, onSwitchSpace) },
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        when {
            !state.loadedOnce -> LoadingScreen(
                title = "Asking Mastodon",
                detail = "Reading the posts your server will publish later. FediFerry keeps no " +
                    "copy, so this list always comes fresh from there.",
                modifier = Modifier.padding(padding),
                icon = Icons.Outlined.Schedule,
            )
            state.accounts.isEmpty() -> Message(
                title = "No account connected",
                detail = "Connect a Mastodon account in Settings to schedule posts.",
                modifier = Modifier.padding(padding),
            )
            else -> PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                QueueList(
                    state = state,
                    onReconnect = viewModel::reconnect,
                    onMove = { moving = it },
                    onCancel = { cancelling = it },
                    onDraft = { drafting = it },
                )
            }
        }
    }
}

@Composable
private fun QueueList(
    state: QueueState,
    onReconnect: (Account) -> Unit,
    onMove: (QueuedPost) -> Unit,
    onCancel: (QueuedPost) -> Unit,
    onDraft: (QueuedPost) -> Unit,
) {
    val showAccount = state.accounts.size > 1
    val days = state.posts.groupBy { ScheduleFormat.dayHeading(it.at) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.problems.forEach { (account, problem) ->
            item(key = "problem-${account.id}") { ProblemCard(account, problem, onReconnect) }
        }
        if (state.posts.isEmpty() && state.problems.size < state.accounts.size) {
            item(key = "empty") {
                Message(
                    title = "Nothing scheduled",
                    detail = "To have Mastodon post something later, long-press it in the inbox " +
                        "and tap the clock. Mastodon keeps the queue, so FediFerry needn't be " +
                        "open when the post goes out.",
                )
            }
        }
        days.forEach { (day, posts) ->
            item(key = "day-$day") {
                Text(
                    day,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
            items(posts, key = { "${it.account.id}/${it.status.id}" }) { post ->
                QueuedCard(
                    post,
                    showAccount,
                    onMove = { onMove(post) },
                    onCancel = { onCancel(post) },
                    onDraft = { onDraft(post) },
                )
            }
        }
    }
}

@Composable
private fun QueuedCard(
    post: QueuedPost,
    showAccount: Boolean,
    onMove: () -> Unit,
    onCancel: () -> Unit,
    onDraft: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val media = post.status.media.firstOrNull()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (media != null) {
                    AsyncImage(
                        model = media.previewUrl ?: media.url,
                        contentDescription = media.description ?: "Attached picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("Text", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    ScheduleFormat.timeText(post.at),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (showAccount) {
                    Text(
                        "@${post.account.acct}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                post.status.params.spoilerText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "CW: $it",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    post.status.params.text.ifBlank { "(no text)" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions for the post at ${ScheduleFormat.timeText(post.at)}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Change time") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Make a draft") }, onClick = { menu = false; onDraft() })
                    DropdownMenuItem(text = { Text("Cancel post") }, onClick = { menu = false; onCancel() })
                }
            }
        }
    }
}

@Composable
private fun ProblemCard(account: Account, problem: QueueProblem, onReconnect: (Account) -> Unit) {
    val reconnect = problem is QueueProblem.NeedsReconnect
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (reconnect) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (reconnect) "Reconnect @${account.acct}" else "Couldn't read @${account.acct}'s queue",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                when (problem) {
                    QueueProblem.NeedsReconnect ->
                        "To show the posts Mastodon will publish later, FediFerry needs permission " +
                            "to read them, which this account didn't give when it was connected. " +
                            "Scheduling works without it."
                    is QueueProblem.Failed -> problem.reason
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (reconnect) {
                FilledTonalButton(onClick = { onReconnect(account) }) { Text("Reconnect") }
            }
        }
    }
}

@Composable
private fun Message(title: String, detail: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
