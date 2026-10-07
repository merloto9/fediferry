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
package app.fediferry.server

import app.fediferry.server.db.Project
import app.fediferry.server.db.Project_token
import java.util.UUID

/** Who a request speaks for: one project, through one of its tokens. */
data class ProjectPrincipal(val projectId: String, val tokenId: String)

/**
 * Projects and their tokens. Creating, listing and deleting happen from the
 * server's command line only; the API never makes or shows a token.
 */
class Projects(private val storage: Storage, private val clock: () -> Long = System::currentTimeMillis) {

    private val db get() = storage.db

    data class Created(val project: Project, val token: String)

    fun create(name: String): Created {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "A project needs a name." }
        require(db.projectQueries.byIdOrName(clean).executeAsOneOrNull() == null) { "There is already a project called \"$clean\"." }
        val id = UUID.randomUUID().toString()
        val token = Tokens.generate()
        db.transaction {
            db.projectQueries.insert(id, clean, clock())
            db.projectTokenQueries.insert(UUID.randomUUID().toString(), id, Tokens.hash(token), "first token", clock())
        }
        return Created(db.projectQueries.byId(id).executeAsOne(), token)
    }

    fun all(): List<Project> = db.projectQueries.all().executeAsList()

    /** A project by its id or its name. */
    fun find(key: String): Project? = db.projectQueries.byIdOrName(key.trim()).executeAsOneOrNull()

    fun byId(id: String): Project? = db.projectQueries.byId(id).executeAsOneOrNull()

    /** Deletes a project and, through the foreign keys, everything in it. */
    fun delete(project: Project) = db.projectQueries.delete(project.id)

    fun tokens(project: Project): List<Project_token> = db.projectTokenQueries.byProject(project.id).executeAsList()

    /** A further token for the same project, say one per person, so each can be revoked alone. */
    fun addToken(project: Project, label: String): String {
        val token = Tokens.generate()
        db.projectTokenQueries.insert(UUID.randomUUID().toString(), project.id, Tokens.hash(token), label.trim(), clock())
        return token
    }

    /** Revokes one of [project]'s tokens; false when it is not one of them or already revoked. */
    fun revokeToken(project: Project, tokenId: String): Boolean {
        val token = db.projectTokenQueries.byId(tokenId).executeAsOneOrNull()
        if (token == null || token.project_id != project.id || token.revoked_at != null) return false
        db.projectTokenQueries.revoke(clock(), tokenId)
        return true
    }

    /** The project a presented token opens, or null for an unknown or revoked one. */
    fun authenticate(token: String): ProjectPrincipal? =
        db.projectTokenQueries.activeByHash(Tokens.hash(token)).executeAsOneOrNull()
            ?.let { ProjectPrincipal(it.project_id, it.id) }
}
