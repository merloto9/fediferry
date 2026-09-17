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
import androidx.room.Index
import androidx.room.PrimaryKey
import app.fediferry.media.cleanup.CleanupRule
import app.fediferry.media.cleanup.Region
import app.fediferry.media.cleanup.TreatmentKind

/**
 * A named set of cleanup rules for one source of screenshots.
 *
 * A profile is data, not code: when Instagram moves a badge, or a screenshot
 * arrives from somewhere new, the fix is to edit a profile rather than to ship
 * a release.
 */
@Entity(tableName = "cleanup_profiles")
data class CleanupProfile(
    @PrimaryKey val id: String,
    val name: String,
    val isDefault: Boolean = false,
)

/**
 * One rule: where, and what to do there.
 *
 * The treatment belongs to the rule rather than to the profile because the kind
 * of artefact varies as much as its position — a translucent badge, a logo bar
 * and a username each want a different answer, and one profile may need all
 * three.
 *
 * Coordinates are fractions of the image so a rule written on one phone works
 * on any other.
 */
@Entity(
    tableName = "cleanup_rules",
    indices = [Index("profileId")],
)
data class ProfileRule(
    @PrimaryKey val id: String,
    val profileId: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val treatment: TreatmentKind,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
) {
    fun toCleanupRule() = CleanupRule(Region(left, top, right, bottom), treatment)
}
