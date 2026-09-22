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
package app.fediferry.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.PlaceholderKey
import app.fediferry.data.model.Template
import app.fediferry.media.cleanup.CleanupPipeline
import app.fediferry.media.cleanup.EditWireFormat
import app.fediferry.media.cleanup.MaskPolarity
import app.fediferry.ui.AltTextModePicker
import app.fediferry.ui.LoadingOverlay
import app.fediferry.ui.VisibilityPicker
import app.fediferry.ui.PlaceholderHelpDialog
import app.fediferry.ui.ContentWarningField
import app.fediferry.ui.StableTextField
import app.fediferry.template.TemplateEngine
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showPlaceholderHelp by remember { mutableStateOf(false) }

    if (showPlaceholderHelp) {
        PlaceholderHelpDialog(keys = state.placeholderKeys, onDismiss = { showPlaceholderHelp = false })
    }

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
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountsSection(state, viewModel)
            HorizontalDivider()
            TemplatesSection(state, viewModel) { showPlaceholderHelp = true }
            HorizontalDivider()
            HashtagsSection(state, viewModel)
            HorizontalDivider()
            PlaceholdersSection(state, viewModel)
            HorizontalDivider()
            ModulesSection(state, viewModel)
            HorizontalDivider()
            PostingSection(state, viewModel)
            HorizontalDivider()
            CleanupSection(state, viewModel)
            HorizontalDivider()
            ImageModelSection(state, viewModel)
            HorizontalDivider()
            VisionSection(state, viewModel)
            HorizontalDivider()
            DiagnosticsSection(state, viewModel)
        }
    }
}

