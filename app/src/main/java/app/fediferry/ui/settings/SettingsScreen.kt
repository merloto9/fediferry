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

import androidx.compose.material.icons.outlined.Language
import android.content.ClipData
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import app.fediferry.ui.typingInsets
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.BuildConfig
import app.fediferry.R
import app.fediferry.data.model.AiKind
import app.fediferry.data.model.AiModel
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.PlaceholderKey
import app.fediferry.data.model.Template
import app.fediferry.media.cleanup.CleanupPipeline
import app.fediferry.media.cleanup.EditWireFormat
import app.fediferry.media.cleanup.MaskPolarity
import app.fediferry.media.cleanup.TreatmentKind
import app.fediferry.module.Modules
import app.fediferry.template.TemplateEngine
import app.fediferry.ui.AltTextModePicker
import app.fediferry.ui.ContentWarningField
import app.fediferry.ui.LoadingOverlay
import app.fediferry.ui.PlaceholderHelpDialog
import app.fediferry.ui.StableTextField
import app.fediferry.ui.VisibilityPicker
import app.fediferry.ui.theme.ColorSource
import app.fediferry.ui.theme.ContrastLevel
import app.fediferry.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.launch

/**
 * The pages Settings is divided into, grouped by what the user is doing
 * rather than by how the app is built. Each is one screen of its own.
 */
enum class SettingsPage(
    val route: String,
    @StringRes val title: Int,
    @StringRes val group: Int,
    val icon: ImageVector,
) {
    ACCOUNT("account", R.string.settings_page_account, R.string.settings_group_account, Icons.Outlined.AccountCircle),
    TEMPLATES("templates", R.string.settings_page_templates, R.string.settings_group_posts, Icons.Outlined.Description),
    HASHTAGS("hashtags", R.string.settings_page_hashtags, R.string.settings_group_posts, Icons.Outlined.Tag),
    PLACEHOLDERS("placeholders", R.string.settings_page_placeholders, R.string.settings_group_posts, Icons.Outlined.DataObject),
    SHARING("sharing", R.string.settings_page_sharing, R.string.settings_group_posts, Icons.AutoMirrored.Outlined.Send),
    CLEANUP("cleanup", R.string.settings_page_cleanup, R.string.settings_group_pictures, Icons.Outlined.AutoFixHigh),
    ALT_TEXT("alt_text", R.string.settings_page_alt_text, R.string.settings_group_pictures, Icons.Outlined.Accessibility),
    APPEARANCE("appearance", R.string.settings_page_appearance, R.string.settings_group_app, Icons.Outlined.Palette),
    LANGUAGE("language", R.string.settings_language, R.string.settings_group_app, Icons.Outlined.Language),
    DIAGNOSTICS("diagnostics", R.string.settings_page_diagnostics, R.string.settings_group_app, Icons.Outlined.BugReport),
    ;

    companion object {
        fun fromRoute(route: String?): SettingsPage? = entries.firstOrNull { it.route == route }
    }
}

