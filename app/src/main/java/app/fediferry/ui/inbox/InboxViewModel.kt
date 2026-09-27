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
package app.fediferry.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.data.model.InboxStack
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.di.ServiceLocator
import app.fediferry.work.PostScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One part of the inbox: a stack and the posts on it, or — with no stack — New,
 * where shares arrive and anything not yet put on a stack waits.
 */
data class InboxSection(val stack: InboxStack?, val items: List<Item>) {
    companion object {
        /**
         * New first, then every stack in its order, each holding its posts in
         * the order given (newest first). A post whose stack is gone counts as
         * New, so nothing can fall out of sight. Empty stacks stay, so a stack
         * made ahead of time is there to move posts onto.
         */
        fun of(items: List<Item>, stacks: List<InboxStack>): List<InboxSection> {
            val known = stacks.map { it.id }.toSet()
            val byStack = items.groupBy { it.stackId?.takeIf { id -> id in known } }
            return listOf(InboxSection(null, byStack[null].orEmpty())) +
                stacks.map { InboxSection(it, byStack[it.id].orEmpty()) }
        }
    }
}

class InboxViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.items(app)
    private val stackDao = ServiceLocator.database(app).stacks()

    val items: StateFlow<List<Item>> = repo.observeInbox()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stacks: StateFlow<List<InboxStack>> = stackDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sections: StateFlow<List<InboxSection>> = combine(items, stacks, InboxSection::of)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- stacks ----------------------------------------------------------------

    /** Makes a stack, and puts [ids] on it straight away when given. */
    fun createStack(name: String, ids: Collection<String> = emptyList()) = viewModelScope.launch {
        val clean = name.trim().ifEmpty { return@launch }
        val stack = InboxStack(id = UUID.randomUUID().toString(), name = clean, sortOrder = stackDao.count())
        stackDao.upsert(stack)
        if (ids.isNotEmpty()) stackDao.move(ids, stack.id)
    }

    fun renameStack(stack: InboxStack, name: String) = viewModelScope.launch {
        name.trim().takeIf { it.isNotEmpty() }?.let { stackDao.rename(stack.id, it) }
    }

    /** Its posts go back to New; none is deleted with it. */
    fun deleteStack(stack: InboxStack) = viewModelScope.launch { stackDao.delete(stack.id) }

    fun setCollapsed(stack: InboxStack, collapsed: Boolean) =
        viewModelScope.launch { stackDao.setCollapsed(stack.id, collapsed) }

    /** Puts posts on [stackId], or back in New when it is null. */
    fun move(ids: Collection<String>, stackId: String?) = viewModelScope.launch { stackDao.move(ids, stackId) }

    /**
     * Posts everything on a stack that is ready to go — drafts, and posts that
     * failed — leaving what is already queued alone.
     */
    fun postStack(stack: InboxStack, spacingMinutes: Int = 0) {
        val ids = items.value.filter { it.stackId == stack.id && canPost(it) }.map { it.id }
        post(ids, spacingMinutes)
    }

    /**
     * Posts the given items. [spacingMinutes] staggers them so a batch of six
     * saved memes does not arrive on followers' timelines as one wall.
     */
    fun post(ids: Collection<String>, spacingMinutes: Int = 0) = viewModelScope.launch {
        ids.forEachIndexed { index, id ->
            repo.markQueued(id)
            PostScheduler.enqueue(
                getApplication(),
                id,
                delayMillis = index * spacingMinutes * 60_000L,
            )
        }
    }

    fun retry(id: String) = post(listOf(id))

    fun delete(ids: Collection<String>) = viewModelScope.launch {
        ids.forEach { id ->
            PostScheduler.cancel(getApplication(), id)
            repo.delete(id)
        }
    }

    fun cancelQueued(id: String) = viewModelScope.launch {
        PostScheduler.cancel(getApplication(), id)
        repo.markDraft(id)
    }

    companion object {
        fun canPost(item: Item) = item.status == Status.DRAFT || item.status == Status.FAILED
    }
}
