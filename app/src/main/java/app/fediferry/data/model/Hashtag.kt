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
package app.fediferry.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One hashtag in the list the user keeps in Settings. Templates pick a subset
 * of these, and the editor offers all of them.
 */
@Entity(tableName = "hashtags")
data class Hashtag(
    /** With its #, as it is posted. */
    @PrimaryKey val tag: String,
    val sortOrder: Int = 0,
)

/**
 * How many sent posts carried a hashtag. Counted for every hashtag a post went
 * out with, on the list or not, so the editor can offer the ones used most
 * first. Kept apart from the posts, so removing old posts keeps the counts.
 */
@Entity(tableName = "hashtag_usage")
data class HashtagUsage(
    /** The tag's [Hashtags.key]: lower case, so #Meme and #meme count together. */
    @PrimaryKey val key: String,
    val uses: Int,
)

/** How hashtags are cleaned up, compared, sorted and stored, everywhere in the app. */
object Hashtags {

    /** What makes two spellings the same tag: Mastodon ignores case. */
    fun key(tag: String): String = tag.removePrefix("#").lowercase()

    /**
     * Sorts by the tag itself, ignoring case and the #, the way a person
     * would — Ä next to A, not after Z.
     */
    val alphabetical: Comparator<String> = run {
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.SECONDARY }
        Comparator { a, b -> collator.compare(a.removePrefix("#"), b.removePrefix("#")).takeIf { it != 0 } ?: a.compareTo(b) }
    }

    fun sortedAlphabetically(tags: List<String>): List<String> = tags.sortedWith(alphabetical)

    /** Most used first; equally used ones — and never used ones — alphabetically. */
    fun sortedByUse(tags: List<String>, uses: Map<String, Int>): List<String> =
        tags.sortedWith(compareByDescending<String> { uses[key(it)] ?: 0 }.then(alphabetical))

    private val SEPARATORS = Regex("""[\s,;]+""")

    /** Anything Mastodon would not treat as part of the tag. */
    private val NOT_TAG = Regex("""[^\p{L}\p{N}_]""")

    /** `meme`, `#meme` and `##meme!` all become `#meme`; nothing usable becomes null. */
    fun normalize(raw: String): String? =
        raw.trim().replace(NOT_TAG, "").takeIf { it.isNotEmpty() }?.let { "#$it" }

    /**
     * Every hashtag in [text], in order, once each. Mastodon matches tags
     * without regard to case, so `#Meme` after `#meme` is the same tag.
     */
    fun parse(text: String?): List<String> =
        text.orEmpty().split(SEPARATORS).mapNotNull(::normalize).distinctBy { it.lowercase() }

    fun format(tags: List<String>): String = tags.joinToString(" ")

    /** [first], then whatever of [second] it does not already have. */
    fun union(first: List<String>, second: List<String>): List<String> =
        (first + second).distinctBy { it.lowercase() }

    fun contains(tags: List<String>, tag: String): Boolean = tags.any { it.equals(tag, ignoreCase = true) }

    /**
     * Every hashtag written in [text], as Mastodon would read it: a `#` that
     * starts a word, followed by letters, digits or `_`, not all of them digits.
     * A link's `page#section` is not a hashtag, and neither is `#1`.
     */
    fun inText(text: String): List<String> =
        IN_TEXT.findAll(text)
            .map { it.groupValues[1] }
            .filter { tag -> tag.any { !it.isDigit() } }
            .map { "#$it" }
            .distinctBy { it.lowercase() }
            .toList()

    /** The hashtags in [text] that [known] does not have yet, in the order written. */
    fun newIn(text: String, known: List<String>): List<String> = inText(text).filterNot { contains(known, it) }

    private val IN_TEXT = Regex("""(?<![\p{L}\p{N}_/#&])#([\p{L}\p{N}_]+)""")
}