/** What each page is set to right now, so the overview says it without opening anything. */
private fun SettingsPage.summary(state: SettingsState, context: Context): String {
    val s = state.settings
    val res = context.resources
    fun count(@PluralsRes id: Int, n: Int) = res.getQuantityString(id, n, n)
    // Strings independent facts together with " · ".
    fun join(a: String, b: String) = context.getString(R.string.settings_summary_separator, a, b)
    return when (this) {
        SettingsPage.ACCOUNT -> {
            val default = state.accounts.firstOrNull { it.isDefault } ?: state.accounts.firstOrNull()
            when {
                default == null -> context.getString(R.string.settings_summary_not_connected)
                state.accounts.size == 1 ->
                    context.getString(R.string.settings_summary_account, default.acct, default.instance)
                else -> context.resources.getQuantityString(
                    R.plurals.settings_summary_account_more,
                    state.accounts.size - 1,
                    default.acct,
                    default.instance,
                    state.accounts.size - 1,
                )
            }
        }
        SettingsPage.TEMPLATES -> {
            val default = state.templates.firstOrNull { it.isDefault }?.name
            val n = count(R.plurals.settings_summary_templates, state.templates.size)
            default?.let { context.getString(R.string.settings_summary_default, n, it) } ?: n
        }
        SettingsPage.HASHTAGS -> {
            val n = count(R.plurals.settings_summary_hashtags, state.hashtags.size)
            if (s.rememberSentHashtags) context.getString(R.string.settings_summary_hashtags_remember, n) else n
        }
        SettingsPage.PLACEHOLDERS ->
            join(
                state.placeholderKeys.sortedByDescending { it.isTags }.joinToString { "{${it.name}}" }
                    .ifEmpty { context.getString(R.string.settings_summary_no_placeholders) },
                count(R.plurals.settings_summary_sources, Modules.all.size),
            )
        SettingsPage.SHARING -> {
            val parts = listOfNotNull(
                context.getString(R.string.settings_summary_undo, s.undoDelaySeconds),
                context.getString(
                    if (s.resolveLinks) R.string.settings_summary_fetches_links else R.string.settings_summary_screenshots_only,
                ),
                if (s.autoCrop) context.getString(R.string.settings_summary_trims) else null,
            )
            parts.reduce { a, b -> join(a, b) }
        }
        SettingsPage.CLEANUP ->
            join(
                count(R.plurals.settings_summary_profiles, state.cleanupProfiles.size),
                models(state, AiKind.IMAGE_EDIT, context, none = context.getString(R.string.settings_summary_no_ai_model)),
            )
        SettingsPage.ALT_TEXT ->
            models(state, AiKind.ALT_TEXT, context, none = context.getString(R.string.settings_summary_no_model))
        SettingsPage.APPEARANCE -> {
            val theme = when (s.themeMode) {
                ThemeMode.SYSTEM -> R.string.settings_summary_theme_system
                ThemeMode.LIGHT -> R.string.settings_summary_theme_light
                ThemeMode.DARK -> R.string.settings_summary_theme_dark
            }
            val colours = if (s.colorSource == ColorSource.WALLPAPER) {
                R.string.settings_summary_colours_wallpaper
            } else {
                R.string.settings_summary_colours_app
            }
            val contrast = when (s.contrastLevel) {
                ContrastLevel.SYSTEM -> R.string.settings_summary_contrast_system
                ContrastLevel.STANDARD -> R.string.settings_summary_contrast_standard
                ContrastLevel.HIGH -> R.string.settings_summary_contrast_high
            }
            listOf(theme, colours, contrast).map { context.getString(it) }.reduce { a, b -> join(a, b) }
        }
        SettingsPage.LANGUAGE -> languageSummary(context)
        SettingsPage.DIAGNOSTICS ->
            if (s.debugLogging) {
                context.getString(R.string.settings_summary_logging, formatSize(context, state.logSizeBytes))
            } else {
                context.getString(R.string.settings_summary_log_off)
            }
    }
}

/** "2 models · default: Gemini Flash", or [none]. */
private fun models(state: SettingsState, kind: AiKind, context: Context, none: String): String {
    val models = state.aiModels.filter { it.kind == kind }
    val default = AiModel.pick(models) ?: return none
    return if (models.size == 1) {
        context.getString(R.string.settings_summary_model, default.displayName)
    } else {
        context.resources.getQuantityString(R.plurals.settings_summary_models, models.size, models.size, default.displayName)
    }
}

