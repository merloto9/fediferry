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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * The phone's own project, kept in the app's database: the same things a
 * FediFerry server keeps — channels, posts with their stages, review folders,
 * schedules — so the same screens work on either. Nested parts (a post's
 * pictures, labels, its way out) are JSON columns: a phone holds a handful.
 */

/** A channel of the phone's project; its account is a row of `accounts`, its token in the token store. */
@Entity(tableName = "phone_channels")
data class PhoneChannel(
    @PrimaryKey val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    /** The [app.fediferry.data.model.Account] it posts as; null until one is connected. */
    val accountId: String? = null,
    /** A [app.fediferry.api.Platform] name, once an account is connected. */
    val platform: String? = null,
    /** The account's id on its instance, for reading its posts back. */
    val remoteAccountId: String? = null,
    /** [app.fediferry.api.PlatformLimits] as JSON. */
    val limitsJson: String = "{}",
    /** [app.fediferry.api.PostSettings] as JSON. */
    val postJson: String = "{}",
)

/** A post of the phone's project, from draft until it is out. Then it is deleted. */
@Entity(tableName = "phone_posts", indices = [Index("stage")])
data class PhonePost(
    @PrimaryKey val id: String,
    /** An [app.fediferry.api.PostStages] value. */
    val stage: String,
    val version: Long = 1,
    val channelId: String? = null,
    val body: String = "",
    /** Space-separated. */
    val hashtags: String = "",
    val addSourceHashtags: Boolean = true,
    val contentWarning: String? = null,
    val visibility: String = "PUBLIC",
    val sourceUrl: String? = null,
    val origin: String? = null,
    val sourceFieldsJson: String = "{}",
    val linkMayIdentify: Boolean = false,
    /** The text as it will be posted, fixed when the post becomes ready. */
    val finalText: String? = null,
    val readyAt: Long? = null,
    val reviewFolderId: String? = null,
    /** JSON list of label names. */
    val labelsJson: String = "[]",
    /** JSON list of [PhoneMedia]. */
    val mediaJson: String = "[]",
    /** [PhonePublication] as JSON, while the post is on its way out. */
    val publicationJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A picture on a post. */
@Serializable
data class PhoneMedia(val position: Int, val assetId: String, val altText: String? = null, val altFailed: Boolean = false)

/** A post on its way out from this phone: when it goes, how the tries went. */
@Serializable
data class PhonePublication(
    val publishAfter: Long,
    /** Set when Mastodon is to hold it until then; null goes out at [publishAfter]. */
    val scheduledAt: Long? = null,
    val scheduleId: String? = null,
    val slotAt: Long? = null,
    val attempts: Int = 0,
    val failureCode: String? = null,
)

/**
 * A stored picture: a file in app-private storage. An edit (crop, clean-up)
 * is a new asset naming its [parentId], so it can be undone.
 */
@Entity(tableName = "phone_assets", indices = [Index("sha256")])
data class PhoneAsset(
    @PrimaryKey val id: String,
    val path: String,
    val sha256: String,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val bytes: Long = 0,
    val parentId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "phone_review_folders")
data class PhoneReviewFolder(@PrimaryKey val id: String, val name: String, val sortOrder: Int = 0)

/** Weekly slots for a channel; "next free slot" hands a post to Mastodon for the soonest one. */
@Entity(tableName = "phone_schedules")
data class PhoneSchedule(
    @PrimaryKey val id: String,
    val channelId: String,
    val name: String,
    val timezone: String,
    val active: Boolean = true,
    /** JSON list of [app.fediferry.api.SlotDto]. */
    val slotsJson: String = "[]",
)
