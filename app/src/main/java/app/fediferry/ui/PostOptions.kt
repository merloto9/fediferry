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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.Visibility

/** The names Mastodon's own apps use, so the choice reads the same in both places. */
val Visibility.label: String
    get() = when (this) {
        Visibility.PUBLIC -> "Public"
        Visibility.UNLISTED -> "Quiet public"
        Visibility.PRIVATE -> "Followers only"
        Visibility.DIRECT -> "Private mention"
    }

val Visibility.explanation: String
    get() = when (this) {
        Visibility.PUBLIC ->
            "Anyone can see it. It appears on your profile, in your followers' home " +
                "feeds, on the public timelines and under its hashtags, and it can be boosted."
        Visibility.UNLISTED ->
            "Anyone can see it and boost it, and your followers get it in their home " +
                "feeds — but it stays off the public timelines, trends and hashtag " +
                "searches. Good for not flooding the local feed with memes."
        Visibility.PRIVATE ->
            "Only your followers and anyone @mentioned in the text can see it. It " +
                "cannot be boosted, and people who are not following you see nothing on your profile."
        Visibility.DIRECT ->
            "Only the accounts @mentioned in the text can see it. Without a mention, " +
                "nobody but you will. Your server's admins can still read it — it is " +
                "not end-to-end encrypted."
    }

val AltTextMode.label: String
    get() = when (this) {
        AltTextMode.NONE -> "None"
        AltTextMode.STATIC -> "Fixed text"
        AltTextMode.VISION -> "Generated"
    }

val AltTextMode.explanation: String
    get() = when (this) {
        AltTextMode.NONE ->
            "Posts go out without a description. People using a screen reader hear " +
                "only that there is an image, and many on Mastodon skip or avoid posts " +
                "without one. You can still write one in the editor."
        AltTextMode.STATIC ->
            "Every post gets the same description, written below. Handy as a " +
                "fallback, but it cannot say what a particular meme shows — edit it in " +
                "the editor when it matters."
        AltTextMode.VISION ->
            "The picture is sent to the image-describing model set up under " +
                "Settings → Alt text, and its answer becomes the description. Check it " +
                "in the editor before posting; a post-now share sends it unread. If " +
                "the model fails, the post goes out without one."
    }

/**
 * Visibility chips with the chosen one explained underneath. Wraps, since four
 * chips do not fit across a phone.
 *
 * @param text the post text, to warn when a mention-only post mentions nobody.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisibilityPicker(
    selected: Visibility,
    onSelect: (Visibility) -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    title: String = "Visibility",
    titleStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = titleStyle)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Visibility.entries.forEach { visibility ->
                FilterChip(
                    selected = selected == visibility,
                    onClick = { onSelect(visibility) },
                    label = { Text(visibility.label) },
                )
            }
        }
        Explanation(selected.explanation)
        if (selected == Visibility.DIRECT && !MENTION.containsMatchIn(text)) {
            Explanation(
                "The text mentions no one yet, so this post would reach nobody.",
                warning = true,
            )
        }
    }
}

/** Alt-text source chips with the chosen one explained underneath. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AltTextModePicker(
    selected: AltTextMode,
    onSelect: (AltTextMode) -> Unit,
    visionConfigured: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Alt text", style = MaterialTheme.typography.labelMedium)
        Explanation(
            "A description of the picture for people who cannot see it. Screen readers " +
                "read it aloud, and it shows behind the ALT badge on the image.",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AltTextMode.entries.forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        Explanation(selected.explanation)
        if (selected == AltTextMode.VISION && !visionConfigured) {
            Explanation(
                "No model is set up yet — fill in Settings → Alt text, or every " +
                    "post from this template goes out without a description.",
                warning = true,
            )
        }
    }
}

@Composable
private fun Explanation(text: String, warning: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `@name` or `@name@server`, not an email address. */
private val MENTION = Regex("""(?<![\w@])@\w+""")
