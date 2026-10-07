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

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.fediferry.server.db.ServerDatabase
import java.io.File
import java.util.Properties

/**
 * The server's one SQLite file. WAL lets readers carry on while a write
 * happens, which is all the concurrency a self-hosted server needs; the busy
 * timeout makes a second writer wait rather than fail.
 */
class Storage private constructor(val driver: JdbcSqliteDriver, val db: ServerDatabase) : AutoCloseable {

    override fun close() = driver.close()

    companion object {
        fun open(file: File): Storage {
            file.absoluteFile.parentFile?.mkdirs()
            val properties = Properties().apply {
                put("foreign_keys", "true")
                put("journal_mode", "WAL")
                put("busy_timeout", "5000")
            }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", properties)
            migrate(driver)
            return Storage(driver, ServerDatabase(driver))
        }

        /** Creates the schema in a new file, or brings an older one up to date. */
        private fun migrate(driver: JdbcSqliteDriver) {
            val schema = ServerDatabase.Schema
            val current = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
                parameters = 0,
            ).value
            when {
                current == 0L -> schema.create(driver)
                current < schema.version -> schema.migrate(driver, current, schema.version)
                current > schema.version -> error("The data file is from a newer FediFerry (schema $current); update the server.")
                else -> return
            }
            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
        }
    }
}
