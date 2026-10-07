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

import app.fediferry.di.ServiceLocator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.EditNote
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.fediferry.R

/** The app's spaces. The library and drafts are there only while the phone works with a FediFerry server. */
enum class Space(val route: String, @StringRes val labelRes: Int) {
    LIBRARY(Routes.LIBRARY, R.string.nav_library),
    DRAFTS(Routes.DRAFTS, R.string.nav_drafts),
    INBOX(Routes.INBOX, R.string.nav_inbox),
    QUEUE(Routes.QUEUE, R.string.nav_queue),
    SOURCES(Routes.SOURCES, R.string.nav_sources),
}

@Composable
fun SpaceBar(current: Space, onNavigate: (Space) -> Unit) {
    val connected = ServiceLocator.serverConnections(LocalContext.current).current() != null
    NavigationBar {
        Space.entries.filter { connected || (it != Space.LIBRARY && it != Space.DRAFTS) }.forEach { space ->
            val label = stringResource(space.labelRes)
            NavigationBarItem(
                selected = space == current,
                onClick = { if (space != current) onNavigate(space) },
                icon = {
                    Icon(
                        when (space) {
                            Space.LIBRARY -> Icons.Outlined.PhotoLibrary
                            Space.DRAFTS -> Icons.Outlined.EditNote
                            Space.INBOX -> Icons.Default.Inbox
                            Space.QUEUE -> Icons.Default.Schedule
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
