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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fediferry.R
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.PlaceholderKey

/** One placeholder, and the honest story about when it is empty. */
private data class Placeholder(
    val token: String,
    val what: String,
    val whenEmpty: String,
)

/** The built-in placeholders, in the app's language. */
@Composable
private fun builtInPlaceholders(): List<Placeholder> = listOf(
    Placeholder(
        "{tags}",
        stringResource(R.string.help_tags_what),
        stringResource(R.string.help_tags_empty),
    ),
    Placeholder(
        "{link}",
        stringResource(R.string.help_link_what),
        stringResource(R.string.help_link_empty),
    ),
    Placeholder(
        "{date}",
        stringResource(R.string.help_date_what),
        stringResource(R.string.help_date_empty),
    ),
)

/** A user-defined placeholder, described by what each source fills it with. */
@Composable
private fun PlaceholderKey.asPlaceholder(): Placeholder {
    val mapped = ContentSource.entries.filter { recipeFor(it).isNotBlank() }
    val unmapped = ContentSource.entries - mapped.toSet()
    val mappedItems = mapped.map { stringResource(R.string.help_custom_mapped_item, it.label, recipeFor(it)) }
    return Placeholder(
        token = "{$name}",
        what = if (mapped.isEmpty()) {
            stringResource(R.string.help_custom_unmapped)
        } else {
            mappedItems.joinToString("; ")
        },
        whenEmpty = if (unmapped.isEmpty()) {
            stringResource(R.string.help_custom_empty)
        } else {
            stringResource(R.string.help_custom_empty_unmapped, unmapped.joinToString { it.label })
        },
    )
}

/**
 * The rule that surprises people: a line whose placeholders all came back empty
 * is dropped whole, so `via {link}` does not post a dangling "via".
 */
@Composable
fun PlaceholderHelpDialog(keys: List<PlaceholderKey>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.help_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.help_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                (builtInPlaceholders() + keys.filterNot { it.isTags }.map { it.asPlaceholder() }).forEach { p ->
                    Column {
                        Text(
                            p.token,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(p.what, style = MaterialTheme.typography.bodySmall)
                        Text(
                            p.whenEmpty,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.help_empty_lines_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.help_empty_lines),
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Text(stringResource(R.string.help_example), style = MaterialTheme.typography.titleSmall)
                Text(
                    "{tags}\n\nvia {link}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.help_unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.help_close)) } },
    )
}