/** The top of Settings: every page, grouped, each with what it is set to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.typingInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
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
                .padding(vertical = 8.dp),
        ) {
            SettingsPage.entries.groupBy { it.group }.forEach { (group, pages) ->
                Text(
                    stringResource(group),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                pages.forEach { page ->
                    ListItem(
                        headlineContent = { Text(stringResource(page.title)) },
                        supportingContent = { Text(page.summary(state, LocalContext.current), maxLines = 2) },
                        leadingContent = { Icon(page.icon, contentDescription = null) },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable { onOpen(page) },
                    )
                }
            }
            AppFooter()
        }
    }
}

/** One page of Settings, with the sections that belong to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScreen(
    page: SettingsPage,
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
        contentWindowInsets = ScaffoldDefaults.typingInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back_to_settings))
                    }
                },
                actions = {
                    if (page == SettingsPage.TEMPLATES) {
                        IconButton(onClick = viewModel::newTemplate) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_new_template))
                        }
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
            when (page) {
                SettingsPage.ACCOUNT -> AccountsSection(state, viewModel)
                SettingsPage.TEMPLATES -> TemplatesSection(state, viewModel) { showPlaceholderHelp = true }
                SettingsPage.HASHTAGS -> HashtagsSection(state, viewModel)
                SettingsPage.PLACEHOLDERS -> {
                    PlaceholdersSection(state, viewModel)
                    HorizontalDivider()
                    ModulesSection(state, viewModel)
                }
                SettingsPage.SHARING -> PostingSection(state, viewModel)
                SettingsPage.CLEANUP -> {
                    CleanupSection(state, viewModel)
                    HorizontalDivider()
                    ImageModelSection(state, viewModel)
                }
                SettingsPage.ALT_TEXT -> VisionSection(state, viewModel)
                SettingsPage.APPEARANCE -> AppearanceSection(state, viewModel)
                SettingsPage.LANGUAGE -> LanguageSection()
                SettingsPage.DIAGNOSTICS -> DiagnosticsSection(state, viewModel)
            }
        }
    }
}

@Composable
private fun AccountsSection(state: SettingsState, viewModel: SettingsViewModel) {
    var instance by remember { mutableStateOf("") }

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
                    Text(stringResource(R.string.settings_default), style = MaterialTheme.typography.labelSmall)
                } else {
                    TextButton(onClick = { viewModel.makeDefaultAccount(account.id) }) {
                        Text(stringResource(R.string.settings_make_default))
                    }
                }
                IconButton(onClick = { viewModel.disconnect(account.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_disconnect))
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
            label = { Text(stringResource(R.string.settings_instance)) },
            placeholder = { Text("mastodon.social") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { viewModel.connect(instance) },
            enabled = !state.connecting,
        ) { Text(stringResource(R.string.settings_connect)) }
    }

    if (state.connecting) {
        LoadingOverlay(
            title = stringResource(
                R.string.settings_contacting,
                instance.trim().ifBlank { stringResource(R.string.settings_your_instance) },
            ),
            detail = stringResource(R.string.settings_contacting_detail),
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
        Text(
            stringResource(R.string.settings_templates_intro),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onShowPlaceholderHelp) { Text(stringResource(R.string.settings_what_can_i_use)) }
    }

    state.templates.forEach { template ->
        TemplateCard(
            template = template,
            viewModel = viewModel,
            visionConfigured = state.aiModels.any { it.kind == AiKind.ALT_TEXT },
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
                label = { Text(stringResource(R.string.settings_template_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val unknown = TemplateEngine.unknownIn(draft.body, placeholderNames)
            OutlinedTextField(
                value = draft.body,
                onValueChange = { draft = draft.copy(body = it) },
                label = { Text(stringResource(R.string.settings_template_body)) },
                minLines = 2,
                isError = unknown.isNotEmpty(),
                supportingText = if (unknown.isNotEmpty()) {
                    {
                        Text(
                            pluralStringResource(
                                R.plurals.settings_template_unknown,
                                unknown.size,
                                unknown.joinToString { "{$it}" },
                            ),
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
                    Text(stringResource(R.string.settings_add_source_hashtags), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_add_source_hashtags_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ContentWarningField(
                key = template.id,
                value = draft.contentWarning.orEmpty(),
                onValueChange = { draft = draft.copy(contentWarning = it.ifBlank { null }) },
                label = stringResource(R.string.settings_default_content_warning),
                modifier = Modifier.fillMaxWidth(),
            )

            VisibilityPicker(
                selected = draft.visibility,
                onSelect = { draft = draft.copy(visibility = it) },
                text = draft.body,
                title = stringResource(R.string.settings_default_visibility),
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
                    label = { Text(stringResource(R.string.settings_fixed_description)) },
                    placeholder = { Text(stringResource(R.string.settings_fixed_description_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { viewModel.saveTemplate(draft) }) { Text(stringResource(R.string.settings_save)) }
                if (!template.isDefault) {
                    TextButton(onClick = { viewModel.makeDefaultTemplate(template.id) }) {
                        Text(stringResource(R.string.settings_make_default))
                    }
                    TextButton(onClick = { viewModel.deleteTemplate(template.id) }) {
                        Text(stringResource(R.string.settings_delete))
                    }
                } else {
                    Text(stringResource(R.string.settings_default), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun PostingSection(state: SettingsState, viewModel: SettingsViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_resolve_links))
            Text(
                stringResource(R.string.settings_resolve_links_detail),
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
            Text(stringResource(R.string.settings_auto_crop))
            Text(
                stringResource(R.string.settings_auto_crop_detail),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = state.settings.autoCrop,
            onCheckedChange = viewModel::setAutoCrop,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_clean_old_links))
            Text(
                stringResource(R.string.settings_clean_old_links_detail),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = viewModel::offerOldLinkCleanupAgain) { Text(stringResource(R.string.settings_check)) }
    }

    Text(stringResource(R.string.settings_undo_window, state.settings.undoDelaySeconds))
    Text(
        stringResource(R.string.settings_undo_window_detail),
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
            stringResource(R.string.settings_purge_never)
        } else {
            pluralStringResource(
                R.plurals.settings_purge_after,
                state.settings.purgePostedAfterDays,
                state.settings.purgePostedAfterDays,
            )
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
    SectionTitle(stringResource(R.string.settings_cleanup_profiles))
    Text(
        stringResource(R.string.settings_cleanup_intro),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.settings_cleanup_default_intro),
        style = MaterialTheme.typography.bodySmall,
    )

    if (state.cleanupProfiles.isEmpty()) {
        Text(
            stringResource(R.string.settings_cleanup_no_profiles),
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
                            Text(stringResource(R.string.settings_default))
                        }
                    } else {
                        TextButton(onClick = { viewModel.setDefaultCleanupProfile(profile.id) }) {
                            Text(stringResource(R.string.settings_make_default))
                        }
                    }
                    IconButton(onClick = { viewModel.deleteCleanupProfile(profile.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_cleanup_delete_profile))
                    }
                }
                if (rules.isEmpty()) {
                    Text(stringResource(R.string.settings_cleanup_no_areas), style = MaterialTheme.typography.bodySmall)
                }
                rules.forEach { rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { viewModel.setCleanupRuleEnabled(rule.id, it) },
                        )
                        Text(
                            stringResource(
                                R.string.settings_cleanup_rule,
                                stringResource(rule.treatment.label),
                                (rule.left * 100).toInt(),
                                (rule.top * 100).toInt(),
                                (rule.right * 100).toInt(),
                                (rule.bottom * 100).toInt(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        IconButton(onClick = { viewModel.deleteCleanupRule(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_cleanup_delete_area))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageModelSection(state: SettingsState, viewModel: SettingsViewModel) {
    SectionTitle(stringResource(R.string.settings_image_model))
    Text(
        stringResource(R.string.settings_image_model_intro),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.settings_image_model_format),
        style = MaterialTheme.typography.bodySmall,
    )

    AiModelsSection(AiKind.IMAGE_EDIT, state, viewModel)

    Text(stringResource(R.string.settings_what_to_ask), style = MaterialTheme.typography.labelLarge)
    StableTextField(
        key = "image-instruction",
        value = state.settings.imageInstruction,
        onValueChange = viewModel::setImageInstruction,
        label = stringResource(R.string.settings_instruction),
        placeholder = CleanupPipeline.DEFAULT_INSTRUCTION,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun VisionSection(state: SettingsState, viewModel: SettingsViewModel) {
    Text(
        stringResource(R.string.settings_vision_intro),
        style = MaterialTheme.typography.bodySmall,
    )

    AiModelsSection(AiKind.ALT_TEXT, state, viewModel)

    Text(stringResource(R.string.settings_what_to_ask), style = MaterialTheme.typography.labelLarge)
    StableTextField(
        key = "vision-prompt",
        value = state.settings.visionPrompt,
        onValueChange = viewModel::setVisionPrompt,
        label = stringResource(R.string.settings_prompt),
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


    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_keep_log))
            Text(
                stringResource(R.string.settings_keep_log_detail),
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
            text = if (state.logSizeBytes == 0L) {
                stringResource(R.string.settings_nothing_logged)
            } else {
                stringResource(R.string.settings_log_size, formatSize(context, state.logSizeBytes))
            },
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
            Text(stringResource(R.string.settings_send_log), modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = viewModel::clearLog, enabled = state.logSizeBytes > 0) {
            Text(stringResource(R.string.settings_clear_log))
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
    context.startActivity(Intent.createChooser(send, context.getString(R.string.settings_send_log_chooser)))
}

/** Bytes as something a person reads at a glance. */
private fun formatSize(context: Context, bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> context.resources.getQuantityString(R.plurals.settings_size_bytes, bytes.toInt(), bytes.toInt())
}

