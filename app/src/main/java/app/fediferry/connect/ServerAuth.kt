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

import app.fediferry.api.Api
import app.fediferry.client.ServerClient
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the project token to requests for the connected server's API — and to
 * nothing else. The app shares one HTTP client (pictures load through it too),
 * so the token must never reach another host.
 */
object ServerAuth : Interceptor {

    @Volatile var connection: ServerConnection? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = connection ?: return chain.proceed(request)
        if (request.header("Authorization") != null) return chain.proceed(request)
        val base = ServerClient.normalise(current.address) ?: return chain.proceed(request)
        val url = request.url
        val ours = url.host == base.host && url.port == base.port && url.encodedPath.contains(Api.PREFIX + "/")
        if (!ours) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer ${current.token}")
                .header(Api.DEVICE_HEADER, current.deviceId)
                .build(),
        )
    }
}
