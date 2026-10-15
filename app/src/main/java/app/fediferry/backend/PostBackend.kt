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
package app.fediferry.backend

import app.fediferry.api.AltSuggestion
import app.fediferry.api.AuthorizeResponse
import app.fediferry.api.ChannelDto
import app.fediferry.api.ChannelInput
import app.fediferry.api.ChannelPatch
import app.fediferry.api.DeriveRequest
import app.fediferry.api.LabelDto
import app.fediferry.api.LockDto
import app.fediferry.api.MediaAssetDto
import app.fediferry.api.PlanRequest
import app.fediferry.api.PostDto
import app.fediferry.api.PostPatch
import app.fediferry.api.PublishedPage
import app.fediferry.api.ReviewFolderDto
import app.fediferry.api.ReviewFolderInput
import app.fediferry.api.ReviewPatch
import app.fediferry.api.ScheduleDto
import app.fediferry.api.ScheduleInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where posts live and how they get out: everything the screens — Drafts,
 * Review, Publishing — ask of the data behind them. One set of screens, two
 * backends: [ServerBackend] for a project on a FediFerry server, and the
 * phone's own store when the app works alone.
 *
 * The screens speak the server's API types ([PostDto], [ChannelDto], …) and
 * its error codes: a failure is an [app.fediferry.client.ServerException]
 * whichever backend raised it, so one set of wordings covers both.
 */
interface PostBackend {

    /** This phone, as edit locks name it. */
    val deviceId: String

    /** Which project this is, for keeping what each one showed apart ([ScreenCache]). */
    val key: String

    /**
     * Whether the backend answered the last time it was asked. The phone's own
     * project always does; a server may not, and the screens say so while
     * still showing what they loaded last.
     */
    val reachable: StateFlow<Reachability>

    /**
     * Something changed: emits the kinds of thing that did ("post",
     * "channel", "schedule", "review_folder", …) for as long as it is
     * collected. Screens collect it while they are visible and read again.
     */
    fun changes(): Flow<Set<String>>

    // --- posts --------------------------------------------------------------------

    suspend fun posts(vararg stages: String): List<PostDto>

    suspend fun post(id: String): PostDto

    suspend fun deletePost(id: String)

    // --- editing a draft ------------------------------------------------------------

    /** Takes or renews the right to edit [id]; [force] takes it from another phone. */
    suspend fun lockPost(id: String, force: Boolean = false): LockDto

    suspend fun unlockPost(id: String)

    /** Changes a draft; [version] is the one the change was made on. */
    suspend fun patchPost(id: String, version: Long, patch: PostPatch): PostDto

    /** A new picture made from [assetId]: cropped and/or cleaned. The original stays. */
    suspend fun derive(assetId: String, request: DeriveRequest): MediaAssetDto

    /** Describes a picture with the alt-text model [modelId], or the default one when null. */
    suspend fun suggestAlt(postId: String, position: Int, modelId: String? = null): AltSuggestion

    // --- review ---------------------------------------------------------------------------

    /** Freezes a draft for review; refused with the violations its channel would not take. */
    suspend fun markReady(id: String, version: Long): PostDto

    suspend fun backToDraft(id: String): PostDto

    suspend fun sortForReview(id: String, patch: ReviewPatch): PostDto

    suspend fun reviewFolders(): List<ReviewFolderDto>

    suspend fun createReviewFolder(input: ReviewFolderInput): ReviewFolderDto

    suspend fun updateReviewFolder(id: String, input: ReviewFolderInput): ReviewFolderDto

    suspend fun deleteReviewFolder(id: String)

    suspend fun labels(): List<LabelDto>

    // --- publishing --------------------------------------------------------------------------

    suspend fun plan(id: String, request: PlanRequest): PostDto

    suspend fun unplan(id: String): PostDto

    suspend fun schedules(): List<ScheduleDto>

    suspend fun createSchedule(input: ScheduleInput): ScheduleDto

    suspend fun updateSchedule(id: String, input: ScheduleInput): ScheduleDto

    suspend fun deleteSchedule(id: String)

    /** The latest posts that went out, as their channels have them. */
    suspend fun published(limit: Int = 20): PublishedPage

    // --- channels ------------------------------------------------------------------------------

    suspend fun channels(): List<ChannelDto>

    /** A channel with no account yet. */
    suspend fun createChannel(input: ChannelInput): ChannelDto

    /** Renames a channel, makes it the default or changes how its posts start. */
    suspend fun patchChannel(id: String, patch: ChannelPatch): ChannelDto

    suspend fun deleteChannel(id: String)

    /** Reads again what the channel's instance takes. */
    suspend fun refreshLimits(id: String): ChannelDto

    /**
     * Starts connecting the account at [instance] to [channelId] — or to a new
     * channel when null. Refused with `platform.*` codes when the instance runs
     * software that cannot be connected. The caller opens [AuthorizeResponse.url];
     * the sign-in finishes when the instance sends the browser back.
     */
    suspend fun connectAccount(instance: String, channelId: String?): AuthorizeResponse

    // --- pictures --------------------------------------------------------------------------------

    /** Something the image loader can show [assetId] from. */
    fun mediaUrl(assetId: String): String

    fun thumbnailUrl(assetId: String): String
}
