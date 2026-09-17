package app.fediferry

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.rememberNavController
import app.fediferry.ui.FediFerryNavHost
import app.fediferry.ui.theme.FediFerryTheme

class MainActivity : ComponentActivity() {

    /** Item to open the editor on, set by a Compose-mode share. */
    private val editItemId = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        editItemId.value = intent.editTarget()
        requestNotificationPermission()

        setContent {
            FediFerryTheme {
                val target by editItemId
                FediFerryNavHost(
                    navController = rememberNavController(),
                    editItemId = target,
                    onEditConsumed = { editItemId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.editTarget()?.let { editItemId.value = it }
    }

    private fun Intent.editTarget(): String? =
        takeIf { it.action == ACTION_EDIT }?.getStringExtra(EXTRA_ITEM_ID)

    /**
     * Without this the undo notification is silently dropped on 33+, which would
     * make "post now" genuinely un-undoable. The post itself does not depend on
     * the grant, so a refusal is not fatal.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val ACTION_EDIT = "app.fediferry.action.EDIT"
        const val EXTRA_ITEM_ID = "app.fediferry.extra.ITEM_ID"
    }
}
