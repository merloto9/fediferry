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
package app.fediferry.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable

/**
 * Scaffold insets for a screen with text fields: the system bars, and the
 * keyboard while it is open.
 *
 * The app draws edge to edge, so the window is not resized for the keyboard
 * by itself. Left alone, Android pans the whole window to keep the cursor in
 * view, while Compose scrolls the same field into view as well. On every
 * keystroke the two corrections undo each other and the screen twitches up
 * and down. With these insets the content ends above the keyboard, the
 * window stays put (`adjustResize` in the manifest), and only Compose scrolls.
 */
val ScaffoldDefaults.typingInsets: WindowInsets
    @Composable get() = contentWindowInsets.union(WindowInsets.ime)
