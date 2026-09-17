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
package app.fediferry.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

/** Downloads media a [LinkResolver] pointed at. */
fun interface MediaFetcher {
    suspend fun fetch(url: String): Result<ByteArray>
}

/**
 * Refuses anything implausibly large before reading it: no instance accepts an
 * attachment near this size, so downloading one would only waste the user's data
 * to fail later.
 */
private const val MAX_MEDIA_BYTES = 40L * 1024 * 1024

class OkHttpMediaFetcher(private val http: OkHttpClient) : MediaFetcher {

    override suspend fun fetch(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "FediFerry (+https://github.com/merloto9/fediferry)")
                .get()
                .build()

            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Media server returned ${response.code}" }
                val body = response.body
                val declared = body.contentLength()
                check(declared <= MAX_MEDIA_BYTES) { "Attachment is too large ($declared bytes)" }

                // Read with a ceiling rather than trusting Content-Length, which a
                // server may omit or understate. InputStream.readNBytes is API 33
                // and minSdk is 26, so this is done by hand.
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        buffer.write(chunk, 0, read)
                        check(buffer.size() <= MAX_MEDIA_BYTES) { "Attachment is too large" }
                    }
                }
                val bytes = buffer.toByteArray()
                check(bytes.isNotEmpty()) { "Media server returned nothing" }
                bytes
            }
        }
    }
}
