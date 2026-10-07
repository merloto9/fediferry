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
import app.fediferry.api.Violation
import app.fediferry.channel.PostValidator
import java.text.DateFormat
import java.util.Date

/** Words a server error code for the person holding the phone. */
object ServerMessages {
    /** Why the server could not publish a post, from the code it kept. */
    fun publishFailure(resources: Resources, code: String?): String = when (code) {
        "mastodon.unauthorized" -> resources.getString(R.string.publish_error_unauthorized)
        "mastodon.rejected" -> resources.getString(R.string.publish_error_rejected)
        "mastodon.rate_limited" -> resources.getString(R.string.publish_error_rate_limited)
        "mastodon.unreachable" -> resources.getString(R.string.publish_error_unreachable)
        "mastodon.too_large" -> resources.getString(R.string.publish_error_too_large)
        "mastodon.media_processing" -> resources.getString(R.string.publish_error_media_processing)
        "channel.no_token" -> resources.getString(R.string.publish_error_no_token)
        "server.interrupted" -> resources.getString(R.string.publish_error_interrupted)
        null -> resources.getString(R.string.publish_error_unknown, "?")
        else -> if (code.startsWith("mastodon.http_5")) {
            resources.getString(R.string.publish_error_busy)
        } else {
            resources.getString(R.string.publish_error_unknown, code)
        }
    }

    /** One reason a post cannot become ready, or a warning about it. */
    fun violation(resources: Resources, v: Violation): String {
        val a = v.args
        return when (v.code) {
            PostValidator.NO_CHANNEL -> resources.getString(R.string.ready_no_channel)
            PostValidator.EMPTY -> resources.getString(R.string.ready_empty)
            PostValidator.TOO_LONG -> resources.getString(R.string.ready_too_long, a["length"].orEmpty(), a["max"].orEmpty())
            PostValidator.TOO_MANY_MEDIA -> resources.getString(R.string.ready_too_many_media, a["count"].orEmpty(), a["max"].orEmpty())
            PostValidator.NEEDS_MEDIA -> resources.getString(R.string.ready_needs_media)
            PostValidator.MEDIA_TYPE -> resources.getString(R.string.ready_media_type, a["picture"].orEmpty(), a["type"].orEmpty())
            PostValidator.MEDIA_TOO_BIG -> resources.getString(R.string.ready_media_too_big, a["picture"].orEmpty(), a["mb"].orEmpty(), a["max"].orEmpty())
            PostValidator.ALT_TOO_LONG -> resources.getString(R.string.ready_alt_too_long, a["picture"].orEmpty(), a["length"].orEmpty(), a["max"].orEmpty())
            PostValidator.NO_CONTENT_WARNING -> resources.getString(R.string.ready_no_content_warning)
            PostValidator.UNKNOWN_PLACEHOLDER -> resources.getString(R.string.ready_unknown_placeholder, a["names"].orEmpty())
            PostValidator.NO_ALT -> resources.getString(R.string.ready_no_alt, a["picture"].orEmpty())
            PostValidator.LINK_MAY_IDENTIFY -> resources.getString(R.string.ready_link_may_identify)
            else -> v.code
        }
    }

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
        "post.not_ready" -> resources.getString(R.string.server_error_not_ready)
        "post.not_ready_stage" -> resources.getString(R.string.server_error_not_ready_stage)
        "plan.no_schedule" -> resources.getString(R.string.plan_error_no_schedule)
        "plan.no_free_slot" -> resources.getString(R.string.plan_error_no_free_slot)
        "plan.in_past" -> resources.getString(R.string.plan_error_in_past)
        "post.publishing" -> resources.getString(R.string.plan_error_publishing)
        "post.not_plannable", "post.not_deletable" -> resources.getString(R.string.plan_error_stage)
        "post.no_channel" -> resources.getString(R.string.ready_no_channel)
        else -> resources.getString(R.string.library_action_failed, code)
    }
}
