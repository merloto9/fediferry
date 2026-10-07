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
package app.fediferry.work

import app.fediferry.R
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.fediferry.alt.AltTextProvider
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.Hashtag
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.di.ServiceLocator
import app.fediferry.log.DebugLog
import app.fediferry.mastodon.MastodonException
import app.fediferry.template.TemplateEngine


/**
 * Sends one item to Mastodon.
 *
 * Every remote step that is not the post itself degrades to nothing rather than
 * aborting: alt text that cannot be resolved means a post without a description,
 * flagged on the item so the user can fill it in afterwards.
 *
 * Neither the access token nor the post body is ever logged.
 */
class PostWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val app = applicationContext
        val repo = ServiceLocator.items(app)
        val item = repo.byId(itemId) ?: return Result.success() // deleted while queued

        if (item.status == Status.POSTED) return Result.success()

        // Set only when Mastodon is to publish the post later; it is not stored.
        val scheduledAt = inputData.getLong(KEY_SCHEDULED_AT, 0L).takeIf { it > 0 }
        if (scheduledAt != null && scheduledAt - System.currentTimeMillis() < MIN_SCHEDULE_LEAD_MS) {
            // Mastodon refuses a time under five minutes away. Say so, rather
            // than posting at once when the upload waited too long for a network.
            return fail(item, app.getString(R.string.schedule_too_soon_failure), retry = false)
        }

        Notifications.cancel(app, itemId)
        repo.update(item.copy(status = Status.POSTING, failureReason = null))

        return try {
            val posted = send(item, scheduledAt)
            // A scheduled post has left the inbox: Mastodon holds it, and the
            // Queue shows it from there. Only the hand-over is recorded here.
            repo.update(
                item.copy(
                    status = Status.POSTED,
                    postedAt = System.currentTimeMillis(),
                    statusUrl = posted.url,
                    altText = posted.altText ?: item.altText,
                    altTextFailed = posted.altTextFailed,
                    failureReason = null,
                ),
            )
            DebugLog.d(
                LOG,
                "${if (scheduledAt != null) "Scheduled" else "Posted"} item $itemId" +
                    if (posted.altTextFailed) " (alt text failed)" else "",
            )
            recordHashtags(posted.text)
            if (scheduledAt != null) {
                Notifications.showResult(
                    app,
                    itemId,
                    app.getString(R.string.schedule_notification_title),
                    app.getString(R.string.schedule_notification_text, ScheduleWords.whenText(app.resources, scheduledAt)),
                )
            } else {
                Notifications.showResult(
                    app,
                    itemId,
                    app.getString(R.string.post_posted),
                    posted.url ?: app.getString(R.string.post_sent),
                )
            }
            Result.success()
        } catch (e: MastodonException) {
            fail(item, e.message ?: app.getString(R.string.post_failed_reason), retry = e.retryable)
        } catch (e: Exception) {
            fail(item, e.message ?: app.getString(R.string.post_failed_reason), retry = false)
        }
    }

    private data class Sent(
        val url: String?,
        val altText: String?,
        val altTextFailed: Boolean,
        /** Exactly what was posted, `{tags}` filled. */
        val text: String,
    )

    private suspend fun send(item: Item, scheduledAt: Long?): Sent {
        val app = applicationContext
        val repo = ServiceLocator.items(app)
        val accounts = ServiceLocator.database(app).accounts()
        val tokens = ServiceLocator.tokens(app)
        val client = ServiceLocator.mastodon(app)

        val account = item.accountId?.let { accounts.byId(it) }
            ?: accounts.defaultAccount()
            ?: throw MastodonException(app.getString(R.string.post_no_account))
        val token = tokens.get(account.id)
            ?: throw MastodonException(app.getString(R.string.post_no_token, account.acct))

        var altText = item.altText
        var altFailed = false
        val mediaIds = mutableListOf<String>()

        val bytes = repo.mediaBytes(item)
        if (bytes != null) {
            if (altText.isNullOrBlank()) {
                val resolved = resolveAltText(item, bytes)
                altText = resolved.getOrNull()
                // NONE is a choice, not a failure; only a real attempt can fail.
                altFailed = resolved.isFailure && altModeOf(item) != AltTextMode.NONE
            }
            val attachment = client.uploadMedia(
                instance = account.instance,
                token = token,
                bytes = bytes,
                mimeType = item.mimeType ?: "image/jpeg",
                fileName = (item.mediaHash ?: item.id) + extensionFor(item.mimeType),
                description = altText,
            )
            mediaIds += attachment.id
        }

        // {tags} is filled only now, so the hashtags picked last are the ones posted.
        val text = TemplateEngine.postTextOf(item)
        if (text.isBlank() && mediaIds.isEmpty()) {
            throw MastodonException(app.getString(R.string.post_nothing_to_post))
        }

        val status = client.postStatus(
            instance = account.instance,
            token = token,
            text = text,
            mediaIds = mediaIds,
            visibility = item.visibility,
            contentWarning = item.contentWarning,
            // Derived from the item id: a retry after an ambiguous failure
            // resolves to the same post rather than a second one. A schedule
            // has its own, so scheduling after a failed one is not a replay.
            idempotencyKey = if (scheduledAt == null) item.id else "${item.id}@$scheduledAt",
            scheduledAtIso = scheduledAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
        )
        return Sent(status.url, altText, altFailed, text)
    }

    /**
     * Counts the hashtags a post actually went out with, and adds new ones to
     * the list when the user asked for that. Only after the post succeeded,
     * so a draft that never sends counts for nothing — and never at the post's
     * expense: it is already out, so a failure here is only logged.
     */
    private suspend fun recordHashtags(text: String) {
        runCatching {
            // Counted always: the editor offers the most used hashtags first.
            ServiceLocator.database(applicationContext).hashtagUsage()
                .countUse(Hashtags.inText(text).map(Hashtags::key))
            if (!ServiceLocator.settings(applicationContext).current().rememberSentHashtags) return
            val dao = ServiceLocator.database(applicationContext).hashtags()
            var next = dao.count()
            Hashtags.newIn(text, dao.all().map { it.tag }).forEach { tag ->
                dao.insert(Hashtag(tag, sortOrder = next++))
            }
        }.onFailure { DebugLog.w(LOG, "Could not record the sent hashtags", it) }
    }

    private suspend fun altModeOf(item: Item): AltTextMode =
        ServiceLocator.items(applicationContext).resolveTemplate(item.templateId).altTextMode

    private suspend fun resolveAltText(item: Item, bytes: ByteArray): kotlin.Result<String> {
        val provider: AltTextProvider = ServiceLocator.altTextProvider(
            applicationContext,
            ServiceLocator.items(applicationContext).resolveTemplate(item.templateId),
        )
        return provider.describe(bytes, item.mimeType ?: "image/jpeg")
    }

    private suspend fun fail(item: Item, reason: String, retry: Boolean): Result {
        val app = applicationContext
        val repo = ServiceLocator.items(app)
        DebugLog.w(LOG, "Item ${item.id} failed on attempt $runAttemptCount, retryable=$retry: $reason")
        if (retry && runAttemptCount < MAX_ATTEMPTS) {
            repo.update(item.copy(status = Status.QUEUED, failureReason = reason))
            return Result.retry()
        }
        repo.update(item.copy(status = Status.FAILED, failureReason = reason))
        Notifications.showResult(app, item.id, app.getString(R.string.post_failed_title), reason)
        return Result.failure()
    }

    /**
     * Instances key off the filename as well as the content type, so a resolved
     * 9GAG animation must not be uploaded as ".jpg".
     */
    private fun extensionFor(mimeType: String?): String = when (mimeType) {
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        "video/mp4" -> ".mp4"
        "video/webm" -> ".webm"
        else -> ".jpg"
    }

    companion object {
        private const val LOG = "post"

        const val KEY_ITEM_ID = "item_id"
        const val KEY_SCHEDULED_AT = "scheduled_at"

        /** Mastodon's five minutes, and a little for the upload itself. */
        const val MIN_SCHEDULE_LEAD_MS = 5 * 60_000L + 15_000L
        private const val MAX_ATTEMPTS = 5
    }
}
