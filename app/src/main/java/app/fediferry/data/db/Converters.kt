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
package app.fediferry.data.db

import androidx.room.TypeConverter
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.Status
import app.fediferry.data.model.Visibility
import app.fediferry.media.cleanup.TreatmentKind

class Converters {
    @TypeConverter fun visibilityToString(v: Visibility): String = v.name
    @TypeConverter fun stringToVisibility(v: String): Visibility = Visibility.valueOf(v)

    @TypeConverter fun statusToString(v: Status): String = v.name
    @TypeConverter fun stringToStatus(v: String): Status = Status.valueOf(v)

    @TypeConverter fun altModeToString(v: AltTextMode): String = v.name
    @TypeConverter fun stringToAltMode(v: String): AltTextMode = AltTextMode.valueOf(v)

    @TypeConverter fun treatmentToString(v: TreatmentKind): String = v.name
    @TypeConverter fun stringToTreatment(v: String): TreatmentKind = TreatmentKind.valueOf(v)
}
