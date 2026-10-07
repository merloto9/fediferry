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
package app.fediferry.mastodon

import app.fediferry.i18n.AppLocale
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.lifecycle.lifecycleScope
import app.fediferry.MainActivity
import app.fediferry.R
import app.fediferry.di.ServiceLocator
import app.fediferry.ui.LoadingScrim
import app.fediferry.ui.theme.FediFerryTheme
import kotlinx.coroutines.launch

/**
 * Receives `fediferry://oauth?code=…&state=…` from the Custom Tab, finishes the
 * token exchange and drops the user back into the app.
 */
class OAuthRedirectActivity : ComponentActivity() {

    // Before Android 13 the app's own language choice is applied here.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data

        val code = data?.getQueryParameter("code")
        val state = data?.getQueryParameter("state")
        val error = data?.getQueryParameter("error")

        if (code == null || state == null) {
            ServiceLocator.auth(this).cancel()
            toast(error?.let { getString(R.string.auth_failed, it) } ?: getString(R.string.auth_cancelled))
            openApp()
            return
        }

        setContent {
            FediFerryTheme {
                LoadingScrim(
                    title = getString(R.string.auth_finishing_title),
                    detail = getString(R.string.auth_finishing_detail),
                    icon = Icons.AutoMirrored.Outlined.Login,
                )
            }
        }

        lifecycleScope.launch {
            val result = ServiceLocator.auth(this@OAuthRedirectActivity)
                .completeAuthorization(code, state)
            toast(
                result.fold(
                    onSuccess = { getString(R.string.auth_connected, it.acct) },
                    onFailure = { getString(R.string.auth_failed, it.message.orEmpty()) },
                ),
            )
            openApp()
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
