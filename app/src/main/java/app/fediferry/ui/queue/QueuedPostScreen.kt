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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import app.fediferry.data.ScheduledDraft
import app.fediferry.data.model.Visibility
import app.fediferry.mastodon.MediaAttachment
import app.fediferry.ui.FullscreenImage
import app.fediferry.ui.LoadingScreen
import app.fediferry.work.ScheduleWords
import coil3.compose.AsyncImage

/**
 * Everything about one post Mastodon holds for later: its pictures (tap for
 * full screen), text, when it goes out, from which account and to whom, with
 * the same actions as its menu in the Queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueuedPostScreen(
    accountId: String,
    statusId: String,
    onBack: () -> Unit,
    viewModel: QueuedPostViewModel = viewModel(),
) {
    val load by viewModel.load.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var moving by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var drafting by remember { mutableStateOf(false) }

    LaunchedEffect(accountId, statusId) { viewModel.load(accountId, statusId) }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.closed.collect { onBack() } }

    val post = (load as? QueuedPostViewModel.Load.Loaded)?.post
    if (post != null) {
        if (moving) ChangeTimeDialog(post, onPick = { viewModel.reschedule(post, it); moving = false }, onDismiss = { moving = false })
        if (cancelling) CancelPostDialog(onConfirm = { viewModel.cancel(post); cancelling = false }, onDismiss = { cancelling = false })
        if (drafting) MakeDraftDialog(onMake = { viewModel.makeDraft(post, it); drafting = false }, onDismiss = { drafting = false })
    }
    if (busy) DraftingOverlay()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue_post_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.queue_back))
                    }
                },
            )
        },
    ) { padding ->
        when (val state = load) {
            QueuedPostViewModel.Load.Loading -> LoadingScreen(
                title = stringResource(R.string.queue_post_loading_title),
                detail = stringResource(R.string.queue_post_loading_detail),
                modifier = Modifier.padding(padding),
                icon = Icons.Outlined.Schedule,
            )
            QueuedPostViewModel.Load.Gone -> Notice(
                stringResource(R.string.queue_post_gone_title),
                stringResource(R.string.queue_post_gone_detail),
                Modifier.padding(padding),
            )
            is QueuedPostViewModel.Load.Failed -> Notice(
                stringResource(R.string.queue_read_post_failed),
                state.reason,
                Modifier.padding(padding),
            )
            is QueuedPostViewModel.Load.Loaded -> PostDetails(
                post = state.post,
                onMove = { moving = true },
                onDraft = { drafting = true },
                onCancel = { cancelling = true },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun PostDetails(
    post: QueuedPost,
    onMove: () -> Unit,
    onDraft: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val params = post.status.params
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        post.status.media.forEach { Picture(it) }

        Detail(stringResource(R.string.queue_detail_when), ScheduleWords.fullText(LocalResources.current, post.at))
        Detail(
            stringResource(R.string.queue_detail_account),
            "${post.account.displayName}\n@${post.account.acct}@${post.account.instance}",
        )
        Detail(stringResource(R.string.queue_detail_visibility), visibilityLabel(params.visibility))
        params.spoilerText?.takeIf { it.isNotBlank() }?.let {
            Detail(stringResource(R.string.queue_detail_cw), it)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(stringResource(R.string.queue_detail_text))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        params.text.ifBlank { stringResource(R.string.queue_no_text) },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onMove, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.queue_change_time))
            }
            OutlinedButton(onClick = onDraft, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.queue_make_draft))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.queue_cancel_post))
            }
        }
    }
}

/** One attached picture: tap for full screen; its alt text below, or a note that it has none. */
@Composable
private fun Picture(media: MediaAttachment) {
    var fullScreen by remember { mutableStateOf(false) }
    // A video's still: Mastodon's preview is an image, the file itself is not.
    val model = if (media.type == null || media.type == "image") media.url ?: media.previewUrl else media.previewUrl ?: media.url
    if (fullScreen) FullscreenImage(model, media.description, onDismiss = { fullScreen = false })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = model,
                contentDescription = media.description ?: stringResource(R.string.media_attached_picture),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clickable(onClickLabel = stringResource(R.string.media_show_full_screen)) { fullScreen = true },
            )
        }
        Detail(
            stringResource(R.string.queue_detail_alt),
            media.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.queue_detail_no_alt),
        )
    }
}

@Composable
private fun visibilityLabel(api: String?): String = stringResource(
    when (ScheduledDraft.visibilityOf(api)) {
        Visibility.PUBLIC, null -> R.string.queue_visibility_public
        Visibility.UNLISTED -> R.string.queue_visibility_unlisted
        Visibility.PRIVATE -> R.string.queue_visibility_private
        Visibility.DIRECT -> R.string.queue_visibility_direct
    },
)

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(label)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Notice(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
