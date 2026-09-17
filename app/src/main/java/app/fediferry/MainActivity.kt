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

    /** Item to trim first, when the share brought an untrimmed screenshot. */
    private val cropItemId = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        editItemId.value = intent.editTarget()
        cropItemId.value = intent.cropTarget()
        requestNotificationPermission()

        setContent {
            FediFerryTheme {
                val edit by editItemId
                val crop by cropItemId
                FediFerryNavHost(
                    navController = rememberNavController(),
                    editItemId = edit,
                    cropItemId = crop,
                    onEditConsumed = {
                        editItemId.value = null
                        cropItemId.value = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.editTarget()?.let { editItemId.value = it }
        intent.cropTarget()?.let { cropItemId.value = it }
    }

    private fun Intent.editTarget(): String? =
        takeIf { it.action == ACTION_EDIT }?.getStringExtra(EXTRA_ITEM_ID)

    private fun Intent.cropTarget(): String? =
        takeIf { it.action == ACTION_CROP }?.getStringExtra(EXTRA_ITEM_ID)

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
        const val ACTION_CROP = "app.fediferry.action.CROP"
        const val EXTRA_ITEM_ID = "app.fediferry.extra.ITEM_ID"
    }
}