/** How a cleanup rule's treatment reads in its one-line summary. */
@get:StringRes
private val TreatmentKind.label: Int
    get() = when (this) {
        TreatmentKind.FILL -> R.string.settings_treatment_fill
        TreatmentKind.CROP_AWAY -> R.string.settings_treatment_crop_away
        TreatmentKind.BLUR -> R.string.settings_treatment_blur
        TreatmentKind.PIXELATE -> R.string.settings_treatment_pixelate
        TreatmentKind.AI_ERASE -> R.string.settings_treatment_ai_erase
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
        Text(stringResource(R.string.settings_fill_placeholders_from), style = MaterialTheme.typography.labelMedium)
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
            stringResource(R.string.settings_fill_placeholders_detail),
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
        Text(stringResource(R.string.settings_template_hashtags), style = MaterialTheme.typography.labelMedium)
        val offered = Hashtags.sortedAlphabetically(Hashtags.union(list, picked))
        if (offered.isEmpty()) {
            Text(
                stringResource(R.string.settings_hashtag_list_empty),
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
                    label = { Text(if (listed) tag else stringResource(R.string.settings_hashtag_not_listed, tag)) },
                    leadingIcon = if (on) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
        Text(
            stringResource(R.string.settings_template_hashtags_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The app's name, the version running, and where its source lives. */
@Composable
private fun AppFooter() {
    val uri = LocalUriHandler.current
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "FediFerry ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { uri.openUri(REPOSITORY_URL) }) {
            Text(REPOSITORY_URL.removePrefix("https://"), style = MaterialTheme.typography.labelMedium)
        }
    }
}

private const val REPOSITORY_URL = "https://github.com/merloto9/fediferry"

