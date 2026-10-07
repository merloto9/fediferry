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
package app.fediferry.share

import app.fediferry.library.UploadWorker
import app.fediferry.api.IngestModes
import app.fediferry.i18n.AppLocale
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.EditNote
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import app.fediferry.MainActivity
import app.fediferry.R
import app.fediferry.data.ItemRepository
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.di.ServiceLocator
import app.fediferry.log.DebugLog
import app.fediferry.ui.LoadingScrim
import app.fediferry.ui.theme.FediFerryTheme
import app.fediferry.work.Notifications
import app.fediferry.work.PostScheduler
import kotlinx.coroutines.launch

/**
 * The single entry point for every share. Ingests and persists first, then
 * branches on the mode — that is the only thing the three modes disagree about.
 *
 * Invisible: it has no UI of its own and finishes as soon as the pipeline has
 * been handed off, so the share sheet dismisses immediately. The one exception
 * is fetching the picture behind a link, which can take seconds; that shows a
 * card saying so over the app the share came from.
 */
class ShareReceiverActivity : ComponentActivity() {

    // Before Android 13 the app's own language choice is applied here.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val mode = resolveMode(intent)
        val payload = SharePayload.from(intent)

        if (payload.isEmpty) {
            toast(getString(R.string.receiver_nothing_shareable))
            finish()
            return
        }

        ShortcutManagerCompat.reportShortcutUsed(this, mode.shortcutId)
        // Shape only — how many pictures, whether a link came along, from
        // which app. Never the text, never the link itself.
        DebugLog.d(
            LOG,
            "$mode share from ${callingPackage ?: referrer?.host ?: "an unknown app"}: " +
                "${payload.imageUris.size} image(s), link ${if (payload.link == null) "no" else "yes"}",
        )

        // Connected to a FediFerry server: a share kept for later goes to the
        // project's library there, and Compose starts a draft there. Post now
        // goes through the server's queue, with the undo window before it.
        val server = ServiceLocator.serverConnections(this).current()
        if (server != null && mode == ShareMode.COMPOSE) {
            composeOnServer(payload, server.projectName)
            return
        }
        if (server != null && mode == ShareMode.POST_NOW) {
            postNowOnServer(payload, server.projectName)
            return
        }
        if (server != null && mode == ShareMode.SAVE_FOR_LATER) {
            lifecycleScope.launch {
                runCatching {
                    ServiceLocator.pendingShares(this@ShareReceiverActivity)
                        .add(IngestModes.SAVE, payload.imageUris, payload.link, payload.text)
                }.onSuccess { shares ->
                    UploadWorker.enqueue(this@ShareReceiverActivity)
                    toast(resources.getQuantityString(R.plurals.receiver_to_library, shares.size, shares.size, server.projectName))
                }.onFailure { error ->
                    DebugLog.w(LOG, "Keeping a share for the server failed", error)
                    toast(getString(R.string.receiver_read_failed, error.describe()))
                }
                finish()
            }
            return
        }

