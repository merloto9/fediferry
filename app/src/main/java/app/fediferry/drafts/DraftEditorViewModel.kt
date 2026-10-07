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

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.api.ChannelDto
import app.fediferry.api.CropRect
import app.fediferry.api.DeriveRequest
import app.fediferry.api.PostDto
import app.fediferry.api.PostMediaPatch
import app.fediferry.api.PostPatch
import app.fediferry.api.PostStages
import app.fediferry.api.Violation
import app.fediferry.channel.PostValidator
import app.fediferry.client.ServerClient
import app.fediferry.client.ServerException
import app.fediferry.data.model.CleanupProfile
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.PlaceholderKey
import app.fediferry.data.model.Template
import app.fediferry.di.ServiceLocator
import app.fediferry.template.TemplateEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether this phone may edit the draft right now. */
sealed interface EditLock {
    data object Asking : EditLock
    data object Held : EditLock
    /** Another phone is editing; [until] is when its lock runs out if it goes quiet. */
    data class Elsewhere(val device: String, val until: Long) : EditLock
    /** The post moved on from draft and can no longer be edited. */
    data class Frozen(val stage: String) : EditLock
    /** The server could not be reached to ask. */
    data object Offline : EditLock
}

data class DraftEditorState(
    /** The draft as shown: the server's copy with this phone's unsaved edits on top. */
    val post: PostDto? = null,
    val loaded: Boolean = false,
    val lock: EditLock = EditLock.Asking,
    /** Edits not on the server yet. */
    val unsaved: Boolean = false,
    val channels: List<ChannelDto> = emptyList(),
    val templates: List<Template> = emptyList(),
    val placeholderKeys: List<PlaceholderKey> = emptyList(),
    val hashtagList: List<String> = emptyList(),
    val hashtagUses: Map<String, Int> = emptyMap(),
    val rememberSentHashtags: Boolean = false,
    val profiles: List<CleanupProfile> = emptyList(),
    /** Pictures, by position, waiting for a description from the server's model. */
    val altBusy: Set<Int> = emptySet(),
    /** A picture is being cropped or cleaned on the server. */
    val mediaBusy: Boolean = false,
    /** Asking the server to freeze the draft. */
    val readying: Boolean = false,
    /** Why the server refused "ready", when it disagreed with the phone's own check. */
    val refusals: List<Violation> = emptyList(),
    /** An error code with its details, for the screen to word. */
    val message: Pair<String, Map<String, String>>? = null,
) {
    val editable: Boolean get() = lock == EditLock.Held
    val channel: ChannelDto? get() = channels.firstOrNull { it.id == post?.channelId }
    val template: Template? get() = templates.firstOrNull { it.id == post?.templateId }

    /** What the channel would refuse, and what the person should know, as of the text on screen. */
    val issues: List<Violation>
        get() = post?.let { PostValidator.check(it, channel, placeholderKeys.map { k -> k.name }) }.orEmpty()
}

/**
 * Edits one draft on the server. The phone takes the draft's edit lock when it
 * opens it and renews it every minute, so two people never write over each
 * other; edits are saved a moment after typing stops, naming the version they
 * were made on. Losing the lock — another phone took over — stops editing and
 * says who has it.
 */
class DraftEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val connections = ServiceLocator.serverConnections(app)
    private val client: ServerClient? get() = connections.current()?.let(connections::client)
    private val db = ServiceLocator.database(app)

    private val _state = MutableStateFlow(DraftEditorState())
    val state: StateFlow<DraftEditorState> = _state.asStateFlow()

    /** The server's last word on the draft; edits are what differs from it. */
    private var base: PostDto? = null
    private var postId: String? = null
    private val saving = Mutex()
    private var saveJob: Job? = null
    private var keepAlive: Job? = null

    fun load(id: String) {
        if (postId == id) return
        postId = id
        viewModelScope.launch {
            _state.update {
                it.copy(
                    templates = ServiceLocator.items(getApplication()).templates(),
                    placeholderKeys = db.placeholderKeys().all(),
                    hashtagList = db.hashtags().all().map { h -> h.tag },
                    hashtagUses = db.hashtagUsage().all().associate { u -> u.key to u.uses },
                    rememberSentHashtags = ServiceLocator.settings(getApplication()).current().rememberSentHashtags,
                    profiles = db.cleanup().profiles(),
                )
            }
            val api = client ?: return@launch
            runCatching { api.channels() }.onSuccess { channels -> _state.update { it.copy(channels = channels) } }
            reload()
            acquire(force = false)
            keepAlive = viewModelScope.launch { keepAlive() }
        }
    }

    /** Reads the draft again; edits not yet saved are dropped. */
    private suspend fun reload() {
        val api = client ?: return
        val id = postId ?: return
        runCatching { api.post(id) }
            .onSuccess { post -> base = post; _state.update { it.copy(post = post, loaded = true, unsaved = false) } }
            .onFailure { e ->
                val code = (e as? ServerException)?.code
                _state.update { it.copy(loaded = true, post = if (code == "not_found" || (e as? ServerException)?.status == 404) null else it.post) }
                if (e is ServerException && e.unreachable) _state.update { it.copy(lock = EditLock.Offline) }
            }
    }

    /** Asks for the edit lock; [force] takes it from another phone. */
    fun takeOver() = viewModelScope.launch { acquire(force = true) }

    fun retry() = viewModelScope.launch {
        reload()
        acquire(force = false)
    }

    private suspend fun acquire(force: Boolean) {
        val api = client ?: return
        val id = postId ?: return
        val post = _state.value.post ?: return
        if (post.stage != PostStages.DRAFT) {
            _state.update { it.copy(lock = EditLock.Frozen(post.stage)) }
            return
        }
        runCatching { api.lockPost(id, force) }
            .onSuccess {
                // Taking over: what the other phone saved last is what we edit.
                if (force) reload()
                _state.update { it.copy(lock = EditLock.Held) }
            }
            .onFailure { handle(it) }
    }

    /** Renews the lock while editing; while someone else edits, keeps the view current. */
    private suspend fun keepAlive() {
        while (viewModelScope.isActive) {
            when (_state.value.lock) {
                EditLock.Held -> {
                    delay(RENEW_MS)
                    if (_state.value.lock != EditLock.Held) continue
                    flush()
                    val api = client ?: continue
                    runCatching { api.lockPost(postId ?: return) }.onFailure { handle(it) }
                }
                is EditLock.Elsewhere, EditLock.Offline -> {
                    delay(WATCH_MS)
                    reload()
                    // The other phone let go, or its lock ran out: carry on here.
                    val lock = _state.value.post?.lock
                    if (lock == null || lock.expiresAt < System.currentTimeMillis()) acquire(force = false)
                }
                else -> delay(WATCH_MS)
            }
        }
    }

    private suspend fun handle(e: Throwable) {
        val error = e as? ServerException
        when (error?.code) {
            "post.locked" -> _state.update {
                it.copy(lock = EditLock.Elsewhere(error.args["device"].orEmpty(), error.args["until"]?.toLongOrNull() ?: 0L))
            }
            "post.lock_required" -> _state.update { it.copy(lock = EditLock.Elsewhere("", 0L)) }
            "post.not_draft" -> _state.update { it.copy(lock = EditLock.Frozen(error.args["stage"].orEmpty())) }
            "post.version_conflict" -> {
                reload()
                _state.update { it.copy(message = error.code to error.args) }
            }
            else -> {
                if (error?.unreachable == true && _state.value.lock != EditLock.Held) _state.update { it.copy(lock = EditLock.Offline) }
                _state.update { it.copy(message = (error?.code ?: "client.unknown") to error?.args.orEmpty()) }
            }
        }
    }

    // --- editing -----------------------------------------------------------------

    private fun edit(block: (PostDto) -> PostDto) {
        if (!_state.value.editable) return
        _state.update { s -> s.copy(post = s.post?.let(block), unsaved = true, refusals = emptyList()) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            save()
        }
    }

    fun setBody(text: String) = edit { it.copy(body = text) }
    fun setContentWarning(text: String) = edit { it.copy(contentWarning = text.ifBlank { null }) }
    fun setVisibility(name: String) = edit { it.copy(visibility = name) }
    fun setChannel(id: String) = edit { it.copy(channelId = id) }

    fun setAltText(position: Int, text: String) = edit { post ->
        post.copy(media = post.media.map { if (it.position == position) it.copy(altText = text, altFailed = false) else it })
    }

    fun toggleHashtag(tag: String) = edit { post ->
        val next = if (Hashtags.contains(post.hashtags, tag)) post.hashtags.filterNot { it.equals(tag, ignoreCase = true) } else post.hashtags + tag
        post.copy(hashtags = next)
    }

    fun addHashtag(raw: String): Boolean {
        val tag = Hashtags.normalize(raw) ?: return false
        edit { it.copy(hashtags = Hashtags.union(it.hashtags, listOf(tag))) }
        return true
    }

    /** The hashtags this draft's source offers, whether or not they are ticked. */
    fun sourceHashtags(): List<String> {
        val s = _state.value
        val post = s.post ?: return emptyList()
        val template = s.template ?: return emptyList()
        return TemplateEngine.sourceHashtags(template, s.placeholderKeys, inputs(post))
    }

    /** Adds or takes away the source's hashtags, keeping any the template picked too. */
    fun setAddSourceHashtags(on: Boolean) {
        val fromSource = sourceHashtags()
        val templateTags = _state.value.template?.hashtagList.orEmpty()
        edit { post ->
            val next = if (on) {
                Hashtags.union(post.hashtags, fromSource)
            } else {
                post.hashtags.filterNot { Hashtags.contains(fromSource, it) && !Hashtags.contains(templateTags, it) }
            }
            post.copy(addSourceHashtags = on, hashtags = next)
        }
    }

    /** Takes a link that may name its sharer out of the text. */
    fun removeLink() = edit { post ->
        val link = post.sourceUrl ?: return@edit post
        post.copy(
            body = post.body.replace(link, "").lines().joinToString("\n") { it.trimEnd() }
                .replace(Regex("\n{3,}"), "\n\n").trim(),
        )
    }

    /** Writes the text again from [template]; edits to the text are replaced. */
    fun applyTemplate(template: Template) = change(
        PostPatch(
            templateId = template.id,
            rerender = true,
            visibility = template.visibility.name,
            contentWarning = template.contentWarning.orEmpty(),
        ),
    )

    fun removePicture(position: Int) = change(PostPatch(media = listOf(PostMediaPatch(position, remove = true))))

    fun crop(position: Int, rect: CropRect) = derive(position, DeriveRequest(crop = rect))

    fun cleanUp(position: Int, profileId: String) = derive(position, DeriveRequest(profileId = profileId))

    /** Goes back one edit: the picture this one was made from. */
    fun undoEdit(position: Int) {
        val parent = _state.value.post?.media?.firstOrNull { it.position == position }?.asset?.parentId ?: return
        change(PostPatch(media = listOf(PostMediaPatch(position, assetId = parent))))
    }

    private fun derive(position: Int, request: DeriveRequest) = viewModelScope.launch {
        val api = client ?: return@launch
        val asset = _state.value.post?.media?.firstOrNull { it.position == position }?.asset ?: return@launch
        _state.update { it.copy(mediaBusy = true) }
        runCatching { api.derive(asset.id, request) }
            .onSuccess { made -> send(PostPatch(media = listOf(PostMediaPatch(position, assetId = made.id)))) }
            .onFailure { handle(it) }
        _state.update { it.copy(mediaBusy = false) }
    }

    /** Asks the project's model to describe a picture; the text lands in the field to check. */
    fun suggestAlt(position: Int) = viewModelScope.launch {
        val api = client ?: return@launch
        val id = postId ?: return@launch
        _state.update { it.copy(altBusy = it.altBusy + position) }
        runCatching { api.suggestAlt(id, position) }
            .onSuccess { setAltText(position, it.text) }
            .onFailure { handle(it) }
        _state.update { it.copy(altBusy = it.altBusy - position) }
    }

    /** A change that cannot wait for typing to stop. Pending edits go first. */
    private fun change(patch: PostPatch) {
        if (!_state.value.editable) return
        viewModelScope.launch { send(patch) }
    }

    private suspend fun send(patch: PostPatch) {
        flush()
        saving.withLock {
            val api = client ?: return
            val from = base ?: return
            runCatching { api.patchPost(from.id, from.version, patch) }
                .onSuccess { saved -> base = saved; _state.update { it.copy(post = saved) } }
                .onFailure { handle(it) }
        }
    }

    /** Saves now whatever differs from the server's copy, instead of after the typing pause. */
    suspend fun flush() {
        saveJob?.cancel()
        save()
    }

    private suspend fun save() {
        saving.withLock {
            val api = client ?: return
            val from = base ?: return
            val edited = _state.value.post ?: return
            val patch = diff(from, edited) ?: run {
                _state.update { it.copy(unsaved = false) }
                return
            }
            runCatching { api.patchPost(from.id, from.version, patch) }
                .onSuccess { saved ->
                    base = saved
                    _state.update { s ->
                        // Typed on while saving: keep that, on the new version.
                        val now = s.post
                        if (now == edited) s.copy(post = saved, unsaved = false)
                        else s.copy(post = now?.copy(version = saved.version, lock = saved.lock), unsaved = true)
                    }
                }
                .onFailure { handle(it) }
        }
    }

    /**
     * Saves, then asks the server to freeze the draft for review. The server
     * checks again: it has the last word on what the channel takes.
     */
    fun markReady(onReady: (String) -> Unit) = viewModelScope.launch {
        val api = client ?: return@launch
        val id = postId ?: return@launch
        _state.update { it.copy(readying = true, refusals = emptyList()) }
        flush()
        val version = base?.version
        if (version == null || _state.value.unsaved) {
            _state.update { it.copy(readying = false) }
            return@launch
        }
        runCatching { api.markReady(id, version) }
            .onSuccess {
                // The server let go of the lock with the freeze.
                _state.update { s -> s.copy(readying = false, lock = EditLock.Frozen(it.stage)) }
                onReady(it.id)
            }
            .onFailure { e ->
                _state.update { it.copy(readying = false, refusals = (e as? ServerException)?.violations.orEmpty()) }
                handle(e)
            }
    }

    /** Leaves the draft: saves, lets go of the lock, then [onDone]. */
    fun close(onDone: () -> Unit) = viewModelScope.launch {
        flush()
        release()
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val api = client ?: return@launch
        val id = postId ?: return@launch
        saveJob?.cancel()
        runCatching { api.deletePost(id) }
            .onSuccess {
                _state.update { it.copy(lock = EditLock.Asking) }
                onDone()
            }
            .onFailure { handle(it) }
    }

    private suspend fun release() {
        if (_state.value.lock != EditLock.Held) return
        val api = client ?: return
        val id = postId ?: return
        _state.update { it.copy(lock = EditLock.Asking) }
        withContext(NonCancellable) { runCatching { api.unlockPost(id) } }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        keepAlive?.cancel()
        // Left without "back" (the app was closed): save and let go anyway.
        if (_state.value.lock == EditLock.Held) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                flush()
                release()
            }
        }
    }

    private fun inputs(post: PostDto) = TemplateEngine.Inputs(
        link = post.sourceUrl,
        source = ContentSource.fromName(post.origin),
        fields = post.sourceFields,
    )

    companion object {
        const val SAVE_DELAY_MS = 1_000L
        /** The server's lock lasts five minutes; renewing every minute leaves room for a bad connection. */
        const val RENEW_MS = 60_000L
        const val WATCH_MS = 10_000L

        /** What changed between the server's copy and the edited one, as a patch; null when nothing did. */
        fun diff(from: PostDto, to: PostDto): PostPatch? {
            val media = to.media.mapNotNull { m ->
                val before = from.media.firstOrNull { it.position == m.position } ?: return@mapNotNull null
                if (before.altText.orEmpty() == m.altText.orEmpty()) null else PostMediaPatch(m.position, altText = m.altText.orEmpty())
            }
            val patch = PostPatch(
                body = to.body.takeIf { it != from.body },
                hashtags = to.hashtags.takeIf { it != from.hashtags },
                addSourceHashtags = to.addSourceHashtags.takeIf { it != from.addSourceHashtags },
                contentWarning = to.contentWarning.orEmpty().takeIf { it != from.contentWarning.orEmpty() },
                visibility = to.visibility.takeIf { it != from.visibility },
                channelId = to.channelId?.takeIf { it != from.channelId },
                media = media.ifEmpty { null },
            )
            return patch.takeIf { it != PostPatch() }
        }
    }
}
