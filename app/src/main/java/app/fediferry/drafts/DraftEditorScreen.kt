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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import app.fediferry.api.CropRect
import app.fediferry.api.PostDto
import app.fediferry.api.PostMediaDto
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Visibility
import app.fediferry.di.ServiceLocator
import app.fediferry.mastodon.MastodonText
import app.fediferry.template.TemplateEngine
import app.fediferry.ui.ContentWarningField
import app.fediferry.ui.DeletePostsDialog
import app.fediferry.ui.FullscreenImage
import app.fediferry.ui.PlaceholderHelpDialog
import app.fediferry.ui.StableTextField
import app.fediferry.ui.VisibilityPicker
import app.fediferry.ui.crop.CropCanvas
import app.fediferry.ui.crop.NormalisedCrop
import app.fediferry.ui.editor.HashtagPanel
import app.fediferry.ui.typingInsets
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

/** Edits one draft on the server, while holding its edit lock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftEditorScreen(
    postId: String,
    onDone: () -> Unit,
    viewModel: DraftEditorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var deleting by remember { mutableStateOf(false) }
    var placeholderHelp by remember { mutableStateOf(false) }

    LaunchedEffect(postId) { viewModel.load(postId) }
    LaunchedEffect(state.message) {
        state.message?.let { (code, args) ->
            snackbar.showSnackbar(ServerMessages.describe(resources, code, args))
            viewModel.clearMessage()
        }
    }
    BackHandler { viewModel.close(onDone) }

    if (deleting) {
        DeletePostsDialog(count = 1, onConfirm = { deleting = false; viewModel.delete(onDone) }, onDismiss = { deleting = false })
    }
    if (placeholderHelp) PlaceholderHelpDialog(keys = state.placeholderKeys, onDismiss = { placeholderHelp = false })

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.typingInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.draft_title))
                        Text(
                            stringResource(if (state.unsaved) R.string.draft_saving else R.string.draft_saved),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.close(onDone) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_save_and_back))
                    }
                },
                actions = {
                    if (state.editable) {
                        IconButton(onClick = { deleting = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.drafts_delete))
                        }
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
        val editable = state.editable

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LockBanner(state.lock, onTakeOver = viewModel::takeOver, onRetry = viewModel::retry)
            if (state.mediaBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

            post.media.forEach { media ->
                Picture(
                    media = media,
                    state = state,
                    canRemove = post.media.size > 1,
                    onAltText = { viewModel.setAltText(media.position, it) },
                    onSuggestAlt = { viewModel.suggestAlt(media.position) },
                    onCrop = { viewModel.crop(media.position, it) },
                    onCleanUp = { viewModel.cleanUp(media.position, it) },
                    onUndo = { viewModel.undoEdit(media.position) },
                    onRemove = { viewModel.removePicture(media.position) },
                )
            }

            var showHashtags by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TemplatePicker(state, enabled = editable, onPick = viewModel::applyTemplate)
                TextButton(onClick = { showHashtags = !showHashtags }, enabled = editable) {
                    Text(stringResource(R.string.editor_adjust_hashtags))
                    Icon(
                        if (showHashtags) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(if (showHashtags) R.string.editor_close else R.string.editor_open),
                        modifier = Modifier.padding(start = 4.dp).size(18.dp),
                    )
                }
            }
            AnimatedVisibility(visible = showHashtags && editable) {
                HashtagPanel(
                    key = post.id,
                    picked = post.hashtags,
                    origin = ContentSource.fromName(post.origin),
                    addSourceHashtags = post.addSourceHashtags,
                    hashtagList = state.hashtagList,
                    onToggle = viewModel::toggleHashtag,
                    onAdd = viewModel::addHashtag,
                    sourceHashtags = viewModel.sourceHashtags(),
                    onAddSourceHashtags = viewModel::setAddSourceHashtags,
                    remembersNewHashtags = state.rememberSentHashtags,
                    uses = state.hashtagUses,
                )
            }

            StableTextField(
                key = post.id to editable,
                value = post.body,
                onValueChange = viewModel::setBody,
                label = stringResource(R.string.editor_post_text),
                minLines = 3,
                enabled = editable,
                supportingText = { Counter(post, state.channel?.capabilities?.maxCharacters) },
                trailingIcon = {
                    IconButton(onClick = { placeholderHelp = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.editor_placeholder_help))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            val link = post.sourceUrl
            if (post.linkMayIdentify && link != null && link in post.body) {
                LinkWarning(enabled = editable, onRemoveLink = viewModel::removeLink)
            }

            if (state.channel?.capabilities?.contentWarning != false) {
                ContentWarningField(
                    key = post.id to editable,
                    value = post.contentWarning.orEmpty(),
                    onValueChange = viewModel::setContentWarning,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            VisibilityPicker(
                selected = Visibility.entries.firstOrNull { it.name == post.visibility } ?: Visibility.PUBLIC,
                onSelect = { viewModel.setVisibility(it.name) },
                text = post.body,
            )

            ChannelPicker(state, enabled = editable, onPick = viewModel::setChannel)

            Button(onClick = { viewModel.close(onDone) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.draft_done))
            }
        }
    }
}

/** Why this phone cannot edit right now, and what to do about it. */
@Composable
private fun LockBanner(lock: EditLock, onTakeOver: () -> Unit, onRetry: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (confirming && lock is EditLock.Elsewhere) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.draft_take_over_title)) },
            text = { Text(stringResource(R.string.draft_take_over_text, lock.device.ifBlank { stringResource(R.string.draft_another_phone) })) },
            confirmButton = { TextButton(onClick = { confirming = false; onTakeOver() }) { Text(stringResource(R.string.draft_take_over)) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.delete_back)) } },
        )
    }
    val text = when (lock) {
        EditLock.Held -> return
        EditLock.Asking -> stringResource(R.string.draft_lock_asking)
        EditLock.Offline -> stringResource(R.string.draft_offline)
        is EditLock.Frozen -> stringResource(R.string.server_error_not_draft)
        is EditLock.Elsewhere -> if (lock.until > 0) {
            stringResource(
                R.string.draft_lock_elsewhere,
                lock.device.ifBlank { stringResource(R.string.draft_another_phone) },
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lock.until)),
            )
        } else {
            stringResource(R.string.draft_lock_lost)
        }
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            }
            if (lock is EditLock.Elsewhere || lock == EditLock.Offline) {
                Row {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.library_retry)) }
                    if (lock is EditLock.Elsewhere) TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.draft_take_over)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Picture(
    media: PostMediaDto,
    state: DraftEditorState,
    canRemove: Boolean,
    onAltText: (String) -> Unit,
    onSuggestAlt: () -> Unit,
    onCrop: (CropRect) -> Unit,
    onCleanUp: (String) -> Unit,
    onUndo: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val connections = remember { ServiceLocator.serverConnections(context) }
    val url = remember(media.asset.id) { connections.current()?.let { connections.client(it).mediaUrl(media.asset.id) } }
    var fullScreen by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(false) }
    val editable = state.editable
    val still = media.asset.mime.startsWith("image/") && media.asset.mime != "image/gif"

    if (fullScreen) FullscreenImage(url, media.altText, onDismiss = { fullScreen = false })
    if (cropping && url != null) {
        CropDialog(
            model = url,
            aspect = media.asset.width?.let { w -> media.asset.height?.takeIf { it > 0 }?.let { h -> w.toFloat() / h } } ?: 1f,
            onApply = { cropping = false; onCrop(it) },
            onDismiss = { cropping = false },
        )
    }

    AsyncImage(
        model = url,
        contentDescription = media.altText ?: stringResource(R.string.media_shared_image),
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = stringResource(R.string.media_show_full_screen)) { fullScreen = true },
    )
    if (editable) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (still) {
                TextButton(onClick = { cropping = true }, enabled = !state.mediaBusy) { Text(stringResource(R.string.editor_trim)) }
                if (state.profiles.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { profiles = true }, enabled = !state.mediaBusy) { Text(stringResource(R.string.editor_clean_up)) }
                        DropdownMenu(expanded = profiles, onDismissRequest = { profiles = false }) {
                            state.profiles.forEach { p ->
                                DropdownMenuItem(text = { Text(p.name) }, onClick = { profiles = false; onCleanUp(p.id) })
                            }
                        }
                    }
                }
            }
            if (media.asset.parentId != null) {
                TextButton(onClick = onUndo, enabled = !state.mediaBusy) { Text(stringResource(R.string.draft_undo_edit)) }
            }
            if (canRemove) TextButton(onClick = onRemove) { Text(stringResource(R.string.draft_remove_picture)) }
        }
    }
    val busy = media.position in state.altBusy
    StableTextField(
        key = Triple(media.asset.id, busy, editable),
        value = media.altText.orEmpty(),
        onValueChange = onAltText,
        label = stringResource(R.string.editor_alt_text),
        enabled = editable,
        minLines = 2,
        supportingText = {
            when {
                busy -> Text(stringResource(R.string.editor_alt_text_busy))
                media.altFailed -> Text(stringResource(R.string.editor_alt_text_failed), color = MaterialTheme.colorScheme.error)
                else -> Text(stringResource(R.string.editor_alt_text_hint))
            }
        },
        trailingIcon = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(12.dp))
            } else if (editable) {
                IconButton(onClick = onSuggestAlt) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.editor_regenerate_alt_text))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Crops on the phone's screen; the server makes the cropped picture from the rectangle. */
