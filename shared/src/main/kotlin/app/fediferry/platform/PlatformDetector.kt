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
package app.fediferry.platform

import app.fediferry.api.Platform
import app.fediferry.mastodon.MastodonAccounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Tells which fediverse software runs an instance, from the nodeinfo every
 * one of them publishes: `/.well-known/nodeinfo` names the document, whose
 * `software.name` says "mastodon", "pixelfed" and so on.
 */
class PlatformDetector(private val http: OkHttpClient) {

    /** What runs at [instance]: a known [Platform], or null with the [software] name it gave. */
    data class Detected(val software: String, val platform: Platform?)

    /** @throws PlatformException when the instance cannot be reached or does not say. */
    suspend fun detect(instance: String): Detected = withContext(Dispatchers.IO) {
        val host = MastodonAccounts.normaliseInstance(instance)
        val index = read("https://$host/.well-known/nodeinfo")
        val href = documentUrl(index) ?: throw PlatformException("platform.undetected")
        val software = softwareName(read(href)) ?: throw PlatformException("platform.undetected")
        Detected(software, Platform.ofSoftware(software))
    }

    private fun read(url: String): JsonObject {
        val target = url.toHttpUrlOrNull() ?: throw PlatformException("platform.undetected")
        val response = try {
            http.newCall(Request.Builder().url(target).header("Accept", "application/json").get().build()).execute()
        } catch (e: IOException) {
            throw PlatformException("platform.unreachable", e)
        }
        return response.use {
            if (!it.isSuccessful) throw PlatformException("platform.undetected")
            runCatching { json.decodeFromString<JsonObject>(it.body.string()) }.getOrElse { throw PlatformException("platform.undetected", it) }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The newest nodeinfo document the index links to. */
        fun documentUrl(index: JsonObject): String? = runCatching {
            index["links"]!!.jsonArray.map { it.jsonObject }
                .filter { it["rel"]?.jsonPrimitive?.content?.contains("nodeinfo.diaspora.software/ns/schema/") == true }
                .maxByOrNull { it["rel"]!!.jsonPrimitive.content.substringAfterLast('/') }
                ?.get("href")?.jsonPrimitive?.content
        }.getOrNull()

        fun softwareName(document: JsonObject): String? =
            runCatching { document["software"]!!.jsonObject["name"]!!.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/** Detecting a platform failed; [message] is a stable code. */
class PlatformException(code: String, cause: Throwable? = null) : Exception(code, cause)
