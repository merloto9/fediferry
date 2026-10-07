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
package app.fediferry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.annotation.StringRes
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.fediferry.R
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.Visibility

/** The names Mastodon's own apps use, so the choice reads the same in both places. */
@get:StringRes
val Visibility.labelRes: Int
    get() = when (this) {
        Visibility.PUBLIC -> R.string.options_visibility_public
        Visibility.UNLISTED -> R.string.options_visibility_unlisted
        Visibility.PRIVATE -> R.string.options_visibility_private
        Visibility.DIRECT -> R.string.options_visibility_direct
    }

val Visibility.label: String
    @Composable get() = stringResource(labelRes)

@get:StringRes
val Visibility.explanationRes: Int
    get() = when (this) {
        Visibility.PUBLIC -> R.string.options_visibility_public_explain
        Visibility.UNLISTED -> R.string.options_visibility_unlisted_explain
        Visibility.PRIVATE -> R.string.options_visibility_private_explain
        Visibility.DIRECT -> R.string.options_visibility_direct_explain
    }

val Visibility.explanation: String
    @Composable get() = stringResource(explanationRes)

@get:StringRes
val AltTextMode.labelRes: Int
    get() = when (this) {
        AltTextMode.NONE -> R.string.options_alt_none
        AltTextMode.STATIC -> R.string.options_alt_static
        AltTextMode.VISION -> R.string.options_alt_vision
    }

val AltTextMode.label: String
    @Composable get() = stringResource(labelRes)

@get:StringRes
val AltTextMode.explanationRes: Int
    get() = when (this) {
        AltTextMode.NONE -> R.string.options_alt_none_explain
        AltTextMode.STATIC -> R.string.options_alt_static_explain
        AltTextMode.VISION -> R.string.options_alt_vision_explain
    }

val AltTextMode.explanation: String
    @Composable get() = stringResource(explanationRes)

/**
 * Visibility chips with the chosen one explained underneath. Wraps, since four
 * chips do not fit across a phone.
 *
 * @param text the post text, to warn when a mention-only post mentions nobody.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisibilityPicker(
    selected: Visibility,
    onSelect: (Visibility) -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.options_visibility),
    titleStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = titleStyle)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Visibility.entries.forEach { visibility ->
                FilterChip(
                    selected = selected == visibility,
                    onClick = { onSelect(visibility) },
                    label = { Text(visibility.label) },
                )
            }
        }
        Explanation(selected.explanation)
        if (selected == Visibility.DIRECT && !MENTION.containsMatchIn(text)) {
            Explanation(
                stringResource(R.string.options_no_mention_warning),
                warning = true,
            )
        }
    }
}

/** Alt-text source chips with the chosen one explained underneath. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AltTextModePicker(
    selected: AltTextMode,
    onSelect: (AltTextMode) -> Unit,
    visionConfigured: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.options_alt_text), style = MaterialTheme.typography.labelMedium)
        Explanation(
            stringResource(R.string.options_alt_text_explain),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AltTextMode.entries.forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        Explanation(selected.explanation)
        if (selected == AltTextMode.VISION && !visionConfigured) {
            Explanation(
                stringResource(R.string.options_no_model),
                warning = true,
            )
        }
    }
}

@Composable
private fun Explanation(text: String, warning: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `@name` or `@name@server`, not an email address. */
private val MENTION = Regex("""(?<![\w@])@\w+""")
