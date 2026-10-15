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

import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.runtime.getValue
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.fediferry.R

/** The app's spaces: the stages a post goes through, and the sources it can come from. */
enum class Space(val route: String, @StringRes val labelRes: Int) {
    DRAFTS(Routes.DRAFTS, R.string.nav_drafts),
    REVIEW(Routes.REVIEW, R.string.nav_review),
    PUBLISHING(Routes.PUBLISHING, R.string.nav_publishing),
    SOURCES(Routes.SOURCES, R.string.nav_sources),
}

@Composable
fun SpaceBar(current: Space, onNavigate: (Space) -> Unit) {
    val shown = Space.entries
    NavigationBar {
        shown.forEach { space ->
            val label = stringResource(space.labelRes)
            NavigationBarItem(
                selected = space == current,
                onClick = { if (space != current) onNavigate(space) },
                icon = {
                    Icon(
                        when (space) {
                            Space.DRAFTS -> Icons.Outlined.EditNote
                            Space.REVIEW -> Icons.Outlined.TaskAlt
                            Space.PUBLISHING -> Icons.AutoMirrored.Outlined.Send
                            Space.SOURCES -> Icons.Default.Subscriptions
                        },
                        contentDescription = label,
                    )
                },
                label = { Text(label) },
            )
        }
    }
}