@Composable
private fun CropDialog(model: Any, aspect: Float, onApply: (CropRect) -> Unit, onDismiss: () -> Unit) {
    var crop by remember { mutableStateOf(NormalisedCrop()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
                Text(
                    stringResource(R.string.crop_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                CropCanvas(
                    model = model,
                    crop = crop,
                    onCropChange = { crop = it },
                    aspect = aspect,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.delete_back)) }
                    TextButton(onClick = { crop = NormalisedCrop() }) { Text(stringResource(R.string.crop_whole_image)) }
                    Button(
                        onClick = { onApply(CropRect(crop.left, crop.top, crop.right, crop.bottom)) },
                        enabled = !crop.isWholeImage,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.crop_use_this)) }
                }
            }
        }
    }
}

/**
 * The length as the channel counts it, `{tags}` filled in and the content
 * warning included, against what the channel takes.
 */
@Composable
private fun Counter(post: PostDto, max: Int?) {
    val length = MastodonText.length(TemplateEngine.finish(post.body, post.hashtags)) + post.contentWarning.orEmpty().length
    if (max == null) {
        Text(stringResource(R.string.draft_length, length))
    } else {
        val over = length > max
        Text(
            stringResource(R.string.draft_length_of, length, max),
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (over) FontWeight.Bold else null,
        )
    }
}

