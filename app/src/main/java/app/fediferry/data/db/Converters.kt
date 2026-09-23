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
import app.fediferry.data.model.AiKind
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Status
import app.fediferry.data.model.SourceKind
import app.fediferry.data.model.Visibility
import app.fediferry.media.cleanup.TreatmentKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Converters {
    @TypeConverter fun visibilityToString(v: Visibility): String = v.name
    @TypeConverter fun stringToVisibility(v: String): Visibility = Visibility.valueOf(v)

    @TypeConverter fun statusToString(v: Status): String = v.name
    @TypeConverter fun stringToStatus(v: String): Status = Status.valueOf(v)

    @TypeConverter fun altModeToString(v: AltTextMode): String = v.name
    @TypeConverter fun stringToAltMode(v: String): AltTextMode = AltTextMode.valueOf(v)

    @TypeConverter fun sourceKindToString(v: SourceKind): String = v.name
    @TypeConverter fun stringToSourceKind(v: String): SourceKind = SourceKind.valueOf(v)

    @TypeConverter fun treatmentToString(v: TreatmentKind): String = v.name
    @TypeConverter fun stringToTreatment(v: String): TreatmentKind = TreatmentKind.valueOf(v)

    @TypeConverter fun aiKindToString(v: AiKind): String = v.name
    @TypeConverter fun stringToAiKind(v: String): AiKind = AiKind.valueOf(v)

    @TypeConverter fun contentSourceToString(v: ContentSource?): String? = v?.name
    @TypeConverter fun stringToContentSource(v: String?): ContentSource? = ContentSource.fromName(v)

    /** A source that no longer exists is dropped rather than failing the whole row. */
    @TypeConverter fun sourcesToString(v: Set<ContentSource>): String =
        v.sortedBy { it.ordinal }.joinToString(",") { it.name }

    @TypeConverter fun stringToSources(v: String): Set<ContentSource> =
        v.split(",").mapNotNull { ContentSource.fromName(it.trim()) }.toSet()

    @TypeConverter fun stringMapToString(v: Map<String, String>): String =
        JsonObject(v.mapValues { JsonPrimitive(it.value) }).toString()

    /** Unreadable data degrades to no data: it only ever feeds placeholders. */
    @TypeConverter fun stringToStringMap(v: String): Map<String, String> = runCatching {
        Json.parseToJsonElement(v).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())
}
