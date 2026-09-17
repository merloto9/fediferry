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
package app.fediferry.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A connected Mastodon account. The access token is *not* stored here — it lives
 * in [app.fediferry.data.TokenStore], backed by EncryptedSharedPreferences.
 */
@Entity(tableName = "accounts")
data class Account(
    /** "$instance/$acct", stable across re-auth of the same handle. */
    @PrimaryKey val id: String,
    /** Host only, no scheme: "mastodon.social". */
    val instance: String,
    val acct: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isDefault: Boolean = false,
)

/** Per-instance OAuth client credentials, obtained from `POST /api/v1/apps`. */
@Entity(tableName = "instance_apps")
data class InstanceApp(
    @PrimaryKey val instance: String,
    val clientId: String,
    val clientSecret: String,
)
