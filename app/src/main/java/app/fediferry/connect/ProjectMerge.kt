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
package app.fediferry.connect

import android.content.Context
import app.fediferry.api.ChannelDto
import app.fediferry.api.ChannelInput
import app.fediferry.api.ChannelPatch
import app.fediferry.api.PostSettings
import app.fediferry.api.PostStages
import app.fediferry.api.ScheduleInput
import app.fediferry.api.SettingsKinds
import app.fediferry.api.SlotDto
import app.fediferry.client.ServerClient
import app.fediferry.data.TokenStore
import app.fediferry.data.db.AppDatabase
import app.fediferry.data.model.Account
import app.fediferry.log.DebugLog
import app.fediferry.mastodon.AuthManager
import app.fediferry.phone.PhoneBackend
import app.fediferry.phone.PhoneChannel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Whose version stays, where the phone and the project have one thing differently. */
enum class MergeSide { PHONE, SERVER }

/** One thing both the phone and the project have, differently, that adding cannot settle. */
data class MergeConflict(
    /** What the choices handed to [ProjectMerge.mergeSettingsAndChannels] are keyed by. */
    val key: String,
    /** A [SettingsKinds] name, or [ProjectMerge.CHANNEL]. */
    val area: String,
    /** The thing's name; for a default, its group (an AI kind's name) or empty. */
    val name: String,
    /** The phone's version in a line; for a default, the name of the phone's. */
    val phone: String,
    val server: String,
    /** Both have a default and it is not the same one. */
    val isDefault: Boolean = false,
)

/** What joining a project does to the settings; filled in by [ProjectSync.preview]. */
class SettingsPreview {
    /** Entries only the phone has, per [SettingsKinds] name: they go to the project. */
    val fromPhone = mutableMapOf<String, Int>()

    /** Entries only the project has: they come to the phone. */
    val fromServer = mutableMapOf<String, Int>()
    val conflicts = mutableListOf<MergeConflict>()

    /** Whether the phone has settings beyond what a fresh install comes with. */
    var phoneHasOwn = false
}

/** Everything joining a project would bring over from this phone: what the warning before it lists. */
data class JoinPlan(
    val drafts: Int = 0,
    /** Ready posts, and failed ones, which go back to review. */
    val inReview: Int = 0,
    val planned: Int = 0,
    val channels: Int = 0,
    val schedules: Int = 0,
    val hashtags: Int = 0,
    val aiModels: Int = 0,
    val otherSettings: Int = 0,
    val fromServer: Int = 0,
    val conflicts: List<MergeConflict> = emptyList(),
    /** False for a phone with nothing of its own yet: it joins without a warning. */
    val phoneHasOwn: Boolean = false,
)

/**
 * Joins a project with a phone that has a project of its own, mirroring it
 * into the server's: settings ([ProjectSync.merge]), channels with their
 * accounts and schedules, and posts in the stage they are in
 * ([LocalImporter]). Nothing that only one side has is lost; where both have
 * one thing differently, the user's choice decides.
 */
