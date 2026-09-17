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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** The app's two spaces: what you have saved, and where things come from. */
enum class Space(val route: String, val label: String) {
    INBOX(Routes.INBOX, "Inbox"),
    SOURCES(Routes.SOURCES, "Sources"),
}

@Composable
fun SpaceBar(current: Space, onNavigate: (Space) -> Unit) {
    NavigationBar {
        Space.entries.forEach { space ->
            NavigationBarItem(
                selected = space == current,
                onClick = { if (space != current) onNavigate(space) },
                icon = {
                    Icon(
                        when (space) {
                            Space.INBOX -> Icons.Default.Inbox
                            Space.SOURCES -> Icons.Default.Subscriptions
                        },
                        contentDescription = space.label,
                    )
                },
                label = { Text(space.label) },
            )
        }
    }
}
