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
package app.fediferry.phone

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.fediferry.R
import app.fediferry.alt.AltTextProvider
import app.fediferry.alt.NoAltTextProvider
import app.fediferry.alt.StaticAltTextProvider
import app.fediferry.data.model.Hashtag
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.Visibility
import app.fediferry.di.ServiceLocator
import app.fediferry.log.DebugLog
import app.fediferry.mastodon.MastodonException
import app.fediferry.work.Notifications
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Sends one post of the phone's project: uploads its pictures, then posts it
 * — or, with a time set, hands it to Mastodon to post then. Either way the
 * post leaves the phone afterwards. Busy or unreachable instances are tried
 * again; anything else fails the post, which stays in Publishing to retry.
 */
class PhonePublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_POST_ID) ?: return Result.failure()
        val app = applicationContext
        val phone = ServiceLocator.phone(app)
        val out = phone.outgoing(id) ?: return Result.success() // taken back, or gone
        val (account, token) = out.account ?: run {
            phone.markFailed(id, "channel.no_account", final = true)
            return Result.failure()
        }
        phone.markSending(id)
        return try {
            val client = ServiceLocator.mastodon(app)
            val mediaIds = out.pictures.map { (asset, media) ->
                val bytes = File(asset.path).readBytes()
                val alt = media.altText?.takeIf { it.isNotBlank() } ?: altFor(out.settings.altTextMode, out.settings.staticAltText, bytes, asset.mime)
                client.uploadMedia(account.instance, token, bytes, asset.mime, asset.sha256 + extensionFor(asset.mime), alt).id
            }
            val text = out.post.finalText.orEmpty()
            val scheduledAt = out.publication.scheduledAt
            client.postStatus(
                instance = account.instance,
                token = token,
                text = text,
                mediaIds = mediaIds,
                visibility = runCatching { Visibility.valueOf(out.post.visibility) }.getOrDefault(Visibility.PUBLIC),
                contentWarning = out.post.contentWarning,
                // The same post sent twice — a retry after an unclear failure — is one post.
                idempotencyKey = if (scheduledAt == null) id else "$id@$scheduledAt",
                scheduledAtIso = scheduledAt?.let { Instant.ofEpochMilli(it).toString() },
            )
            phone.markSent(id)
            recordHashtags(text)
            DebugLog.d(LOG, if (scheduledAt == null) "Posted a post" else "Handed a post to Mastodon")
            Result.success()
        } catch (e: MastodonException) {
            val retry = e.retryable && runAttemptCount < MAX_ATTEMPTS
            phone.markFailed(id, e.message ?: "mastodon.error", final = !retry)
            DebugLog.w(LOG, "Sending failed (try ${runAttemptCount + 1}, ${if (retry) "will retry" else "final"})")
            if (!retry) Notifications.showResult(app, id, app.getString(R.string.post_failed_title), e.message.orEmpty())
            if (retry) Result.retry() else Result.failure()
        } catch (e: Exception) {
            phone.markFailed(id, e.javaClass.simpleName, final = runAttemptCount >= MAX_ATTEMPTS)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /** Alt text the channel writes for a picture left without; a failure never stops the post. */
    private suspend fun altFor(mode: String, static: String?, bytes: ByteArray, mime: String): String? {
        val provider: AltTextProvider = when (mode) {
            "STATIC" -> StaticAltTextProvider(static.orEmpty())
            "VISION" -> ServiceLocator.visionProvider(applicationContext)
            else -> NoAltTextProvider
        }
        return provider.describe(bytes, mime).getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Counts the hashtags a post went out with, and adds new ones to the list when asked to. */
    private suspend fun recordHashtags(text: String) {
        runCatching {
            val db = ServiceLocator.database(applicationContext)
            db.hashtagUsage().countUse(Hashtags.inText(text).map(Hashtags::key))
            if (!ServiceLocator.settings(applicationContext).current().rememberSentHashtags) return
            var next = db.hashtags().count()
            Hashtags.newIn(text, db.hashtags().all().map { it.tag }).forEach { db.hashtags().insert(Hashtag(it, sortOrder = next++)) }
        }
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        "video/mp4" -> ".mp4"
        "video/webm" -> ".webm"
        else -> ".jpg"
    }

    companion object {
        private const val LOG = "publish"
        private const val KEY_POST_ID = "post_id"
        private const val MAX_ATTEMPTS = 5

        /** Mastodon's five minutes ahead, and a little for the upload itself. */
        const val MIN_MASTODON_LEAD_MS = 5 * 60_000L + 30_000L

        private fun name(postId: String) = "phone-post-$postId"

        fun enqueue(context: Context, postId: String, delayMillis: Long) {
            val request = OneTimeWorkRequestBuilder<PhonePublishWorker>()
                .setInputData(workDataOf(KEY_POST_ID to postId))
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(postId), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, postId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(postId))
        }
    }
}
