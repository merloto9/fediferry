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

import androidx.compose.ui.platform.LocalResources
import app.fediferry.work.ScheduleWords
import app.fediferry.R
import androidx.compose.ui.res.stringResource
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
import app.fediferry.ui.LoadingScreen
import app.fediferry.ui.Space
import app.fediferry.ui.SpaceBar
import coil3.compose.AsyncImage

/**
 * What Mastodon will post later, read live from the server. FediFerry keeps
 * no copy of the schedule, so this is the one place to see or change it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    onOpenPost: (QueuedPost) -> Unit,
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
        ChangeTimeDialog(post, onPick = { at -> viewModel.reschedule(post, at); moving = null }, onDismiss = { moving = null })
    }
    cancelling?.let { post ->
        CancelPostDialog(onConfirm = { viewModel.cancel(post); cancelling = null }, onDismiss = { cancelling = null })
    }
    drafting?.let { post ->
        MakeDraftDialog(onMake = { cancel -> viewModel.makeDraft(post, cancel); drafting = null }, onDismiss = { drafting = null })
    }
    if (state.drafting) DraftingOverlay()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { SpaceBar(Space.QUEUE, onSwitchSpace) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue_title)) },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.queue_refresh))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.queue_settings))
                    }
                },
            )
        },
    ) { padding ->
        when {
            !state.loadedOnce -> LoadingScreen(
                title = stringResource(R.string.queue_loading_title),
                detail = stringResource(R.string.queue_loading_detail),
                modifier = Modifier.padding(padding),
                icon = Icons.Outlined.Schedule,
            )
            state.accounts.isEmpty() -> Message(
                title = stringResource(R.string.queue_no_account_title),
                detail = stringResource(R.string.queue_no_account_detail),
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
                    onOpen = onOpenPost,
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
    onOpen: (QueuedPost) -> Unit,
) {
    val resources = LocalResources.current
    val showAccount = state.accounts.size > 1
    val days = state.posts.groupBy { ScheduleWords.dayHeading(resources, it.at) }
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
                    title = stringResource(R.string.queue_empty_title),
                    detail = stringResource(R.string.queue_empty_detail),
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
                    onOpen = { onOpen(post) },
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
    onOpen: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val media = post.status.media.firstOrNull()
    val time = ScheduleWords.timeText(LocalResources.current, post.at)
    Card(
        onClick = onOpen,
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
                        contentDescription = media.description ?: stringResource(R.string.media_attached_picture),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(stringResource(R.string.queue_text_only), style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    time,
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
                        stringResource(R.string.queue_cw, it),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    post.status.params.text.ifBlank { stringResource(R.string.queue_no_text) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.queue_actions_for, time))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.queue_change_time)) }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.queue_make_draft)) }, onClick = { menu = false; onDraft() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.queue_cancel_post)) }, onClick = { menu = false; onCancel() })
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
                if (reconnect) {
                    stringResource(R.string.queue_reconnect_title, account.acct)
                } else {
                    stringResource(R.string.queue_read_failed_title, account.acct)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                when (problem) {
                    QueueProblem.NeedsReconnect -> stringResource(R.string.queue_reconnect_text)
                    is QueueProblem.Failed -> problem.reason
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (reconnect) {
                FilledTonalButton(onClick = { onReconnect(account) }) { Text(stringResource(R.string.queue_reconnect)) }
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
