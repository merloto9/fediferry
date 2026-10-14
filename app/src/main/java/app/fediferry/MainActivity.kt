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
package app.fediferry

import app.fediferry.i18n.AppLocale
import android.content.Context
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

    // Before Android 13 the app's own language choice is applied here.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    /** A draft to open, made by a Compose-mode share. */
    private val editPostId = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        editPostId.value = intent.draftTarget()
        requestNotificationPermission()

        setContent {
            FediFerryTheme {
                val draft by editPostId
                FediFerryNavHost(
                    navController = rememberNavController(),
                    editPostId = draft,
                    onEditConsumed = { editPostId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.draftTarget()?.let { editPostId.value = it }
    }

    private fun Intent.draftTarget(): String? =
        takeIf { it.action == ACTION_EDIT_POST }?.getStringExtra(EXTRA_POST_ID)

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
        const val ACTION_EDIT_POST = "app.fediferry.action.EDIT_POST"
        const val EXTRA_POST_ID = "app.fediferry.extra.POST_ID"
    }
}
