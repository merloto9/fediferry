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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.data.model.Visibility
import app.fediferry.ui.ContentWarningField
import app.fediferry.ui.PlaceholderHelpDialog
import app.fediferry.ui.StableTextField
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
        PlaceholderHelpDialog(onDismiss = { showPlaceholderHelp = false })
    }

    LaunchedEffect(itemId) { viewModel.load(itemId) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Edit post") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.saveDraft(onDone) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Save and go back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.discard(onDone) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Discard")
                    }
                },
            )
        },
    ) { padding ->
        val item = state.item
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loaded) Text("That item is gone") else CircularProgressIndicator()
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
                AsyncImage(
                    model = File(path),
                    contentDescription = item.altText ?: "Shared image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Both tools decode a still; a resolved animation is a video.
                    if (item.mimeType?.startsWith("video/") != true) {
                        TextButton(onClick = { onTrim(item.id) }) { Text("Trim") }
                        TextButton(onClick = { onCleanUp(item.id) }) { Text("Clean up") }
                    }
                    if (item.originalMediaPath != null) {
                        TextButton(onClick = viewModel::revertEdits) { Text("Undo edits") }
                    }
                }
            }

            TemplatePicker(state, viewModel)

            StableTextField(
                key = item.id,
                value = item.bodyText,
                onValueChange = viewModel::setBody,
                label = "Post text",
                minLines = 3,
                trailingIcon = {
                    IconButton(onClick = { showPlaceholderHelp = true }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "Which placeholders can I use?",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            StableTextField(
                // Regenerating alt text replaces it from outside, so the field
                // must re-seed when that happens rather than keep the old text.
                key = item.id to item.altText,
                value = item.altText.orEmpty(),
                onValueChange = viewModel::setAltText,
                label = "Alt text",
                supportingText = {
                    if (state.altTextBusy) {
                        Text("Asking the vision model to describe the picture…")
                    } else if (item.altTextFailed) {
                        Text(
                            "Automatic description failed — the post will go out without one.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                minLines = 2,
                trailingIcon = {
                    if (state.altTextBusy) {
                        CircularProgressIndicator(Modifier.padding(12.dp))
                    } else {
                        IconButton(onClick = viewModel::regenerateAltText) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Regenerate alt text")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            ContentWarningField(
                key = item.id,
                value = item.contentWarning.orEmpty(),
                onValueChange = viewModel::setContentWarning,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Visibility", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Visibility.entries.forEach { visibility ->
                    FilterChip(
                        selected = item.visibility == visibility,
                        onClick = { viewModel.setVisibility(visibility) },
                        label = { Text(visibility.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            AccountPicker(state, viewModel)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.saveDraft(onDone) },
                    modifier = Modifier.weight(1f),
                ) { Text("Save draft") }

                Button(
                    onClick = { viewModel.send(onDone) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Text("Post", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun TemplatePicker(state: EditorState, viewModel: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    val current = state.templates.firstOrNull { it.id == state.item?.templateId }

    Box {
        TextButton(onClick = { open = true }) {
            Text("Template: ${current?.name ?: "—"}")
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
            "No account connected — add one in Settings before posting.",
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
            Text("Account: @${current?.acct ?: "—"}")
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
