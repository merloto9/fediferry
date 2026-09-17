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
