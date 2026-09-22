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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.PlaceholderKey

/** One placeholder, and the honest story about when it is empty. */
private data class Placeholder(
    val token: String,
    val what: String,
    val whenEmpty: String,
)

private val PLACEHOLDERS = listOf(
    Placeholder(
        "{tags}",
        "The post's hashtags: the template's picks, plus any a source adds. Filled when " +
            "the post is sent, so it stays {tags} in the editor — change the hashtags with # " +
            "next to the template.",
        "Empty when no hashtags are picked.",
    ),
    Placeholder(
        "{link}",
        "The permalink of the post that was shared.",
        "Empty unless a link came with the share. Instagram's share sheet sends one; " +
            "a plain screenshot does not.",
    ),
    Placeholder(
        "{date}",
        "Today's date, as yyyy-MM-dd.",
        "Never empty.",
    ),
)

/** A user-defined placeholder, described by what each source fills it with. */
private fun PlaceholderKey.asPlaceholder(): Placeholder {
    val mapped = ContentSource.entries.filter { recipeFor(it).isNotBlank() }
    val unmapped = ContentSource.entries - mapped.toSet()
    return Placeholder(
        token = "{$name}",
        what = if (mapped.isEmpty()) {
            "Defined under Settings → Placeholders, but no source module fills it yet."
        } else {
            mapped.joinToString("; ") { "${it.label}: ${recipeFor(it)}" }
        },
        whenEmpty = "Empty for screenshots and unfetched links" +
            (if (unmapped.isEmpty()) "" else ", and for " + unmapped.joinToString { it.label }) +
            ", and for any source a template has unticked.",
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
        title = { Text("Placeholders") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Anything in braces is replaced when the post is written.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                (PLACEHOLDERS + keys.filterNot { it.isTags }.map { it.asPlaceholder() }).forEach { p ->
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
                Text("Empty lines disappear", style = MaterialTheme.typography.titleSmall)
                Text(
                    "If every placeholder on a line comes back empty, the whole line is " +
                        "dropped — so a template ending in \"via {link}\" posts nothing at " +
                        "all when there was no link, rather than a dangling \"via\".",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Text("Example", style = MaterialTheme.typography.titleSmall)
                Text(
                    "{tags}\n\nvia {link}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "An unknown placeholder is left exactly as typed, so a mistake shows " +
                        "up instead of vanishing.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
