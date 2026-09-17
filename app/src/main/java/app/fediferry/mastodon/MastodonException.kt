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
package app.fediferry.mastodon

/**
 * A failure talking to an instance. [retryable] distinguishes "try again later"
 * (5xx, 429, network) from "this will never work" (401, 422), which is what
 * PostWorker keys its retry decision off.
 */
class MastodonException(
    message: String,
    val code: Int = 0,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
