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
package app.fediferry.data

import app.fediferry.data.model.Item
import app.fediferry.data.model.Status

/**
 * Cleans the links of posts shared before FediFerry took the sharer's details
 * out of Pinterest and Reddit links (0.17.0 and 0.17.1). New shares are cleaned
 * as they arrive; this catches the ones already waiting in the inbox.
 */
object OldLinkCleanup {

    /** What one run did, for the summary shown afterwards. */
    data class Result(val cleaned: Int = 0, val kept: Int = 0, val alreadyClean: Int = 0)

    /**
     * Posts that are still the user's to change. Queued and sending posts are
     * left alone: the worker may be reading them right now.
     */
    fun isCandidate(item: Item): Boolean =
        item.sourceUrl != null && (item.status == Status.DRAFT || item.status == Status.FAILED)

    /**
     * The body with the old link swapped for the clean one. A body the user
     * edited is not re-rendered from its template, so without this the old
     * link would stay in the text even though the post's link is clean.
     */
    fun rewriteBody(body: String, oldUrl: String, newUrl: String): String =
        if (oldUrl == newUrl) body else body.replace(oldUrl, newUrl)
}
