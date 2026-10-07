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
package app.fediferry.channel

import app.fediferry.mastodon.MastodonException
import app.fediferry.mastodon.MediaAttachment
import app.fediferry.mastodon.PostedStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** One picture to go with a post, with its description. */
class PublishMedia(val bytes: ByteArray, val mime: String, val altText: String?)

/** Everything a channel needs to put one post out. */
class PublishJob(
    val instance: String,
    val token: String,
    val text: String,
    val contentWarning: String?,
    /** public, unlisted, private or direct. */
    val visibility: String,
    val media: List<PublishMedia>,
    /** The same for every try of one post, so an instance that saw it once does not post it twice. */
    val idempotencyKey: String,
)

/** Where the post ended up. */
data class Published(val remoteId: String, val url: String?)

/**
 * Puts a post out on one kind of channel. Fails with a [MastodonException]
 * whose message is a stable code and whose `retryable` says whether trying
 * again later can help.
 */
fun interface ChannelPublisher {
    suspend fun publish(job: PublishJob): Published
}

/**
 * Posts to Mastodon: each picture is uploaded with its description, waited
 * for until the instance has processed it, then the status goes out with an
 * `Idempotency-Key`, so a try after an ambiguous failure cannot post twice.
 */
class MastodonPublisher(private val http: OkHttpClient) : ChannelPublisher {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun publish(job: PublishJob): Published = withContext(Dispatchers.IO) {
        val mediaIds = job.media.mapIndexed { i, m -> upload(job, m, i) }
        val form = FormBody.Builder()
            .add("status", job.text)
            .add("visibility", job.visibility)
            .apply {
                mediaIds.forEach { add("media_ids[]", it) }
                if (!job.contentWarning.isNullOrBlank()) add("spoiler_text", job.contentWarning)
            }
            .build()
        val request = Request.Builder().url(url(job.instance, "api/v1/statuses"))
            .header("Authorization", "Bearer ${job.token}")
            .header("Idempotency-Key", job.idempotencyKey)
            .post(form).build()
        val status = decode<PostedStatus>(execute(request, setOf(200)))
        Published(status.id, status.url)
    }

    private suspend fun upload(job: PublishJob, media: PublishMedia, index: Int): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "picture-${index + 1}.${extension(media.mime)}", media.bytes.toRequestBody(media.mime.toMediaTypeOrNull()))
            .apply { if (!media.altText.isNullOrBlank()) addFormDataPart("description", media.altText) }
            .build()
        val request = Request.Builder().url(url(job.instance, "api/v2/media"))
            .header("Authorization", "Bearer ${job.token}")
            .post(body).build()
        val response = execute(request, setOf(200, 202))
        val attachment = decode<MediaAttachment>(response)
        if (response.code == 200) return attachment.id
        // 202: accepted, still processing. Attaching it now would post without the picture.
        var wait = FIRST_POLL_MS
        repeat(POLLS) {
            delay(wait)
            val poll = execute(
                Request.Builder().url(url(job.instance, "api/v1/media/${attachment.id}"))
                    .header("Authorization", "Bearer ${job.token}").get().build(),
                setOf(200, 206),
            )
            poll.close()
            if (poll.code == 200) return attachment.id
            wait = (wait * 2).coerceAtMost(MAX_POLL_MS)
        }
        throw MastodonException("mastodon.media_processing", retryable = true)
    }

    private fun execute(request: Request, accept: Set<Int>): Response {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw MastodonException("mastodon.unreachable", retryable = true, cause = e)
        }
        if (response.code in accept) return response
        val code = response.code
        response.close()
        throw MastodonException(
            when (code) {
                401, 403 -> "mastodon.unauthorized"
                404 -> "mastodon.not_found"
                413 -> "mastodon.too_large"
                422 -> "mastodon.rejected"
                429 -> "mastodon.rate_limited"
                else -> "mastodon.http_$code"
            },
            code,
            retryable = code == 408 || code == 429 || code >= 500,
        )
    }

    private inline fun <reified T> decode(response: Response): T = response.use {
        try {
            json.decodeFromString(it.body.string())
        } catch (e: Exception) {
            throw MastodonException("mastodon.unreadable", it.code, retryable = true, cause = e)
        }
    }

    private fun url(instance: String, path: String): HttpUrl {
        val base = if ("://" in instance) instance else "https://$instance"
        return base.trimEnd('/').toHttpUrl().newBuilder().addPathSegments(path).build()
    }

    private fun extension(mime: String) = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        else -> mime.substringAfter('/', "bin")
    }

    private companion object {
        const val FIRST_POLL_MS = 1_000L
        const val MAX_POLL_MS = 8_000L
        const val POLLS = 8
    }
}
