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
package app.fediferry.connect

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import app.fediferry.BuildConfig
import app.fediferry.api.DeviceRegistration
import app.fediferry.client.ServerClient
import app.fediferry.data.TokenStore
import okhttp3.OkHttpClient
import java.util.UUID

/** The FediFerry server this phone works with, and the project its token opens. */
data class ServerConnection(
    val address: String,
    val projectId: String,
    val projectName: String,
    val token: String,
    val deviceId: String,
)

/**
 * Where the connection is kept. The project token goes into the encrypted
 * [TokenStore]; the address, project name and this device's id are not secret
 * and sit in plain preferences.
 */
class ServerConnections(context: Context, private val tokens: TokenStore, private val http: OkHttpClient) {

    private val prefs = context.getSharedPreferences("server_connection", Context.MODE_PRIVATE)

    /** This phone's id towards every server, made once and kept. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit { putString(KEY_DEVICE, id) }
        }

    /** How this phone introduces itself, so others see who is editing: "Google Pixel 9". */
    fun registration(): DeviceRegistration = DeviceRegistration(
        id = deviceId,
        name = (if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "${Build.MANUFACTURER} ${Build.MODEL}")
            .replaceFirstChar { it.titlecase() },
        platform = "android",
        appVersion = BuildConfig.VERSION_NAME,
    )

    fun current(): ServerConnection? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val token = tokens.get(TOKEN_KEY) ?: return null
        return ServerConnection(
            address = address,
            projectId = prefs.getString(KEY_PROJECT_ID, null).orEmpty(),
            projectName = prefs.getString(KEY_PROJECT_NAME, null).orEmpty(),
            token = token,
            deviceId = deviceId,
        )
    }

    fun client(address: String, token: String): ServerClient = ServerClient(address, token, deviceId, http)

    fun client(connection: ServerConnection): ServerClient = client(connection.address, connection.token)

    fun save(connection: ServerConnection) {
        tokens.put(TOKEN_KEY, connection.token)
        prefs.edit {
            putString(KEY_ADDRESS, connection.address)
            putString(KEY_PROJECT_ID, connection.projectId)
            putString(KEY_PROJECT_NAME, connection.projectName)
        }
    }

    /** Forgets the server and its token; the device id stays, so it is the same device next time. */
    fun clear() {
        tokens.remove(TOKEN_KEY)
        prefs.edit {
            remove(KEY_ADDRESS)
            remove(KEY_PROJECT_ID)
            remove(KEY_PROJECT_NAME)
        }
    }

    private companion object {
        const val TOKEN_KEY = "server:project-token"
        const val KEY_ADDRESS = "address"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_PROJECT_NAME = "project_name"
        const val KEY_DEVICE = "device_id"
    }
}
