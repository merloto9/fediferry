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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.fediferry.ui.editor.EditorScreen
import app.fediferry.ui.inbox.InboxScreen
import app.fediferry.ui.settings.SettingsScreen

object Routes {
    const val INBOX = "inbox"
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{itemId}"

    fun editor(itemId: String) = "editor/$itemId"
}

@Composable
fun FediFerryNavHost(
    navController: NavHostController,
    editItemId: String?,
    onEditConsumed: () -> Unit,
) {
    // A Compose-mode share lands here: jump straight to the editor for the item
    // the share receiver already persisted.
    LaunchedEffect(editItemId) {
        val id = editItemId ?: return@LaunchedEffect
        navController.navigate(Routes.editor(id))
        onEditConsumed()
    }

    NavHost(navController = navController, startDestination = Routes.INBOX) {
        composable(Routes.INBOX) {
            InboxScreen(
                onOpenItem = { navController.navigate(Routes.editor(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            EditorScreen(
                itemId = entry.arguments?.getString("itemId").orEmpty(),
                onDone = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.INBOX)
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
