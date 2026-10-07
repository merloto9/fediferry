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
package app.fediferry.drafts

import android.content.res.Resources
import app.fediferry.R
import java.text.DateFormat
import java.util.Date

/** Words a server error code for the person holding the phone. */
object ServerMessages {
    fun describe(resources: Resources, code: String, args: Map<String, String> = emptyMap()): String = when (code) {
        "client.unreachable" -> resources.getString(R.string.draft_offline)
        "post.locked" -> resources.getString(
            R.string.server_error_locked,
            args["device"].orEmpty(),
            args["until"]?.toLongOrNull()?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }.orEmpty(),
        )
        "post.lock_required" -> resources.getString(R.string.server_error_lock_required)
        "post.version_conflict" -> resources.getString(R.string.server_error_version_conflict)
        "post.not_draft" -> resources.getString(R.string.server_error_not_draft)
        "alt.no_model" -> resources.getString(R.string.server_error_alt_no_model)
        "alt.failed" -> resources.getString(R.string.server_error_alt_failed, args["reason"].orEmpty())
        "media.not_editable" -> resources.getString(R.string.server_error_not_editable)
        else -> resources.getString(R.string.library_action_failed, code)
    }
}
