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

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.VisualTransformation

/**
 * A text field that does not fight the person typing into it.
 *
 * Binding a field straight to a value that travels through a StateFlow — or
 * worse, through DataStore and back off disk — means every keystroke is
 * followed a frame later by the field being handed an older string. The cursor
 * jumps to the end, characters arrive out of order, and fast typing is close to
 * impossible.
 *
 * So the field owns its text. Edits are reported upwards immediately for
 * saving, and what comes back is recognised as our own and ignored.
 *
 * It still follows changes made elsewhere — a stored value arriving a frame
 * after the screen opens, or alt text being regenerated — but only while the
 * field does not have focus. Whoever is typing owns the text until they leave.
 *
 * Two earlier attempts got this wrong. Seeding once and never looking again left
 * every settings field blank, because the stored value had not arrived yet when
 * the field was built. Adopting anything that differed from the last value sent
 * up scrambled fast typing instead: a value saved to disk comes back several
 * keystrokes later, and by then it no longer matches, so it overwrote the
 * characters typed in the meantime.
 */
@Composable
fun StableTextField(
    key: Any?,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var text by remember(key) { mutableStateOf(value) }
    var focused by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key, value, focused) {
        if (!focused && value != text) text = value
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        visualTransformation = visualTransformation,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}