@Composable
private fun AccountsSection(state: SettingsState, viewModel: SettingsViewModel) {
    var instance by remember { mutableStateOf("") }

    SectionTitle("Accounts")

    state.accounts.forEach { account ->
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("@${account.acct}", style = MaterialTheme.typography.titleSmall)
                    Text(account.instance, style = MaterialTheme.typography.bodySmall)
                }
                if (account.isDefault) {
                    Text("Default", style = MaterialTheme.typography.labelSmall)
                } else {
                    TextButton(onClick = { viewModel.makeDefaultAccount(account.id) }) {
                        Text("Make default")
                    }
                }
                IconButton(onClick = { viewModel.disconnect(account.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Disconnect")
                }
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = instance,
            onValueChange = { instance = it },
            label = { Text("Instance") },
            placeholder = { Text("mastodon.social") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { viewModel.connect(instance) },
            enabled = !state.connecting,
        ) { Text("Connect") }
    }

    if (state.connecting) {
        LoadingOverlay(
            title = "Contacting ${instance.trim().ifBlank { "your instance" }}",
            detail = "Registering FediFerry with the server. Its sign-in page opens " +
                "in the browser as soon as it answers.",
            icon = Icons.AutoMirrored.Outlined.Login,
        )
    }
}

@Composable
private fun TemplatesSection(
    state: SettingsState,
    viewModel: SettingsViewModel,
    onShowPlaceholderHelp: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Templates", Modifier.weight(1f))
        IconButton(onClick = viewModel::newTemplate) {
            Icon(Icons.Default.Add, contentDescription = "New template")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "A template is a topic: which hashtags it ticks, and how its posts are " +
                "written. Bodies can use {tags}, {link}, {date} and the placeholders below.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onShowPlaceholderHelp) { Text("What can I use?") }
    }

    state.templates.forEach { template ->
        TemplateCard(
            template = template,
            viewModel = viewModel,
            visionConfigured = state.settings.visionEndpoint.isNotBlank(),
            placeholderNames = PlaceholderKey.RESERVED + state.placeholderKeys.map { it.name },
            hashtags = state.hashtags,
        )
    }
}

@Composable
private fun TemplateCard(
    template: Template,
    viewModel: SettingsViewModel,
    visionConfigured: Boolean,
    placeholderNames: Set<String>,
    hashtags: List<String>,
) {
    var draft by remember(template.id) { mutableStateOf(template) }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val unknown = TemplateEngine.unknownIn(draft.body, placeholderNames)
            OutlinedTextField(
                value = draft.body,
                onValueChange = { draft = draft.copy(body = it) },
                label = { Text("Body") },
                minLines = 2,
                isError = unknown.isNotEmpty(),
                supportingText = if (unknown.isNotEmpty()) {
                    {
                        Text(
                            unknown.joinToString { "{$it}" } + " " +
                                (if (unknown.size == 1) "is" else "are") +
                                " not defined, and would be posted exactly as typed.",
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            SourcePicker(
                excluded = draft.excludedSources,
                onChange = { draft = draft.copy(excludedSources = it) },
            )
            TemplateHashtags(
                picked = draft.hashtagList,
                list = hashtags,
                onChange = { draft = draft.copy(tags = Hashtags.format(it)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = draft.addSourceHashtags,
                    onCheckedChange = { draft = draft.copy(addSourceHashtags = it) },
                )
                Column(Modifier.weight(1f)) {
                    Text("Add the source's hashtags", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Ticks whatever a source's {tags} recipe under Source modules gives — " +
                            "9GAG's own tags, say — beside the ones above. Each post can still " +
                            "change it in the editor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ContentWarningField(
                key = template.id,
                value = draft.contentWarning.orEmpty(),
                onValueChange = { draft = draft.copy(contentWarning = it.ifBlank { null }) },
                label = "Default content warning",
                modifier = Modifier.fillMaxWidth(),
            )

            VisibilityPicker(
                selected = draft.visibility,
                onSelect = { draft = draft.copy(visibility = it) },
                text = draft.body,
                title = "Default visibility",
                titleStyle = MaterialTheme.typography.labelMedium,
            )

            AltTextModePicker(
                selected = draft.altTextMode,
                onSelect = { draft = draft.copy(altTextMode = it) },
                visionConfigured = visionConfigured,
            )

            if (draft.altTextMode == AltTextMode.STATIC) {
                OutlinedTextField(
                    value = draft.staticAltText.orEmpty(),
                    onValueChange = { draft = draft.copy(staticAltText = it) },
                    label = { Text("Fixed description") },
                    placeholder = { Text("A meme") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { viewModel.saveTemplate(draft) }) { Text("Save") }
                if (!template.isDefault) {
                    TextButton(onClick = { viewModel.makeDefaultTemplate(template.id) }) {
                        Text("Make default")
                    }
                    TextButton(onClick = { viewModel.deleteTemplate(template.id) }) {
                        Text("Delete")
                    }
                } else {
                    Text("Default", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun PostingSection(state: SettingsState, viewModel: SettingsViewModel) {
    SectionTitle("Posting")

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Fetch images from shared links")
            Text(
                "When a link is shared on its own, get the image from the service " +
                    "instead of waiting for a screenshot. Works for 9GAG, Pinterest and Reddit; " +
                    "Instagram publishes nothing to fetch.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = state.settings.resolveLinks,
            onCheckedChange = viewModel::setResolveLinks,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Trim screenshots automatically")
            Text(
                "Post now and Save for later cut a screenshot down to the picture " +
                    "when the detection is confident. The original is kept either way, " +
                    "so the editor can undo it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = state.settings.autoCrop,
            onCheckedChange = viewModel::setAutoCrop,
        )
    }

    Text("Undo window: ${state.settings.undoDelaySeconds}s")
    Text(
        "How long Post now waits before sending. Zero sends immediately.",
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = state.settings.undoDelaySeconds.toFloat(),
        onValueChange = { viewModel.setUndoDelay(it.toInt()) },
        valueRange = 0f..30f,
        steps = 29,
    )

    Text(
        if (state.settings.purgePostedAfterDays == 0) {
            "Posted items: kept forever"
        } else {
            "Posted items: purged after ${state.settings.purgePostedAfterDays} days"
        },
    )
    Slider(
        value = state.settings.purgePostedAfterDays.toFloat(),
        onValueChange = { viewModel.setPurgeAfterDays(it.toInt()) },
        valueRange = 0f..90f,
        steps = 89,
    )
}

@Composable
private fun CleanupSection(state: SettingsState, viewModel: SettingsViewModel) {
    SectionTitle("Cleanup profiles")
    Text(
        "Areas to remove from a screenshot, per source. Drawn on the Clean up " +
            "screen and kept here, so when a layout moves you adjust the rule " +
            "rather than wait for an update.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "The profile marked default is applied on its own to shares that do not " +
            "stop for input. Leave none default and nothing happens unasked.",
        style = MaterialTheme.typography.bodySmall,
    )

    if (state.cleanupProfiles.isEmpty()) {
        Text(
            "No profiles yet — draw on Clean up in the editor and tick Remember.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    state.cleanupProfiles.forEach { profile ->
        val rules = state.cleanupRules.filter { it.profileId == profile.id }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (profile.isDefault) {
                        TextButton(onClick = viewModel::clearDefaultCleanupProfile) {
                            Text("Default")
                        }
                    } else {
                        TextButton(onClick = { viewModel.setDefaultCleanupProfile(profile.id) }) {
                            Text("Make default")
                        }
                    }
                    IconButton(onClick = { viewModel.deleteCleanupProfile(profile.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete profile")
                    }
                }
                if (rules.isEmpty()) {
                    Text("No areas yet", style = MaterialTheme.typography.bodySmall)
                }
                rules.forEach { rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { viewModel.setCleanupRuleEnabled(rule.id, it) },
                        )
                        Text(
                            "${rule.treatment.name.lowercase().replace('_', ' ')} · " +
                                "${(rule.left * 100).toInt()},${(rule.top * 100).toInt()}% → " +
                                "${(rule.right * 100).toInt()},${(rule.bottom * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        IconButton(onClick = { viewModel.deleteCleanupRule(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete area")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageModelSection(state: SettingsState, viewModel: SettingsViewModel) {
    SectionTitle("Image model")
    Text(
        "Used by the \"Erase with AI\" treatment on the Clean up screen, for " +
            "overlays that sit on detail where filling from the surroundings " +
            "only smears. Leave the endpoint empty and that treatment falls " +
            "back to a local fill.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Inpainting has no standard request shape, so the format is a setting " +
            "rather than a guess. Multipart suits the OpenAI images/edits " +
            "family; JSON suits the Stable Diffusion derived servers.",
        style = MaterialTheme.typography.bodySmall,
    )

    StableTextField(
        key = "image-endpoint",
        value = state.settings.imageEndpoint,
        onValueChange = viewModel::setImageEndpoint,
        label = "Endpoint URL",
        placeholder = "https://api.example.com/v1/images/edits",
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    StableTextField(
        key = "image-model",
        value = state.settings.imageModel,
        onValueChange = viewModel::setImageModel,
        label = "Model",
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    StableTextField(
        key = "image-key",
        value = state.settings.imageApiKey,
        onValueChange = viewModel::setImageApiKey,
        label = "API key",
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    StableTextField(
        key = "image-instruction",
        value = state.settings.imageInstruction,
        onValueChange = viewModel::setImageInstruction,
        label = "Instruction",
        placeholder = CleanupPipeline.DEFAULT_INSTRUCTION,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    Text("Request format", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditWireFormat.entries.forEach { format ->
            FilterChip(
                selected = state.settings.imageWireFormat == format.name,
                onClick = { viewModel.setImageWireFormat(format.name) },
                label = { Text(if (format == EditWireFormat.MULTIPART) "Multipart" else "JSON") },
            )
        }
    }

    Text("Mask marks the area to replace as", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MaskPolarity.entries.forEach { polarity ->
            FilterChip(
                selected = state.settings.imageMaskPolarity == polarity.name,
                onClick = { viewModel.setImageMaskPolarity(polarity.name) },
                label = {
                    Text(
                        if (polarity == MaskPolarity.TRANSPARENT_HOLE) "Transparent" else "White",
                    )
                },
            )
        }
    }
}

@Composable
private fun VisionSection(state: SettingsState, viewModel: SettingsViewModel) {
    SectionTitle("Alt-text provider")
    Text(
        "Writes alt text for templates set to Generated. Any OpenAI-compatible " +
            "chat-completions endpoint whose model can look at images works. The " +
            "picture is sent there, so pick a service you trust with it. A failure " +
            "never blocks a post; it goes out without a description instead.",
        style = MaterialTheme.typography.bodySmall,
    )

    StableTextField(
        key = "vision-endpoint",
        value = state.settings.visionEndpoint,
        onValueChange = viewModel::setVisionEndpoint,
        label = "Endpoint URL",
        placeholder = "https://api.example.com/v1/chat/completions",
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    StableTextField(
        key = "vision-model",
        value = state.settings.visionModel,
        onValueChange = viewModel::setVisionModel,
        label = "Model",
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    StableTextField(
        key = "vision-key",
        value = state.settings.visionApiKey,
        onValueChange = viewModel::setVisionApiKey,
        label = "API key",
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    StableTextField(
        key = "vision-prompt",
        value = state.settings.visionPrompt,
        onValueChange = viewModel::setVisionPrompt,
        label = "Prompt",
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

@Composable
private fun DiagnosticsSection(state: SettingsState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    SectionTitle("Diagnostics")

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Keep a log")
            Text(
                "Records what the app does — a share arriving, a link resolving, a " +
                    "post failing — so a problem can be looked at afterwards. Access " +
                    "tokens and post text are never written down.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = state.settings.debugLogging,
            onCheckedChange = viewModel::setDebugLogging,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (state.logSizeBytes == 0L) "Nothing logged yet" else "Log: ${formatSize(state.logSizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                scope.launch {
                    viewModel.exportLog()?.let { shareLog(context, it) }
                }
            },
            enabled = state.logSizeBytes > 0,
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Text("Send log", modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = viewModel::clearLog, enabled = state.logSizeBytes > 0) {
            Text("Clear")
        }
    }
}

/**
 * Hands the exported file to whichever messenger the user picks.
 *
 * The URI comes from the app's FileProvider and is granted read permission for
 * this one send; the file itself stays in the app's cache.
 */
private fun shareLog(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        // Some messengers read the ClipData rather than the extra.
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Send the log"))
}

/** Bytes as something a person reads at a glance. */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}

/**
 * Which sources a template's placeholders are filled from. Unticking one keeps
 * the template usable for that source's posts — the tags and link still come
 * through — but every user-defined placeholder comes out empty for them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcePicker(excluded: Set<ContentSource>, onChange: (Set<ContentSource>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Fill placeholders from", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContentSource.entries.forEach { source ->
                val on = source !in excluded
                FilterChip(
                    selected = on,
                    onClick = { onChange(if (on) excluded + source else excluded - source) },
                    label = { Text(source.label) },
                    leadingIcon = if (on) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
        Text(
            "For posts from a ticked source, placeholders such as {caption} are filled " +
                "using the mappings under Placeholders. From an unticked one they stay empty.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The template's hashtags, picked from the list. One it picked that has since
 * left the list still shows, marked, so nothing disappears from a template
 * without the user seeing it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateHashtags(picked: List<String>, list: List<String>, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Hashtags", style = MaterialTheme.typography.labelMedium)
        val offered = Hashtags.union(list, picked)
        if (offered.isEmpty()) {
            Text(
                "The hashtag list is empty — add some under Hashtags below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            offered.forEach { tag ->
                val on = Hashtags.contains(picked, tag)
                val listed = Hashtags.contains(list, tag)
                FilterChip(
                    selected = on,
                    onClick = {
                        onChange(if (on) picked.filterNot { it.equals(tag, ignoreCase = true) } else picked + tag)
                    },
                    label = { Text(if (listed) tag else "$tag (not on the list)") },
                    leadingIcon = if (on) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
        Text(
            "Ticked ones start ticked in the editor and fill {tags}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
