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

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.fediferry.R
import app.fediferry.ui.theme.ColorSource
import app.fediferry.ui.theme.ContrastLevel
import app.fediferry.ui.theme.ThemeMode

/** Theme, colours and contrast — each choice explained under its chips. */
@Composable
internal fun AppearanceSection(state: SettingsState, viewModel: SettingsViewModel) {
    val s = state.settings

    Choice(
        title = stringResource(R.string.appearance_theme),
        options = ThemeMode.entries,
        selected = s.themeMode,
        label = {
            when (it) {
                ThemeMode.SYSTEM -> stringResource(R.string.appearance_system)
                ThemeMode.LIGHT -> stringResource(R.string.appearance_light)
                ThemeMode.DARK -> stringResource(R.string.appearance_dark)
            }
        },
        explain = {
            when (it) {
                ThemeMode.SYSTEM -> stringResource(R.string.appearance_theme_system_explain)
                ThemeMode.LIGHT -> stringResource(R.string.appearance_theme_light_explain)
                ThemeMode.DARK -> stringResource(R.string.appearance_theme_dark_explain)
            }
        },
        onSelect = viewModel::setThemeMode,
    )

    val wallpaperAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Choice(
        title = stringResource(R.string.appearance_colours),
        options = if (wallpaperAvailable) ColorSource.entries else listOf(ColorSource.APP),
        selected = if (wallpaperAvailable) s.colorSource else ColorSource.APP,
        label = {
            when (it) {
                ColorSource.APP -> "FediFerry"
                ColorSource.WALLPAPER -> stringResource(R.string.appearance_colours_wallpaper)
            }
        },
        explain = {
            when (it) {
                ColorSource.APP ->
                    stringResource(
                        if (wallpaperAvailable) {
                            R.string.appearance_colours_app_explain
                        } else {
                            R.string.appearance_colours_app_explain_no_wallpaper
                        },
                    )
                ColorSource.WALLPAPER -> stringResource(R.string.appearance_colours_wallpaper_explain)
            }
        },
        onSelect = viewModel::setColorSource,
    )

    val systemContrast = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    Choice(
        title = stringResource(R.string.appearance_contrast),
        options = ContrastLevel.entries,
        selected = s.contrastLevel,
        label = {
            when (it) {
                ContrastLevel.SYSTEM -> stringResource(R.string.appearance_system)
                ContrastLevel.STANDARD -> stringResource(R.string.appearance_contrast_standard)
                ContrastLevel.HIGH -> stringResource(R.string.appearance_contrast_high)
            }
        },
        explain = {
            when (it) {
                ContrastLevel.SYSTEM ->
                    if (systemContrast) {
                        stringResource(R.string.appearance_contrast_system_explain)
                    } else {
                        stringResource(R.string.appearance_contrast_system_unavailable)
                    }
                ContrastLevel.STANDARD -> stringResource(R.string.appearance_contrast_standard_explain)
                ContrastLevel.HIGH -> stringResource(R.string.appearance_contrast_high_explain)
            }
        },
        onSelect = viewModel::setContrastLevel,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    explain: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val on = option == selected
                FilterChip(
                    selected = on,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                    leadingIcon = if (on) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
        Text(
            explain(selected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
