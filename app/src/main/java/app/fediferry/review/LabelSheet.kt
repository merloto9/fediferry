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
package app.fediferry.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.fediferry.R

/**
 * Labels for one or more ready posts. A label is ticked when every post
 * carries it; ticking puts it on all of them, unticking takes it off all.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LabelSheet(
    /** Every label in use in the project, and any these posts have. */
    known: List<String>,
    /** Labels each of the posts carries. */
    onAll: List<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    fun add() {
        val name = typed.trim().removePrefix("#")
        if (name.isNotEmpty()) onToggle(name, true)
        typed = ""
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.review_labels), style = MaterialTheme.typography.titleMedium)
            if (known.isEmpty()) Text(stringResource(R.string.review_labels_none), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                known.forEach { label ->
                    val on = onAll.any { it.equals(label, ignoreCase = true) }
                    FilterChip(
                        selected = on,
                        onClick = { onToggle(label, !on) },
                        label = { Text(label) },
                        leadingIcon = if (on) { { Icon(Icons.Default.Check, contentDescription = null) } } else null,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.review_new_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = ::add, enabled = typed.isNotBlank()) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.review_add_label))
                }
            }
        }
    }
}
