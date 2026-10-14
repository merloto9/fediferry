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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.fediferry.R
import app.fediferry.api.PostSettings
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.Visibility
import app.fediferry.template.TemplateEngine
import app.fediferry.ui.AltTextModePicker
import app.fediferry.ui.ContentWarningField
import app.fediferry.ui.VisibilityPicker

/**
 * How a channel's posts start: the text with its placeholders, which sources
 * fill them, the hashtags, visibility, content warning and alt text. What a
 * template held before; one per channel now.
 */
@Composable
internal fun PostSettingsFields(
    key: Any,
    value: PostSettings,
    onChange: (PostSettings) -> Unit,
    visionConfigured: Boolean,
    placeholderNames: Set<String>,
    hashtags: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val unknown = TemplateEngine.unknownIn(value.body, placeholderNames)
        OutlinedTextField(
            value = value.body,
            onValueChange = { onChange(value.copy(body = it)) },
            label = { Text(stringResource(R.string.settings_template_body)) },
            minLines = 2,
            isError = unknown.isNotEmpty(),
            supportingText = if (unknown.isNotEmpty()) {
                { Text(pluralStringResource(R.plurals.settings_template_unknown, unknown.size, unknown.joinToString { "{$it}" })) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SourcePicker(excluded = value.excludedSources, onChange = { onChange(value.copy(excludedSources = it)) })
        TemplateHashtags(
            picked = value.hashtagList,
            list = hashtags,
            onChange = { onChange(value.copy(tags = Hashtags.format(it))) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = value.addSourceHashtags, onCheckedChange = { onChange(value.copy(addSourceHashtags = it)) })
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
            key = key,
            value = value.contentWarning.orEmpty(),
            onValueChange = { onChange(value.copy(contentWarning = it.ifBlank { null })) },
            label = stringResource(R.string.settings_default_content_warning),
            modifier = Modifier.fillMaxWidth(),
        )
        VisibilityPicker(
            selected = runCatching { Visibility.valueOf(value.visibility) }.getOrDefault(Visibility.PUBLIC),
            onSelect = { onChange(value.copy(visibility = it.name)) },
            text = value.body,
            title = stringResource(R.string.settings_default_visibility),
            titleStyle = MaterialTheme.typography.labelMedium,
        )
        val altMode = runCatching { AltTextMode.valueOf(value.altTextMode) }.getOrDefault(AltTextMode.NONE)
        AltTextModePicker(selected = altMode, onSelect = { onChange(value.copy(altTextMode = it.name)) }, visionConfigured = visionConfigured)
        if (altMode == AltTextMode.STATIC) {
            OutlinedTextField(
                value = value.staticAltText.orEmpty(),
                onValueChange = { onChange(value.copy(staticAltText = it)) },
                label = { Text(stringResource(R.string.settings_fixed_description)) },
                placeholder = { Text(stringResource(R.string.settings_fixed_description_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
