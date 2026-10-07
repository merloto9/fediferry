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
package app.fediferry.ui.editor

import app.fediferry.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import app.fediferry.ui.FullscreenImage
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import app.fediferry.ui.typingInsets
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.data.model.AiModel
import app.fediferry.data.model.Item
import app.fediferry.ui.ContentWarningField
import app.fediferry.ui.ModelPicker
import app.fediferry.ui.PlaceholderHelpDialog
import app.fediferry.ui.StableTextField
import app.fediferry.ui.VisibilityPicker
import coil3.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    itemId: String,
    onDone: () -> Unit,
    onTrim: (String) -> Unit = {},
    onCleanUp: (String) -> Unit = {},
    viewModel: EditorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showPlaceholderHelp by remember { mutableStateOf(false) }

    if (showPlaceholderHelp) {
        PlaceholderHelpDialog(keys = state.placeholderKeys, onDismiss = { showPlaceholderHelp = false })
    }

    LaunchedEffect(itemId) { viewModel.load(itemId) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.typingInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.saveDraft(onDone) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_save_and_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.discard(onDone) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.editor_discard))
                    }
                },
            )
        },
    ) { padding ->
        val item = state.item
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loaded) Text(stringResource(R.string.editor_item_gone)) else CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item.mediaPath?.let { path ->
                var fullScreen by remember { mutableStateOf(false) }
                if (fullScreen) {
                    FullscreenImage(File(path), item.altText, onDismiss = { fullScreen = false })
                }
                AsyncImage(
                    model = File(path),
                    contentDescription = item.altText ?: stringResource(R.string.media_shared_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = stringResource(R.string.media_show_full_screen)) { fullScreen = true },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Both tools decode a still; a resolved animation is a video.
                    if (item.mimeType?.startsWith("video/") != true) {
                        TextButton(onClick = { onTrim(item.id) }) { Text(stringResource(R.string.editor_trim)) }
                        TextButton(onClick = { onCleanUp(item.id) }) { Text(stringResource(R.string.editor_clean_up)) }
                    }
                    if (item.originalMediaPath != null) {
                        TextButton(onClick = viewModel::revertEdits) { Text(stringResource(R.string.editor_undo_edits)) }
                    }
                }
            }

            var showHashtags by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TemplatePicker(state, viewModel)
                // Words rather than a bare "#": the icon alone did not say what it opened.
                TextButton(onClick = { showHashtags = !showHashtags }) {
                    Text(stringResource(R.string.editor_adjust_hashtags))
                    Icon(
                        if (showHashtags) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(if (showHashtags) R.string.editor_close else R.string.editor_open),
                        modifier = Modifier.padding(start = 4.dp).size(18.dp),
                    )
                }
            }
            AnimatedVisibility(visible = showHashtags) {
                HashtagPanel(
                    item = item,
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
                key = item.id,
                value = item.bodyText,
                onValueChange = viewModel::setBody,
                label = stringResource(R.string.editor_post_text),
                minLines = 3,
                supportingText = { TagsHint(item) },
                trailingIcon = {
                    IconButton(onClick = { showPlaceholderHelp = true }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.editor_placeholder_help),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (item.linkMayIdentify && item.sourceUrl != null) {
                LinkWarning(onRemoveLink = viewModel::removeLink)
            }

            StableTextField(
                // Regenerating alt text replaces it from outside, so the field
                // must re-seed when that happens rather than keep the old text.
                key = item.id to item.altText,
                value = item.altText.orEmpty(),
                onValueChange = viewModel::setAltText,
                label = stringResource(R.string.editor_alt_text),
                supportingText = {
                    if (state.altTextBusy) {
                        Text(stringResource(R.string.editor_alt_text_busy))
                    } else if (item.altTextFailed) {
                        Text(
                            stringResource(R.string.editor_alt_text_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(stringResource(R.string.editor_alt_text_hint))
                    }
                },
                minLines = 2,
                trailingIcon = {
                    if (state.altTextBusy) {
                        CircularProgressIndicator(Modifier.padding(12.dp))
                    } else {
                        IconButton(onClick = viewModel::regenerateAltText) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.editor_regenerate_alt_text))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.altModels.isNotEmpty()) {
                ModelPicker(
                    models = state.altModels,
                    current = state.altModel,
                    onPick = viewModel::setAltModel,
                )
            }

            ContentWarningField(
                key = item.id,
                value = item.contentWarning.orEmpty(),
                onValueChange = viewModel::setContentWarning,
                modifier = Modifier.fillMaxWidth(),
            )

            VisibilityPicker(
                selected = item.visibility,
                onSelect = viewModel::setVisibility,
                text = item.bodyText,
            )

            AccountPicker(state, viewModel)
            if (state.stacks.isNotEmpty()) StackPicker(state, viewModel)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.saveDraft(onDone) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.editor_save_draft)) }

                Button(
                    onClick = { viewModel.send(onDone) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Text(stringResource(R.string.editor_post), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * Says what `{tags}` will become, since the text shows the placeholder itself
 * until the post is sent — and says so when the hashtags would go nowhere.
 */
@Composable
private fun TagsHint(item: Item) {
    if (item.hashtags == null) return
    val hasToken = "{tags}" in item.bodyText
    val tags = item.hashtagList
    Text(
        when {
            hasToken && tags.isEmpty() -> stringResource(R.string.editor_tags_empty)
            hasToken -> stringResource(R.string.editor_tags_becomes, tags.joinToString(" "))
            tags.isNotEmpty() -> stringResource(R.string.editor_tags_missing)
            else -> ""
        },
    )
}

@Composable
private fun TemplatePicker(state: EditorState, viewModel: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    val current = state.templates.firstOrNull { it.id == state.item?.templateId }

    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.editor_template, current?.name ?: "—"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.templates.forEach { template ->
                DropdownMenuItem(
                    text = { Text(template.name) },
                    onClick = {
                        viewModel.applyTemplate(template)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AccountPicker(state: EditorState, viewModel: EditorViewModel) {
    if (state.accounts.isEmpty()) {
        Text(
            stringResource(R.string.editor_no_account),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    var open by remember { mutableStateOf(false) }
    val current = state.accounts.firstOrNull { it.id == state.item?.accountId }
        ?: state.accounts.firstOrNull { it.isDefault }

    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.editor_account, current?.acct ?: "—"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text("@${account.acct}") },
                    onClick = {
                        viewModel.setAccount(account.id)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * The link may name whoever shared it — a Pinterest link carries the sender —
 * and no clean one could be confirmed. A warning, so in the warning colours.
 */
@Composable
private fun LinkWarning(onRemoveLink: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.editor_link_warning_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.editor_link_warning_body),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = onRemoveLink,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) { Text(stringResource(R.string.editor_remove_link), fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * Which inbox stack the post sits on — so a post finished here can go onto
 * "Ready" on its way out of the editor. Saved with the post.
 */
@Composable
private fun StackPicker(state: EditorState, viewModel: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    val current = state.stacks.firstOrNull { it.id == state.item?.stackId }
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.editor_stack, current?.name ?: stringResource(R.string.editor_stack_new)))
            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.editor_change_stack), modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_stack_none)) },
                onClick = {
                    viewModel.setStack(null)
                    open = false
                },
            )
            state.stacks.forEach { stack ->
                DropdownMenuItem(
                    text = { Text(stack.name) },
                    onClick = {
                        viewModel.setStack(stack.id)
                        open = false
                    },
                )
            }
        }
    }
}

