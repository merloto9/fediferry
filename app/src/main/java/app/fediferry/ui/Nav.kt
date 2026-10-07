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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.fediferry.library.LibraryScreen
import app.fediferry.drafts.DraftEditorScreen
import app.fediferry.drafts.DraftsScreen
import app.fediferry.review.ReviewPostScreen
import app.fediferry.review.ReviewScreen
import app.fediferry.ui.queue.QueuedPostScreen
import android.net.Uri
import app.fediferry.ui.queue.QueueScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.fediferry.ui.cleanup.CleanupScreen
import app.fediferry.ui.crop.CropScreen
import app.fediferry.ui.editor.EditorScreen
import app.fediferry.ui.inbox.InboxScreen
import app.fediferry.ui.settings.SettingsPage
import app.fediferry.ui.settings.SettingsPageScreen
import app.fediferry.ui.settings.SettingsScreen
import app.fediferry.ui.sources.ChannelScreen
import app.fediferry.ui.sources.SourcesScreen
import androidx.navigation.NavController

object Routes {
    const val INBOX = "inbox"
    const val LIBRARY = "library"
    const val DRAFTS = "drafts"
    const val DRAFT = "draft/{postId}"
    const val REVIEW = "review"
    const val REVIEW_POST = "review/{postId}"
    const val QUEUE = "queue"
    const val QUEUED_POST = "queue/{accountId}/{statusId}"
    const val SOURCES = "sources"
    const val CHANNEL = "channel/{sourceId}"
    const val SETTINGS = "settings"
    const val SETTINGS_PAGE = "settings/{page}"
    const val EDITOR = "editor/{itemId}"
    const val CROP = "crop/{itemId}"
    const val CLEANUP = "cleanup/{itemId}"

    fun editor(itemId: String) = "editor/$itemId"
    fun draft(postId: String) = "draft/$postId"
    fun reviewPost(postId: String) = "review/$postId"
    fun crop(itemId: String) = "crop/$itemId"
    fun cleanup(itemId: String) = "cleanup/$itemId"
    fun channel(sourceId: String) = "channel/$sourceId"
    /** An account id holds a slash ("instance/acct"), so it travels encoded. */
    fun queuedPost(accountId: String, statusId: String) = "queue/${Uri.encode(accountId)}/${Uri.encode(statusId)}"
    fun settingsPage(page: SettingsPage) = "settings/${page.route}"
}

