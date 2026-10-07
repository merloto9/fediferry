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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import app.fediferry.api.PostStages
import app.fediferry.data.model.Visibility
import app.fediferry.drafts.ServerMessages
import app.fediferry.library.FolderPicker
import app.fediferry.ui.DeletePostsDialog
import app.fediferry.ui.FullscreenImage
import app.fediferry.ui.label
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

/** One ready post, exactly as it will go out, with what can still change about it: where it is sorted. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewPostScreen(
    postId: String,
    onBack: () -> Unit,
    onOpenDraft: (String) -> Unit,
    viewModel: ReviewPostViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var deleting by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(false) }
    var labelling by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf<Pair<String?, String?>?>(null) }

    LaunchedEffect(postId) { viewModel.load(postId) }
    LifecycleResumeEffect(postId) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(ServerMessages.describe(resources, it))
            viewModel.clearMessage()
        }
    }
    if (deleting) DeletePostsDialog(count = 1, onConfirm = { deleting = false; viewModel.delete(onBack) }, onDismiss = { deleting = false })
    fullScreen?.let { (url, alt) -> FullscreenImage(url, alt, onDismiss = { fullScreen = null }) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_post_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.queue_back))
                    }
                },
                actions = {
                    IconButton(onClick = { deleting = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.drafts_delete))
                    }
                },
            )
        },
    ) { padding ->
        val post = state.post
        if (post == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loaded) Text(stringResource(R.string.draft_gone)) else CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (folders) {
            FolderPicker(
                folders = state.folders.map { it.id to it.name },
                onPick = { viewModel.setFolder(it); folders = false },
                onNewFolder = null,
                onDismiss = { folders = false },
            )
        }
        if (labelling) {
            LabelSheet(
                known = (state.labels.map { it.name } + post.labels).distinctBy { it.lowercase() }.sortedBy { it.lowercase() },
                onAll = post.labels,
                onToggle = viewModel::setLabel,
                onDismiss = { labelling = false },
            )
        }

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (post.stage != PostStages.READY) {
                Text(stringResource(R.string.review_not_ready_any_more), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            post.media.forEach { media ->
                val url = viewModel.mediaUrl(media.asset.id)
                AsyncImage(
                    model = url,
                    contentDescription = media.altText ?: stringResource(R.string.media_shared_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = stringResource(R.string.media_show_full_screen)) { fullScreen = url to media.altText },
                )
                Text(
                    media.altText?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.review_alt, it) } ?: stringResource(R.string.review_no_alt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            post.contentWarning?.takeIf { it.isNotBlank() }?.let { cw ->
                Text(stringResource(R.string.review_cw, cw), style = MaterialTheme.typography.labelLarge)
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Text(post.finalText.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(12.dp))
            }
            Detail(stringResource(R.string.review_channel), state.channel?.let { "${it.name} · @${it.acct}" } ?: "—")
            Detail(stringResource(R.string.options_visibility), (Visibility.entries.firstOrNull { it.name == post.visibility } ?: Visibility.PUBLIC).label)
            post.readyAt?.let { Detail(stringResource(R.string.review_ready_since), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { folders = true },
                    label = { Text(state.folders.firstOrNull { it.id == post.reviewFolderId }?.name ?: stringResource(R.string.library_unsorted)) },
                    leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { labelling = true },
                    label = { Text(post.labels.joinToString(", ").ifEmpty { stringResource(R.string.review_labels) }) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }

            if (post.stage == PostStages.READY) {
                OutlinedButton(onClick = { viewModel.backToDraft(onOpenDraft) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.EditNote, contentDescription = null)
                    Text(stringResource(R.string.review_back_to_draft), modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.review_back_to_draft_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
