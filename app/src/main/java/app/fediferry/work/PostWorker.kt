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

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.fediferry.alt.AltTextProvider
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.di.ServiceLocator
import app.fediferry.mastodon.MastodonException

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

        Notifications.cancel(app, itemId)
        repo.update(item.copy(status = Status.POSTING, failureReason = null))

        return try {
            val posted = send(item)
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
            Notifications.showResult(app, itemId, "Posted", posted.url ?: "Sent to Mastodon")
            Result.success()
        } catch (e: MastodonException) {
            fail(item, e.message ?: "Posting failed", retry = e.retryable)
        } catch (e: Exception) {
            fail(item, e.message ?: "Posting failed", retry = false)
        }
    }

    private data class Sent(
        val url: String?,
        val altText: String?,
        val altTextFailed: Boolean,
    )

    private suspend fun send(item: Item): Sent {
        val app = applicationContext
        val repo = ServiceLocator.items(app)
        val accounts = ServiceLocator.database(app).accounts()
        val tokens = ServiceLocator.tokens(app)
        val client = ServiceLocator.mastodon(app)

        val account = item.accountId?.let { accounts.byId(it) }
            ?: accounts.defaultAccount()
            ?: throw MastodonException("No Mastodon account is connected")
        val token = tokens.get(account.id)
            ?: throw MastodonException("No access token — reconnect @${account.acct}")

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
                fileName = (item.mediaHash ?: item.id) + ".jpg",
                description = altText,
            )
            mediaIds += attachment.id
        }

        if (item.bodyText.isBlank() && mediaIds.isEmpty()) {
            throw MastodonException("Nothing to post — no text and no image")
        }

        val status = client.postStatus(
            instance = account.instance,
            token = token,
            text = item.bodyText,
            mediaIds = mediaIds,
            visibility = item.visibility,
            contentWarning = item.contentWarning,
            // Derived from the item id: a retry after an ambiguous failure
            // resolves to the same post rather than a second one.
            idempotencyKey = item.id,
        )
        return Sent(status.url, altText, altFailed)
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
        if (retry && runAttemptCount < MAX_ATTEMPTS) {
            repo.update(item.copy(status = Status.QUEUED, failureReason = reason))
            return Result.retry()
        }
        repo.update(item.copy(status = Status.FAILED, failureReason = reason))
        Notifications.showResult(app, item.id, "Post failed", reason)
        return Result.failure()
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        private const val MAX_ATTEMPTS = 5
    }
}
