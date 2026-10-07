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

import app.fediferry.server.db.ServerDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts what must not sit in the database in the clear — AI keys, account
 * tokens — with AES-GCM under one master key. The key comes from
 * `FEDIFERRY_MASTER_KEY` (base64, 32 bytes) or, failing that, from
 * `<data>/master.key`, made on first start and readable by the server only.
 * Losing the key means those secrets have to be entered again; nothing else.
 */
class Crypto(private val key: ByteArray) {
    init {
        require(key.size == 32) { "The master key must be 32 bytes" }
    }

    private val random = SecureRandom()

    data class Sealed(val ciphertext: String, val nonce: String)

    fun seal(plain: String): Sealed {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val bytes = cipher.doFinal(plain.toByteArray())
        return Sealed(b64.encodeToString(bytes), b64.encodeToString(nonce))
    }

    fun open(ciphertext: String, nonce: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64d.decode(nonce)))
        return String(cipher.doFinal(b64d.decode(ciphertext)))
    }

    companion object {
        private val b64 = Base64.getEncoder()
        private val b64d = Base64.getDecoder()

        fun load(dataDir: File, env: String? = System.getenv("FEDIFERRY_MASTER_KEY")): Crypto {
            env?.takeIf { it.isNotBlank() }?.let { return Crypto(b64d.decode(it.trim())) }
            val file = File(dataDir, "master.key")
            if (!file.exists()) {
                dataDir.mkdirs()
                val key = ByteArray(32).also(SecureRandom()::nextBytes)
                file.writeText(b64.encodeToString(key))
                runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
            }
            return Crypto(b64d.decode(file.readText().trim()))
        }
    }
}

/** A project's secrets, sealed by [Crypto]. */
class Secrets(private val db: ServerDatabase, private val crypto: Crypto, private val clock: () -> Long) {

    fun put(projectId: String, id: String, value: String) {
        val sealed = crypto.seal(value)
        db.secretQueries.upsert(projectId, id, sealed.ciphertext, sealed.nonce, clock())
    }

    fun get(projectId: String, id: String): String? =
        db.secretQueries.byId(projectId, id).executeAsOneOrNull()?.let { crypto.open(it.ciphertext, it.nonce) }

    fun ids(projectId: String): List<String> = db.secretQueries.ids(projectId).executeAsList()

    fun delete(projectId: String, id: String) = db.secretQueries.delete(projectId, id)
}
