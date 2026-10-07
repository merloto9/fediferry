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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.time.Instant

/** The version the jar was built as; "dev" when run from the build. */
val SERVER_VERSION: String = Services::class.java.`package`?.implementationVersion ?: "dev"

/**
 * `fediferry` — the server and its housekeeping, in one program:
 *
 * ```
 * fediferry serve                      run the API
 * fediferry project create "Memes"     make a project and print its token
 * fediferry project list | delete <name>
 * fediferry token add <project> [--label phone] | list <project> | revoke <project> <token id>
 * ```
 *
 * All of them work on the data directory given by `--data` or `FEDIFERRY_DATA`.
 */
fun main(args: Array<String>) = Root().subcommands(
    Serve(),
    ProjectCommand().subcommands(ProjectCreate(), ProjectList(), ProjectDelete()),
    TokenCommand().subcommands(TokenAdd(), TokenList(), TokenRevoke()),
).main(args)

private class Root : NoOpCliktCommand(name = "fediferry") {
    override fun help(context: Context) = "The FediFerry server: one library and its fediverse channels per project."
}

/** Commands that work on the data directory. */
private abstract class DataCommand(name: String) : CliktCommand(name = name) {
    val data by option("--data", envvar = "FEDIFERRY_DATA", help = "Data directory (database and media)").default("data")

    fun <T> withStorage(block: (Storage) -> T): T = Storage.open(File(data, "fediferry.db")).use(block)
}

private class Serve : DataCommand("serve") {
    override fun help(context: Context) = "Run the FediFerry API."
    val host by option("--host", envvar = "FEDIFERRY_HOST").default("0.0.0.0")
    val port by option("--port", envvar = "FEDIFERRY_PORT").int().default(8080)

    override fun run() {
        val storage = Storage.open(File(data, "fediferry.db"))
        val services = Services(storage, File(data))
        echo("FediFerry server $SERVER_VERSION on http://$host:$port — data in ${File(data).absolutePath}")
        if (services.projects.all().isEmpty()) echo("No projects yet. Create one with: fediferry project create \"Name\"")
        embeddedServer(Netty, port = port, host = host) { fediferry(services) }.start(wait = true)
    }
}

private class ProjectCommand : NoOpCliktCommand(name = "project") {
    override fun help(context: Context) = "Create, list and delete projects."
}

private class ProjectCreate : DataCommand("create") {
    override fun help(context: Context) = "Create a project and print its token."
    val name by argument(help = "The project's name")

    override fun run() = withStorage { storage ->
        val created = runCatching { Projects(storage).create(name) }.getOrElse { throw UsageError(it.message) }
        echo("Created project \"${created.project.name}\" (${created.project.id}).")
        echo("")
        echo("Project token — enter it on every device that works on this project:")
        echo("")
        echo("    ${created.token}")
        echo("")
        echo("It is shown only now. Keep it somewhere safe; a lost token can be replaced with `fediferry token add`.")
    }
}

private class ProjectList : DataCommand("list") {
    override fun help(context: Context) = "List the projects."
    override fun run() = withStorage { storage ->
        val projects = Projects(storage)
        val all = projects.all()
        if (all.isEmpty()) echo("No projects.")
        all.forEach { p ->
            val active = projects.tokens(p).count { it.revoked_at == null }
            echo("${p.name}  ${p.id}  created ${Instant.ofEpochMilli(p.created_at)}  $active active token(s)")
        }
    }
}

private class ProjectDelete : DataCommand("delete") {
    override fun help(context: Context) = "Delete a project and everything in it."
    val key by argument(help = "The project's name or id")
    val yes by option("--yes", help = "Do not ask").flag()

    override fun run() = withStorage { storage ->
        val projects = Projects(storage)
        val project = projects.find(key) ?: throw UsageError("No project \"$key\".")
        if (!yes) {
            echo("Delete \"${project.name}\" with its whole library, drafts and channels? Type the name to confirm:")
            if (readlnOrNull()?.trim() != project.name) throw UsageError("Not deleted.")
        }
        projects.delete(project)
        echo("Deleted \"${project.name}\".")
    }
}

private class TokenCommand : NoOpCliktCommand(name = "token") {
    override fun help(context: Context) = "Add, list and revoke a project's tokens."
}

private class TokenAdd : DataCommand("add") {
    override fun help(context: Context) = "Make another token for a project, e.g. one per person."
    val key by argument(help = "The project's name or id")
    val label by option("--label", help = "Who or what the token is for").default("token")

    override fun run() = withStorage { storage ->
        val projects = Projects(storage)
        val project = projects.find(key) ?: throw UsageError("No project \"$key\".")
        val token = projects.addToken(project, label)
        echo("New token for \"${project.name}\" ($label), shown only now:")
        echo("")
        echo("    $token")
    }
}

private class TokenList : DataCommand("list") {
    override fun help(context: Context) = "List a project's tokens (never the tokens themselves)."
    val key by argument(help = "The project's name or id")

    override fun run() = withStorage { storage ->
        val projects = Projects(storage)
        val project = projects.find(key) ?: throw UsageError("No project \"$key\".")
        projects.tokens(project).forEach { t ->
            val state = t.revoked_at?.let { "revoked ${Instant.ofEpochMilli(it)}" } ?: "active"
            echo("${t.id}  ${t.label}  created ${Instant.ofEpochMilli(t.created_at)}  $state")
        }
    }
}

private class TokenRevoke : DataCommand("revoke") {
    override fun help(context: Context) = "Revoke one of a project's tokens; devices using it are locked out."
    val key by argument(help = "The project's name or id")
    val tokenId by argument(help = "The token's id, from `token list`")

    override fun run() = withStorage { storage ->
        val projects = Projects(storage)
        val project = projects.find(key) ?: throw UsageError("No project \"$key\".")
        if (!projects.revokeToken(project, tokenId)) throw UsageError("No active token $tokenId in \"${project.name}\".")
        echo("Revoked.")
    }
}
