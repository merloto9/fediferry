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
import app.fediferry.api.PostSettings
import app.fediferry.data.model.Template
import app.fediferry.di.ServiceLocator
import app.fediferry.log.DebugLog
import androidx.room.withTransaction
import androidx.work.WorkManager
import java.io.File

/**
 * Brings what the app kept before it had a project of its own into the
 * phone's project, once:
 *
 * - each Mastodon account becomes a channel, starting its posts the way its
 *   template did (the one naming the account, else the default template);
 *   with no account yet, one channel with the default template waits for one;
 * - each inbox stack becomes a review folder;
 * - everything in the inbox goes to review, as it was written, into the
 *   folder of its stack.
 *
 * The same rules will bring an older FediFerry's content over.
 */
object LegacyInbox {

    private const val LOG = "legacy"

    /**
     * Only items from before [startedAt] are taken: a share arriving as the
     * app starts sits in the same table while it is cleaned and fetched, and
     * is the share screen's to place.
     */
    suspend fun bringOver(context: Context, startedAt: Long) {
        val db = ServiceLocator.database(context)
        val phone = ServiceLocator.phone(context)
        val dao = db.phone()

        // All channels or none, so a death halfway does not leave accounts without one.
        if (dao.channels().isEmpty()) db.withTransaction {
            val templates = db.templates().all()
            val fallback = templates.firstOrNull { it.isDefault } ?: templates.firstOrNull() ?: Template.seed()
            val accounts = db.accounts().all()
            accounts.forEachIndexed { i, account ->
                val template = templates.firstOrNull { it.accountId == account.id } ?: fallback
                dao.upsertChannel(
                    PhoneChannel(
                        id = account.id,
                        name = account.displayName.ifBlank { account.acct },
                        isDefault = account.isDefault || (accounts.none { it.isDefault } && i == 0),
                        sortOrder = i,
                        accountId = account.id,
                        platform = app.fediferry.api.Platform.MASTODON.name,
                        postJson = PhoneBackend.json.encodeToString(PostSettings.serializer(), settingsOf(template)),
                    ),
                )
            }
            if (accounts.isEmpty()) {
                dao.upsertChannel(
                    PhoneChannel(
                        id = java.util.UUID.randomUUID().toString(),
                        name = fallback.name,
                        isDefault = true,
                        postJson = PhoneBackend.json.encodeToString(PostSettings.serializer(), settingsOf(fallback)),
                    ),
                )
            }
            DebugLog.d(LOG, "Made ${dao.channels().size} channel(s) from accounts and templates")
        }

        val stacks = db.stacks().all()
        stacks.forEach { dao.upsertReviewFolder(PhoneReviewFolder(it.id, it.name, it.sortOrder)) }

        // Stacks are gone after the first run; their folders keep the ids.
        val folders = dao.reviewFolders().map { it.id }.toSet()
        val items = db.items().unposted().filter { it.createdAt < startedAt }
        items.forEach { item ->
            // Whatever was on its way out under the old pipeline waits in review instead.
            WorkManager.getInstance(context).cancelUniqueWork("post-${item.id}")
            // A picture whose file is gone moves over without it rather than stopping the rest.
            val usable = item.copy(
                mediaPath = item.mediaPath?.takeIf { File(it).exists() },
                originalMediaPath = item.originalMediaPath?.takeIf { File(it).exists() },
            )
            // Channels took their accounts' ids, so a post keeps the account it was set to.
            runCatching { phone.adopt(usable, ready = true, reviewFolderId = item.stackId?.takeIf { it in folders }, channelId = item.accountId) }
                .onFailure { DebugLog.w(LOG, "Could not move inbox post ${item.id}: ${it.javaClass.simpleName}") }
        }
        stacks.forEach { db.stacks().delete(it.id) }
        if (items.isNotEmpty() || stacks.isNotEmpty()) DebugLog.d(LOG, "Moved ${items.size} inbox post(s) and ${stacks.size} stack(s) to review")
    }

    /** What a template held, as a channel's post settings. */
    fun settingsOf(template: Template) = PostSettings(
        body = template.body,
        tags = template.tags,
        visibility = template.visibility.name,
        contentWarning = template.contentWarning,
        altTextMode = template.altTextMode.name,
        staticAltText = template.staticAltText,
        addSourceHashtags = template.addSourceHashtags,
        excludedSources = template.excludedSources,
    )
}
