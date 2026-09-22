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

/**
 * The content warnings offered as presets, German first and English second.
 *
 * A warning is read by people scrolling past, not by the poster, so it says what
 * is behind the cover in both languages of the timeline this app posts into. The
 * text is never final: a preset only fills the field, and what is sent is
 * whatever stands there when the post goes out.
 *
 * Several warnings combine into one line, separated by [SEPARATOR] — real posts
 * warn for violence and blood at once far more often than for either alone.
 * Presets carry commas of their own, so membership is tested by substring rather
 * than by splitting the line back apart.
 */
object ContentWarningPresets {

    const val SEPARATOR = ", "

    data class Preset(val de: String, val en: String) {
        /** What lands in `spoiler_text`. */
        val text: String get() = "$de / $en"
    }

    data class Group(val title: String, val presets: List<Preset>)

    val groups: List<Group> = listOf(
        Group(
            "Gewalt & Tod",
            listOf(
                Preset("Gewaltdarstellung", "Depiction of violence"),
                Preset("Blut, Gore", "Blood, gore"),
                Preset("Tod, Sterben", "Death, dying"),
                Preset("Krieg", "War"),
                Preset("Waffen", "Weapons"),
                Preset("Polizeigewalt", "Police violence"),
            ),
        ),
        Group(
            "Psychische Gesundheit",
            listOf(
                Preset("Suizid, Selbstverletzung", "Suicide, self-harm"),
                Preset("Psychische Gesundheit", "Mental health"),
                Preset("Essstörung, Körperbild", "Eating disorder, body image"),
                Preset("Sexualisierte Gewalt, Missbrauch", "Sexual violence, abuse"),
            ),
        ),
        Group(
            "Sexualität & Körper",
            listOf(
                Preset("Sexueller Inhalt (NSFW)", "Sexual content (NSFW)"),
                Preset("Nacktheit", "Nudity"),
                Preset("Medizinisches, Nadeln", "Medical, needles"),
            ),
        ),
        Group(
            "Hass & Diskriminierung",
            listOf(
                Preset("NS-Symbolik, Rechtsextremismus", "Nazi symbolism, far-right extremism"),
                Preset("Rassismus", "Racism"),
                Preset("Antisemitismus", "Antisemitism"),
                Preset("Queerfeindlichkeit, Transfeindlichkeit", "Anti-queer, anti-trans hostility"),
                Preset("Sexismus", "Sexism"),
                Preset("Ableismus", "Ableism"),
                Preset("Diskriminierung", "Discrimination"),
            ),
        ),
        Group(
            "Substanzen & Essen",
            listOf(
                Preset("Drogen", "Drugs"),
                Preset("Alkohol", "Alcohol"),
                Preset("Essen", "Food"),
            ),
        ),
        Group(
            "Reize & Phobien",
            listOf(
                Preset("Blinklichter, schnelle Schnitte", "Flashing lights, rapid motion"),
                Preset("Spinnen", "Spiders"),
                Preset("Insekten", "Insects"),
                Preset("Blickkontakt", "Eye contact"),
            ),
        ),
        Group(
            "Tiere, Ton & Kontext",
            listOf(
                Preset("Tierleid, Tiertod", "Animal suffering, animal death"),
                Preset("Politik", "Politics"),
                Preset("Schwarzer Humor", "Dark humour"),
                Preset("Vulgäre Sprache", "Strong language"),
                Preset("Spoiler", "Spoilers"),
                Preset("Klimakrise", "Climate crisis"),
            ),
        ),
    )

    val all: List<Preset> = groups.flatMap { it.presets }

    /** True when [current] already carries this preset, whatever else it says. */
    fun isApplied(current: String?, preset: Preset): Boolean =
        current?.contains(preset.text) == true

    /** Adds the preset to [current], or takes it back out if it is already there. */
    fun toggle(current: String?, preset: Preset): String =
        if (isApplied(current, preset)) remove(current.orEmpty(), preset.text)
        else append(current.orEmpty(), preset.text)

    private fun append(current: String, text: String): String =
        if (current.isBlank()) text else tidy(current) + SEPARATOR + text

    private fun remove(current: String, text: String): String {
        val head = tidy(current.substringBefore(text))
        val tail = current.substringAfter(text).trim().trimStart(',').trim()
        return when {
            head.isEmpty() -> tail
            tail.isEmpty() -> head
            else -> head + SEPARATOR + tail
        }
    }

    /** Strips the trailing separator a removal or a hand edit can leave behind. */
    private fun tidy(text: String): String = text.trim().trimEnd(',').trim()
}