@Composable
fun FediFerryNavHost(
    navController: NavHostController,
    editItemId: String?,
    cropItemId: String?,
    editPostId: String? = null,
    onEditConsumed: () -> Unit,
) {
    // A Compose-mode share lands here: jump straight to the editor for the item
    // the share receiver already persisted — or to the trim step first, when the
    // share brought an image that still looks like a full screenshot.
    LaunchedEffect(editItemId, cropItemId, editPostId) {
        val crop = cropItemId
        val edit = editItemId
        val draft = editPostId
        when {
            draft != null -> navController.navigate(Routes.draft(draft))
            crop != null -> navController.navigate(Routes.crop(crop))
            edit != null -> navController.navigate(Routes.editor(edit))
            else -> return@LaunchedEffect
        }
        onEditConsumed()
    }

    // Asks once whether to clean the links of posts shared before links were cleaned.
    OldLinkCleanupPrompt()

    // Working with a server, the library is home; on its own, the inbox is.
    val context = LocalContext.current
    val start = remember { if (ServiceLocator.serverConnections(context).current() != null) Routes.LIBRARY else Routes.INBOX }
    NavHost(navController = navController, startDestination = start) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenDraft = { navController.navigate(Routes.draft(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onSwitchSpace = { navController.switchSpace(it) },
            )
        }
        composable(Routes.DRAFTS) {
            DraftsScreen(
                onOpenDraft = { navController.navigate(Routes.draft(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onSwitchSpace = { navController.switchSpace(it) },
            )
        }
        composable(
            route = Routes.DRAFT,
            arguments = listOf(navArgument("postId") { type = NavType.StringType }),
        ) { entry ->
            DraftEditorScreen(
                postId = entry.arguments?.getString("postId").orEmpty(),
                onDone = { if (!navController.popBackStack()) navController.navigate(Routes.DRAFTS) },
                // Ready: the post is in review now, so that is where it is shown.
                onReady = { id -> navController.navigate(Routes.reviewPost(id)) { popUpTo(Routes.DRAFT) { inclusive = true } } },
            )
        }
        composable(Routes.REVIEW) {
            ReviewScreen(
                onOpenPost = { navController.navigate(Routes.reviewPost(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onSwitchSpace = { navController.switchSpace(it) },
            )
        }
        composable(
            route = Routes.REVIEW_POST,
            arguments = listOf(navArgument("postId") { type = NavType.StringType }),
        ) { entry ->
            ReviewPostScreen(
                postId = entry.arguments?.getString("postId").orEmpty(),
                onBack = { if (!navController.popBackStack()) navController.navigate(Routes.REVIEW) },
                onOpenDraft = { id -> navController.navigate(Routes.draft(id)) { popUpTo(Routes.REVIEW_POST) { inclusive = true } } },
            )
        }
        composable(Routes.INBOX) {
            InboxScreen(
                onOpenItem = { navController.navigate(Routes.editor(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onSwitchSpace = { navController.switchSpace(it) },
            )
        }
        composable(Routes.QUEUE) {
            QueueScreen(
                onOpenPost = { navController.navigate(Routes.queuedPost(it.account.id, it.status.id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onSwitchSpace = { navController.switchSpace(it) },
            )
        }
        composable(
            route = Routes.QUEUED_POST,
            arguments = listOf(
                navArgument("accountId") { type = NavType.StringType },
                navArgument("statusId") { type = NavType.StringType },
            ),
        ) { entry ->
            QueuedPostScreen(
                accountId = entry.arguments?.getString("accountId").orEmpty(),
                statusId = entry.arguments?.getString("statusId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SOURCES) {
            SourcesScreen(
                onOpenChannel = { navController.navigate(Routes.channel(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onSwitchSpace = { navController.switchSpace(it) },
            )
        }
        composable(
            route = Routes.CHANNEL,
            arguments = listOf(navArgument("sourceId") { type = NavType.StringType }),
        ) { entry ->
            ChannelScreen(
                sourceId = entry.arguments?.getString("sourceId").orEmpty(),
                onBack = { navController.popBackStack() },
                onPicked = { itemId -> navController.navigate(Routes.editor(itemId)) },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            EditorScreen(
                itemId = entry.arguments?.getString("itemId").orEmpty(),
                onTrim = { navController.navigate(Routes.crop(it)) },
                onCleanUp = { navController.navigate(Routes.cleanup(it)) },
                onDone = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.INBOX)
                    }
                },
            )
        }
        composable(
            route = Routes.CROP,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("itemId").orEmpty()
            CropScreen(
                itemId = id,
                onDone = { croppedId ->
                    navController.navigate(Routes.editor(croppedId)) {
                        popUpTo(Routes.CROP) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.CLEANUP,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            CleanupScreen(
                itemId = entry.arguments?.getString("itemId").orEmpty(),
                onDone = { cleanedId ->
                    navController.navigate(Routes.editor(cleanedId)) {
                        popUpTo(Routes.CLEANUP) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpen = { navController.navigate(Routes.settingsPage(it)) },
            )
        }
        composable(
            Routes.SETTINGS_PAGE,
            arguments = listOf(navArgument("page") { type = NavType.StringType }),
        ) { entry ->
            val page = SettingsPage.fromRoute(entry.arguments?.getString("page"))
            if (page == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                SettingsPageScreen(page = page, onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Switching space replaces the current one rather than stacking on it, so Back
 * always leaves the app from a space instead of cycling between the two.
 */
private fun NavController.switchSpace(space: Space) {
    navigate(space.route) {
        // Spaces sit side by side: switching never builds up a back stack.
        popUpTo(graph.startDestinationId) { inclusive = space.route == graph.startDestinationRoute }
        launchSingleTop = true
    }
}
