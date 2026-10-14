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
import app.fediferry.drafts.DraftEditorScreen
import app.fediferry.drafts.DraftsScreen
import app.fediferry.review.ReviewPostScreen
import app.fediferry.review.ReviewScreen
import app.fediferry.publishing.PublishingScreen
import app.fediferry.publishing.SchedulesScreen
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.fediferry.channels.ChannelSettingsScreen
import app.fediferry.ui.settings.SettingsPage
import app.fediferry.ui.settings.SettingsPageScreen
import app.fediferry.ui.settings.SettingsScreen
import app.fediferry.ui.sources.ChannelScreen
import app.fediferry.ui.sources.SourcesScreen
import androidx.navigation.NavController

object Routes {
    const val DRAFTS = "drafts"
    const val DRAFT = "draft/{postId}"
    const val REVIEW = "review"
    const val REVIEW_POST = "review/{postId}"
    const val PUBLISHING = "publishing"
    const val SCHEDULES = "schedules"
    const val SOURCES = "sources"
    const val CHANNEL = "channel/{sourceId}"
    const val SETTINGS = "settings"
    const val SETTINGS_PAGE = "settings/{page}"
    const val CHANNEL_SETTINGS = "settings/channel/{channelId}"

    fun draft(postId: String) = "draft/$postId"
    fun reviewPost(postId: String) = "review/$postId"
    fun channel(sourceId: String) = "channel/$sourceId"
    fun settingsPage(page: SettingsPage) = "settings/${page.route}"
    /** A channel id may hold a slash ("instance/acct"), so it travels encoded. */
    fun channelSettings(channelId: String) = "settings/channel/${Uri.encode(channelId)}"
}

@Composable
fun FediFerryNavHost(
    navController: NavHostController,
    editPostId: String? = null,
    onEditConsumed: () -> Unit,
) {
    // A Compose-mode share lands here: jump straight to the editor for the item
    // the share receiver already persisted — or to the trim step first, when the
    // share brought an image that still looks like a full screenshot.
    LaunchedEffect(editPostId) {
        val draft = editPostId ?: return@LaunchedEffect
        navController.navigate(Routes.draft(draft))
        onEditConsumed()
    }

    // Asks once whether to clean the links of posts shared before links were cleaned.
    OldLinkCleanupPrompt()

    // Drafts are home, whichever project the phone works on.
    NavHost(navController = navController, startDestination = Routes.DRAFTS) {
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
        composable(Routes.PUBLISHING) {
            PublishingScreen(
                onOpenPost = { navController.navigate(Routes.reviewPost(it)) },
                onOpenSchedules = { navController.navigate(Routes.SCHEDULES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onSwitchSpace = { navController.switchSpace(it) },
            )
        }
        composable(Routes.SCHEDULES) { SchedulesScreen(onBack = { navController.popBackStack() }) }
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
                onPicked = { postId -> navController.navigate(Routes.draft(postId)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpen = { navController.navigate(Routes.settingsPage(it)) },
            )
        }
        composable(
            Routes.CHANNEL_SETTINGS,
            arguments = listOf(navArgument("channelId") { type = NavType.StringType }),
        ) { entry ->
            ChannelSettingsScreen(
                channelId = entry.arguments?.getString("channelId").orEmpty(),
                onBack = { navController.popBackStack() },
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
                SettingsPageScreen(
                    page = page,
                    onBack = { navController.popBackStack() },
                    onOpenChannel = { navController.navigate(Routes.channelSettings(it)) },
                )
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