        lifecycleScope.launch {
            val repo = ServiceLocator.items(this@ShareReceiverActivity)
            repo.seedIfEmpty()

            val result = repo.ingest(
                payload = payload,
                status = if (mode == ShareMode.POST_NOW) Status.QUEUED else Status.DRAFT,
            )

            result.fold(
                onSuccess = { ingested -> handle(mode, ingested) },
                onFailure = { error ->
                    // The whole throwable goes to the log: a bare `message` is
                    // useless for the errors that actually occur here (a
                    // NoClassDefFoundError's message is just the mangled class
                    // name). The payload itself is never logged.
                    Log.e(TAG, "Ingest failed for a $mode share", error)
                    DebugLog.w(LOG, "Ingest failed for a $mode share", error)
                    toast(getString(R.string.receiver_read_failed, error.describe()))
                    finish()
                },
            )
        }
    }

    /**
     * Keeps the share like any other, sends it straight away and opens the
     * draft the server starts from it. Without a connection it waits with the
     * other shares, and the draft is made when it goes up.
     */
    private fun composeOnServer(payload: SharePayload, project: String) = lifecycleScope.launch {
        val shares = runCatching {
            ServiceLocator.pendingShares(this@ShareReceiverActivity).add(IngestModes.COMPOSE, payload.imageUris, payload.link, payload.text)
        }.getOrElse { error ->
            DebugLog.w(LOG, "Keeping a share for the server failed", error)
            toast(getString(R.string.receiver_read_failed, error.describe()))
            finish()
            return@launch
        }
        setContent {
            FediFerryTheme {
                LoadingScrim(
                    title = getString(R.string.receiver_drafting_title, project),
                    detail = getString(R.string.receiver_drafting_detail),
                    icon = Icons.Outlined.EditNote,
                )
            }
        }
        val connections = ServiceLocator.serverConnections(this@ShareReceiverActivity)
        val client = connections.current()?.let(connections::client)
        val drafts = runCatching { shares.mapNotNull { share -> client?.let { UploadWorker.send(this@ShareReceiverActivity, it, share) } } }
            .onFailure { DebugLog.w(LOG, "Starting a draft on the server failed: ${(it as? app.fediferry.client.ServerException)?.code}") }
            .getOrNull()
        val first = drafts?.firstOrNull()
        if (first == null) {
            UploadWorker.enqueue(this@ShareReceiverActivity)
            toast(getString(R.string.receiver_draft_later, project))
        } else {
            if (drafts.size > 1) toast(resources.getQuantityString(R.plurals.receiver_drafts_made, drafts.size, drafts.size))
            startActivity(
                Intent(this@ShareReceiverActivity, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_EDIT_POST)
                    .putExtra(MainActivity.EXTRA_POST_ID, first)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }

    /**
     * Post now, by way of the server: the share goes up, becomes a draft, is
     * checked and made ready, and is queued for the end of the undo window.
     * A post the channel would not take opens in the editor instead. Without
     * a connection it waits, and goes out once it is up.
     */
    private fun postNowOnServer(payload: SharePayload, project: String) = lifecycleScope.launch {
        val shares = runCatching {
            ServiceLocator.pendingShares(this@ShareReceiverActivity).add(IngestModes.POST_NOW, payload.imageUris, payload.link, payload.text)
        }.getOrElse { error ->
            DebugLog.w(LOG, "Keeping a share for the server failed", error)
            toast(getString(R.string.receiver_read_failed, error.describe()))
            finish()
            return@launch
        }
        setContent {
            FediFerryTheme {
                LoadingScrim(
                    title = getString(R.string.receiver_posting_title, project),
                    detail = getString(R.string.receiver_drafting_detail),
                    icon = Icons.Outlined.EditNote,
                )
            }
        }
        val context = this@ShareReceiverActivity
        val connections = ServiceLocator.serverConnections(context)
        val client = connections.current()?.let(connections::client)
        val delay = ServiceLocator.settings(context).current().undoDelaySeconds
        val outcomes = runCatching {
            shares.mapNotNull { share ->
                client?.let { api -> UploadWorker.send(context, api, share)?.let { UploadWorker.postNow(api, it, delay) } }
            }
        }.onFailure { DebugLog.w(LOG, "Posting now through the server failed: ${(it as? app.fediferry.client.ServerException)?.code}") }
            .getOrNull()
        when {
            outcomes.isNullOrEmpty() -> {
                UploadWorker.enqueue(context)
                toast(getString(R.string.receiver_post_later, project))
            }
            else -> {
                outcomes.filterIsInstance<UploadWorker.PostNowOutcome.Queued>().forEach { q ->
                    if (delay > 0) Notifications.showUndo(context, q.postId, delay, onServer = true)
                }
                val notReady = outcomes.filterIsInstance<UploadWorker.PostNowOutcome.NotReady>().firstOrNull()
                if (notReady != null) {
                    val why = notReady.violations.firstOrNull()?.let { app.fediferry.drafts.ServerMessages.violation(resources, it) }.orEmpty()
                    toast(getString(R.string.receiver_post_not_ready, why))
                    startActivity(
                        Intent(context, MainActivity::class.java)
                            .setAction(MainActivity.ACTION_EDIT_POST)
                            .putExtra(MainActivity.EXTRA_POST_ID, notReady.postId)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    )
                } else {
                    toast(if (delay > 0) getString(R.string.receiver_posting_in, delay) else getString(R.string.receiver_posting))
                }
            }
        }
        finish()
    }

    private suspend fun handle(mode: ShareMode, shared: ItemRepository.Ingested) {
        // First, the link loses whatever says who shared it. Everything after
        // compares against this item, so "was media fetched?" stays accurate.
        val original = shared.copy(item = cleanLink(shared.item))
        // A link-only share may carry media we can fetch. This is a no-op for
        // Instagram and for anything already carrying an image.
        val resolved = resolveLink(original.item)
        val ingested = if (resolved === original.item) original else original.copy(item = resolved)
        val itemId = ingested.item.id
        when (mode) {
            ShareMode.POST_NOW -> {
                // Only a screenshot needs trimming; fetched media arrives cropped.
                if (resolved === original.item) {
                    val trimmed = autoCropped(ingested.item)
                    ServiceLocator.items(this).applyDefaultCleanup(trimmed)
                }
                val delay = ServiceLocator.settings(this).current().undoDelaySeconds
                if (delay > 0) Notifications.showUndo(this, itemId, delay)
                PostScheduler.enqueue(this, itemId, delay * 1000L)
                toast(if (delay > 0) getString(R.string.receiver_posting_in, delay) else getString(R.string.receiver_posting))
                finish()
            }

            ShareMode.COMPOSE -> {
                // Offer the trim step only for a screenshot with something to
                // trim. Media fetched from a link is already exactly the
                // picture, and running the screenshot detector over it proposes
                // a crop through the middle of the meme.
                val trim = resolved === original.item &&
                    ingested.item.mediaPath != null &&
                    ServiceLocator.items(this).suggestCrop(ingested.item) != null
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setAction(if (trim) MainActivity.ACTION_CROP else MainActivity.ACTION_EDIT)
                        .putExtra(MainActivity.EXTRA_ITEM_ID, itemId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                finish()
            }

            ShareMode.SAVE_FOR_LATER -> {
                var trimmed = false
                if (resolved === original.item) {
                    val after = autoCropped(ingested.item)
                    trimmed = after !== ingested.item
                    ServiceLocator.items(this).applyDefaultCleanup(after)
                }
                toast(
                    when {
                        resolved !== original.item -> getString(R.string.receiver_saved_with_link_image)
                        trimmed -> getString(R.string.receiver_saved_trimmed, ingested.describe())
                        else -> ingested.describe()
                    },
                )
                finish()
            }
        }
    }

    /**
     * Direct-share taps carry the mode as an extra. A plain "Open with FediFerry"
     * tap carries nothing, and defaults to the editor: the mode was not chosen,
     * so do not assume the irreversible one.
     */
    private fun resolveMode(intent: Intent): ShareMode =
        ShareMode.fromName(intent.getStringExtra(ShareMode.EXTRA))
            ?: ShareMode.fromShortcutId(intent.getStringExtra(EXTRA_SHORTCUT_ID))
            ?: ShareMode.COMPOSE

    /**
     * Takes the sharer out of a link whose service puts them in it — a
     * Pinterest link names who sent it. Done even with fetching from links
     * switched off: it protects the person sharing, it fetches no content.
     * When a clean link cannot be confirmed the shared one is kept, and the
     * user is told.
     */
    private suspend fun cleanLink(item: Item): Item {
        val repo = ServiceLocator.items(this)
        val service = repo.linkCleaningService(item) ?: return item
        setContent {
            FediFerryTheme {
                LoadingScrim(
                    title = getString(R.string.receiver_cleaning_title, service),
                    detail = getString(R.string.receiver_cleaning_detail),
                    icon = Icons.Outlined.Link,
                )
            }
        }
        val cleaned = repo.cleanLink(item)
        if (cleaned.linkMayIdentify) {
            Toast.makeText(
                applicationContext,
                getString(R.string.receiver_link_may_identify, service),
                Toast.LENGTH_LONG,
            ).show()
        }
        return cleaned
    }

    /**
     * Fetches the media behind a shared link when the service publishes one.
     * Silent on failure by design — the screenshot path remains the fallback.
     */
    private suspend fun resolveLink(item: Item): Item {
        if (item.mediaPath != null || item.sourceUrl == null) return item
        if (!ServiceLocator.settings(this).current().resolveLinks) return item
        val repo = ServiceLocator.items(this)
        val service = repo.resolvingService(item) ?: return item
        setContent {
            FediFerryTheme {
                LoadingScrim(
                    title = getString(R.string.receiver_fetching_title, service),
                    detail = getString(R.string.receiver_fetching_detail, service),
                    icon = Icons.Outlined.Link,
                )
            }
        }
        return repo.resolveLinkMedia(item)
    }

    /**
     * Trims a screenshot without asking, for the modes that do not stop for
     * input. Only a confident suggestion is applied, and the untouched
     * screenshot is kept, so the editor can always put it back.
     *
     * @return the trimmed item, or the original one when nothing was trimmed.
     */
    private suspend fun autoCropped(item: Item): Item {
        if (item.mediaPath == null) return item
        if (!ServiceLocator.settings(this).current().autoCrop) return item

        val repo = ServiceLocator.items(this)
        val suggestion = repo.suggestCrop(item) ?: return item
        if (suggestion.confidence < AUTO_CROP_MIN_CONFIDENCE) return item

        return repo.applyCrop(item, suggestion.crop)
            .onFailure { Log.e(TAG, "Auto-crop failed", it) }
            .getOrDefault(item)
    }

    /**
     * Says what actually happened. A permalink on its own is the common
     * Instagram case and looks like a no-op otherwise — the user cannot tell
     * that the post still has no image.
     */
    private fun ItemRepository.Ingested.describe(): String = when (outcome) {
        ItemRepository.Ingested.Outcome.PAIRED -> getString(R.string.receiver_paired)
        ItemRepository.Ingested.Outcome.DUPLICATE -> getString(R.string.receiver_duplicate)
        ItemRepository.Ingested.Outcome.AWAITING_MEDIA -> getString(R.string.receiver_awaiting_media)
        ItemRepository.Ingested.Outcome.CREATED -> getString(R.string.receiver_saved)
    }

    private fun toast(message: String) =
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()

    /**
     * Class name plus message. Errors thrown by the class loader carry only a
     * mangled name as their message, so the message alone says nothing.
     */
    private fun Throwable.describe(): String {
        val root = generateSequence(this) { it.cause }.last()
        val name = root::class.java.simpleName
        return root.message?.takeIf { it.isNotBlank() }?.let { "$name: $it" } ?: name
    }

    private companion object {
        const val TAG = "ShareReceiver"

        private const val LOG = "share"

        /**
         * Below this the suggestion is not trustworthy enough to apply behind
         * the user's back; the image is left whole instead.
         */
        const val AUTO_CROP_MIN_CONFIDENCE = 0.72f

        /** Set by the launcher when a Sharing Shortcut is tapped. */
        const val EXTRA_SHORTCUT_ID = "android.intent.extra.shortcut.ID"
    }
}
