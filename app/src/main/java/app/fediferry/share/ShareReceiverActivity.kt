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

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import app.fediferry.MainActivity
import app.fediferry.data.ItemRepository
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.di.ServiceLocator
import app.fediferry.work.Notifications
import app.fediferry.work.PostScheduler
import kotlinx.coroutines.launch

/**
 * The single entry point for every share. Ingests and persists first, then
 * branches on the mode — that is the only thing the three modes disagree about.
 *
 * Invisible: it has no UI of its own and finishes as soon as the pipeline has
 * been handed off, so the share sheet dismisses immediately.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val mode = resolveMode(intent)
        val payload = SharePayload.from(intent)

        if (payload.isEmpty) {
            toast("Nothing shareable in that")
            finish()
            return
        }

        ShortcutManagerCompat.reportShortcutUsed(this, mode.shortcutId)

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
                    toast("Could not read the share: ${error.describe()}")
                    finish()
                },
            )
        }
    }

    private suspend fun handle(mode: ShareMode, original: ItemRepository.Ingested) {
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
                toast(if (delay > 0) "Posting in ${delay}s" else "Posting…")
                finish()
            }

            ShareMode.COMPOSE -> {
                // Offer the trim step only when there is something to trim; an
                // already-cropped image should go straight to the editor.
                val trim = ingested.item.mediaPath != null &&
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
                        resolved !== original.item -> "Saved with the image from the link"
                        trimmed -> ingested.describe() + ", trimmed"
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
     * Fetches the media behind a shared link when the service publishes one.
     * Silent on failure by design — the screenshot path remains the fallback.
     */
    private suspend fun resolveLink(item: Item): Item {
        if (item.mediaPath != null || item.sourceUrl == null) return item
        if (!ServiceLocator.settings(this).current().resolveLinks) return item
        return ServiceLocator.items(this).resolveLinkMedia(item)
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
        ItemRepository.Ingested.Outcome.PAIRED -> "Joined to your other share"
        ItemRepository.Ingested.Outcome.DUPLICATE -> "Already in the inbox"
        ItemRepository.Ingested.Outcome.AWAITING_MEDIA ->
            "Link saved — share a screenshot next to attach the image"
        ItemRepository.Ingested.Outcome.CREATED -> "Saved to the inbox"
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

        /**
         * Below this the suggestion is not trustworthy enough to apply behind
         * the user's back; the image is left whole instead.
         */
        const val AUTO_CROP_MIN_CONFIDENCE = 0.72f

        /** Set by the launcher when a Sharing Shortcut is tapped. */
        const val EXTRA_SHORTCUT_ID = "android.intent.extra.shortcut.ID"
    }
}