@Composable
private fun TemplatePicker(state: DraftEditorState, enabled: Boolean, onPick: (app.fediferry.data.model.Template) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) {
            Text(stringResource(R.string.editor_template, state.template?.name ?: "—"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.templates.forEach { template ->
                DropdownMenuItem(text = { Text(template.name) }, onClick = { open = false; onPick(template) })
            }
        }
    }
}

@Composable
private fun ChannelPicker(state: DraftEditorState, enabled: Boolean, onPick: (String) -> Unit) {
    if (state.channels.isEmpty()) {
        Text(stringResource(R.string.draft_no_channels), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) {
            Text(stringResource(R.string.draft_channel, state.channel?.let { "@${it.acct}" } ?: stringResource(R.string.draft_pick_channel)))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.channels.forEach { channel ->
                DropdownMenuItem(
                    text = { Text("${channel.name} · @${channel.acct}") },
                    onClick = { open = false; onPick(channel.id) },
                )
            }
        }
    }
}

/** The link may name whoever shared it; a warning, so in the warning colours. */
@Composable
private fun LinkWarning(enabled: Boolean, onRemoveLink: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.editor_link_warning_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.editor_link_warning_body), style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = onRemoveLink,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) { Text(stringResource(R.string.editor_remove_link), fontWeight = FontWeight.Bold) }
        }
    }
}
