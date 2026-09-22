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
import androidx.compose.ui.unit.dp
import app.fediferry.ui.theme.ColorSource
import app.fediferry.ui.theme.ContrastLevel
import app.fediferry.ui.theme.ThemeMode

/** Theme, colours and contrast — each choice explained under its chips. */
@Composable
internal fun AppearanceSection(state: SettingsState, viewModel: SettingsViewModel) {
    val s = state.settings
    Text("Appearance", style = MaterialTheme.typography.titleMedium)

    Choice(
        title = "Theme",
        options = ThemeMode.entries,
        selected = s.themeMode,
        label = {
            when (it) {
                ThemeMode.SYSTEM -> "System"
                ThemeMode.LIGHT -> "Light"
                ThemeMode.DARK -> "Dark"
            }
        },
        explain = {
            when (it) {
                ThemeMode.SYSTEM -> "Light or dark, as the phone is set."
                ThemeMode.LIGHT -> "Always light."
                ThemeMode.DARK -> "Always dark."
            }
        },
        onSelect = viewModel::setThemeMode,
    )

    val wallpaperAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Choice(
        title = "Colours",
        options = if (wallpaperAvailable) ColorSource.entries else listOf(ColorSource.APP),
        selected = if (wallpaperAvailable) s.colorSource else ColorSource.APP,
        label = {
            when (it) {
                ColorSource.APP -> "FediFerry"
                ColorSource.WALLPAPER -> "Wallpaper"
            }
        },
        explain = {
            when (it) {
                ColorSource.APP ->
                    "FediFerry's own green, with every text colour checked to stand out " +
                        "from what it sits on." +
                        if (wallpaperAvailable) "" else " Wallpaper colours need Android 12."
                ColorSource.WALLPAPER ->
                    "Colours taken from your wallpaper, as other apps on this phone use. They " +
                        "come with no contrast guarantee, and any that come out red are " +
                        "swapped for FediFerry's own: red is kept for warnings."
            }
        },
        onSelect = viewModel::setColorSource,
    )

    val systemContrast = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    Choice(
        title = "Contrast",
        options = ContrastLevel.entries,
        selected = s.contrastLevel,
        label = {
            when (it) {
                ContrastLevel.SYSTEM -> "System"
                ContrastLevel.STANDARD -> "Standard"
                ContrastLevel.HIGH -> "High"
            }
        },
        explain = {
            when (it) {
                ContrastLevel.SYSTEM ->
                    if (systemContrast) {
                        "Follows Settings → Accessibility → Colour and motion → Contrast: " +
                            "medium or high there means high here."
                    } else {
                        "This Android version has no contrast setting, so this is Standard."
                    }
                ContrastLevel.STANDARD -> "Text at least 4.5 times as bright or dark as its background."
                ContrastLevel.HIGH ->
                    "Text at least 7 times as bright or dark as its background, and firmer " +
                        "outlines. Always uses FediFerry's own colours."
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
    label: (T) -> String,
    explain: (T) -> String,
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
