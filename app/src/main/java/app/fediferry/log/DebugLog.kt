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
package app.fediferry.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * An opt-in record of what the app did, kept so a problem that happened an hour
 * ago can still be looked at.
 *
 * Off by default and silent when off: a disabled logger opens no file and costs
 * one volatile read per call. What it records is *events* — a share arrived, a
 * resolver declined, a post failed with this reason — never content. Tokens and
 * post bodies are out of bounds by project rule, so callers do not pass them and
 * [redact] scrubs the shapes that leak by accident anyway.
 *
 * Writes go to a single background thread. Logging happens at human speed, a
 * handful of lines per share, so one thread is ample and the caller is never
 * made to wait on the disk.
 */
object DebugLog {

    /** Where the log lives, once [install] has been told. */
    @Volatile private var directory: File? = null

    /** Named in the header of every log, so an exported file identifies itself. */
    @Volatile private var version: String = "unknown"

    @Volatile private var enabled: Boolean = false

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fediferry-log").apply { isDaemon = true }
    }

    private val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Called once from the Application, before anything wants to log. */
    fun install(logDir: File, appVersion: String) {
        directory = logDir
        version = appVersion
    }

    /** Follows the setting; flipping it off stops writing immediately. */
    fun setEnabled(value: Boolean) {
        val was = enabled
        enabled = value
        if (value && !was) write("log", "Logging on — FediFerry $version, Android ${android.os.Build.VERSION.RELEASE}")
    }

    fun isEnabled(): Boolean = enabled

    fun d(tag: String, message: String) = write(tag, message)

    fun w(tag: String, message: String, error: Throwable? = null) =
        write(tag, if (error == null) "WARN $message" else "WARN $message — ${error.javaClass.simpleName}: ${error.message}")

    private fun write(tag: String, message: String) {
        if (!enabled) return
        val dir = directory ?: return
        val line = "${timestamp.format(Date())} [$tag] ${redact(message)}\n"
        writer.execute {
            runCatching {
                val file = current(dir)
                if (file.length() > MAX_BYTES) rotate(dir, file)
                current(dir).appendText(line)
            }
        }
    }

    /**
     * Last-resort scrubbing. Nothing should hand a secret to the logger in the
     * first place, but a message built from an HTTP error or a URL can carry one
     * without the caller noticing, and a log meant to be sent to someone else is
     * the wrong place to find out.
     */
    fun redact(message: String): String = message
        .replace(BEARER, "Bearer ###")
        .replace(TOKEN_PARAM) { "${it.groupValues[1]}###" }

    private val BEARER = Regex("""Bearer\s+[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE)
    private val TOKEN_PARAM = Regex(
        """((?:access_token|api_key|token|authorization)["'\s:=]+)[A-Za-z0-9._\-]{8,}""",
        RegexOption.IGNORE_CASE,
    )

    // --- the file ---------------------------------------------------------

    private const val MAX_BYTES = 256L * 1024

    fun current(dir: File): File = File(dir.apply { mkdirs() }, "fediferry.log")

    private fun previous(dir: File): File = File(dir, "fediferry.log.1")

    /** One generation back is kept, so rotating mid-problem loses nothing yet. */
    private fun rotate(dir: File, file: File) {
        val old = previous(dir)
        old.delete()
        file.renameTo(old)
    }

    /** Oldest first, so the export reads in the order things happened. */
    fun parts(dir: File): List<File> =
        listOf(previous(dir), current(dir)).filter { it.isFile && it.length() > 0 }

    fun sizeBytes(): Long = directory?.let { dir -> parts(dir).sumOf { it.length() } } ?: 0

    fun clear() {
        val dir = directory ?: return
        writer.execute { runCatching { parts(dir).forEach { it.delete() } } }
    }

    /**
     * Gathers the log into one file for sharing, under a name a messenger will
     * show legibly. Returns null when there is nothing to send.
     */
    fun export(into: File): File? {
        val dir = directory ?: return null
        val parts = parts(dir).ifEmpty { return null }

        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val out = File(into.apply { mkdirs() }, "fediferry-log-$stamp.txt")

        return runCatching {
            out.writeText("FediFerry $version — log exported $stamp\n\n")
            parts.forEach { out.appendText(it.readText()) }
            out
        }.getOrNull()
    }
}