class ProjectMerge(
    private val context: Context,
    private val db: AppDatabase,
    private val tokens: TokenStore,
    private val auth: AuthManager,
    private val sync: ProjectSync,
    private val importer: LocalImporter,
) {

    /** What joining [client]'s project would bring over, and where the user has to choose. Changes nothing. */
    suspend fun plan(client: ServerClient): JoinPlan {
        val settings = sync.preview(client)
        val serverChannels = client.channels()
        val pairs = pairs(serverChannels)
        val posts = db.phone().posts(listOf(PostStages.DRAFT, PostStages.READY, PostStages.FAILED, PostStages.SCHEDULED))
        val conflicts = settings.conflicts + channelConflicts(pairs, serverChannels)
        val schedules = newSchedules(client, pairs.associate { it.phone.id to (it.server?.id ?: it.phone.id) })
        val hashtags = settings.fromPhone[SettingsKinds.HASHTAG] ?: 0
        val models = settings.fromPhone[SettingsKinds.AI_MODEL] ?: 0
        return JoinPlan(
            drafts = posts.count { it.stage == PostStages.DRAFT },
            inReview = posts.count { it.stage == PostStages.READY || it.stage == PostStages.FAILED },
            planned = posts.count { it.stage == PostStages.SCHEDULED },
            channels = pairs.count { it.server == null },
            schedules = schedules,
            hashtags = hashtags,
            aiModels = models,
            otherSettings = settings.fromPhone.values.sum() - hashtags - models -
                (settings.fromPhone[SettingsKinds.HASHTAG_USAGE] ?: 0),
            fromServer = settings.fromServer.values.sum() - (settings.fromServer[SettingsKinds.HASHTAG_USAGE] ?: 0),
            conflicts = conflicts,
            phoneHasOwn = posts.isNotEmpty() || pairs.isNotEmpty() || settings.phoneHasOwn,
        )
    }

    /**
     * Step one, before the connection is kept: the settings and the channels.
     * Returns, for each of the phone's channels, the project's it became.
     */
    suspend fun mergeSettingsAndChannels(client: ServerClient, projectId: String, choices: Map<String, MergeSide>): Map<String, String> {
        sync.merge(client, projectId, choices)
        // Out of the phone's queue before the accounts that would send them move.
        importer.takeOutOfQueue(projectId)
        val server = client.channels()
        val pairs = pairs(server)
        val map = mutableMapOf<String, String>()
        for (pair in pairs) {
            val target = pair.server
            val id = if (target != null) {
                if (choices[channelKey(target.id)] == MergeSide.PHONE) {
                    runCatching { client.patchChannel(target.id, ChannelPatch(name = pair.phone.name, post = pair.settings)) }
                }
                target.id
            } else {
                newChannel(client, pair)?.id ?: continue
            }
            map[pair.phone.id] = id
        }
        val phoneDefault = pairs.firstOrNull { it.phone.isDefault }?.let { map[it.phone.id] }
        val serverDefault = server.firstOrNull { it.isDefault }?.id
        if (phoneDefault != null && phoneDefault != serverDefault && (serverDefault == null || choices[CHANNEL_DEFAULT] == MergeSide.PHONE)) {
            runCatching { client.patchChannel(phoneDefault, ChannelPatch(isDefault = true)) }
        }
        channelsFile(projectId).apply { parentFile?.mkdirs() }.writeText(PhoneBackend.json.encodeToString(CHANNEL_MAP, map))
        return map
    }

    /** Step two, once connected: the posts, then the schedules. Returns how many posts arrived. */
    suspend fun copyPostsAndSchedules(client: ServerClient, projectId: String, channels: Map<String, String>): Int {
        val copied = importer.importAll(client, projectId, channels)
        val theirs = runCatching { client.schedules() }.getOrDefault(emptyList())
        for (schedule in db.phone().schedules()) {
            val channelId = channels[schedule.channelId] ?: continue
            if (theirs.any { it.channelId == channelId && it.name.equals(schedule.name, ignoreCase = true) }) continue
            val slots = runCatching { PhoneBackend.json.decodeFromString(SLOTS, schedule.slotsJson) }.getOrDefault(emptyList())
            runCatching { client.createSchedule(ScheduleInput(channelId, schedule.name, schedule.timezone, schedule.active, slots)) }
                .onFailure { DebugLog.w(LOG, "Copying a schedule failed: ${it.javaClass.simpleName}") }
        }
        return copied
    }

    /** The channel map step one kept, for copying posts again later. */
    fun channels(projectId: String): Map<String, String> =
        channelsFile(projectId).takeIf { it.exists() }
            ?.let { runCatching { PhoneBackend.json.decodeFromString(CHANNEL_MAP, it.readText()) }.getOrNull() }
            .orEmpty()

    // --- channels -----------------------------------------------------------------

    /** One of the phone's channels, and the project's channel for the same account, if it has one. */
    private class ChannelPair(val phone: PhoneChannel, val settings: PostSettings, val account: Account?, val server: ChannelDto?)

    private suspend fun pairs(server: List<ChannelDto>): List<ChannelPair> {
        val accounts = db.accounts().all().associateBy { it.id }
        return db.phone().channels().map { channel ->
            val account = channel.accountId?.let(accounts::get)
            val match = server.firstOrNull { it.id == channel.id }
                ?: account?.let { a -> server.firstOrNull { s -> s.account?.let { it.instance.equals(a.instance, true) && it.acct.equals(a.acct, true) } == true } }
            ChannelPair(channel, PhoneBackend.postOf(channel), account, match)
        }
    }

    private fun channelConflicts(pairs: List<ChannelPair>, server: List<ChannelDto>): List<MergeConflict> = buildList {
        for (pair in pairs) {
            val theirs = pair.server ?: continue
            if (theirs.name == pair.phone.name && theirs.post == pair.settings) continue
            add(MergeConflict(channelKey(theirs.id), CHANNEL, theirs.title, describe(pair.phone.name, pair.settings), describe(theirs.name, theirs.post)))
        }
        val phoneDefault = pairs.firstOrNull { it.phone.isDefault }
        val serverDefault = server.firstOrNull { it.isDefault }
        if (phoneDefault != null && serverDefault != null && phoneDefault.server?.id != serverDefault.id) {
            add(MergeConflict(CHANNEL_DEFAULT, CHANNEL, "", phoneDefault.phone.name, serverDefault.title, isDefault = true))
        }
    }

    private fun describe(name: String, post: PostSettings) =
        "$name · ${post.body.lines().first().take(40)}" + post.tags.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()

    /**
     * The project's copy of a channel it does not have: with its account and
     * token where the phone is signed in — the phone forgets its copy of the
     * token then, as it lives on the server — else without one.
     */
    private suspend fun newChannel(client: ServerClient, pair: ChannelPair): ChannelDto? {
        val account = pair.account
        val token = account?.let { tokens.get(it.id) }
        val moved = if (account != null && token != null) {
            val app = auth.registration(account.instance)
            runCatching { client.importMastodon(account.instance, token, pair.phone.id, app?.clientId, app?.clientSecret) }
                .onSuccess { auth.forget(account.id) }
                .onFailure { DebugLog.w(LOG, "Moving an account failed: ${it.javaClass.simpleName}") }
                .getOrNull()
        } else {
            null
        }
        val channel = moved ?: runCatching { client.createChannel(ChannelInput(pair.phone.name, pair.settings)) }.getOrNull() ?: return null
        return runCatching { client.patchChannel(channel.id, ChannelPatch(name = pair.phone.name, post = pair.settings)) }.getOrDefault(channel)
    }

    private suspend fun newSchedules(client: ServerClient, channels: Map<String, String>): Int {
        val theirs = runCatching { client.schedules() }.getOrDefault(emptyList())
        return db.phone().schedules().count { s ->
            val channelId = channels[s.channelId]
            theirs.none { it.channelId == channelId && it.name.equals(s.name, ignoreCase = true) }
        }
    }

    private fun channelsFile(projectId: String) = File(context.filesDir, "sync/$projectId-channels.json")

    companion object {
        private const val LOG = "join"
        const val CHANNEL = "channel"
        const val CHANNEL_DEFAULT = "default/channel/"
        private val SLOTS = ListSerializer(SlotDto.serializer())
        private val CHANNEL_MAP = MapSerializer(String.serializer(), String.serializer())

        fun channelKey(id: String) = "channel/$id"
    }
}
