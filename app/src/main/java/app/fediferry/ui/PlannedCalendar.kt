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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fediferry.R
import app.fediferry.work.ScheduleWords
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** A post already planned, as the calendar marks it. */
data class PlannedMark(
    val at: Long,
    /** Its channel and the start of its text, say. */
    val label: String,
    /** The post being planned now, moved from this time. */
    val isThis: Boolean = false,
)

/**
 * A month to pick a day from, with what is planned already: a dot under a
 * day for each post on it, and the selected day's posts listed below. Days
 * before [today] cannot be picked.
 */
@Composable
fun PlannedCalendar(
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    today: LocalDate,
    planned: List<PlannedMark>,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val locale = Locale.getDefault()
    val byDay = remember(planned, zone) { planned.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() } }
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    val firstDay = WeekFields.of(locale).firstDayOfWeek

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }, enabled = month > YearMonth.from(today)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.calendar_previous_month))
            }
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { month = month.plusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.calendar_next_month))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).map { firstDay.plus(it.toLong()) }.forEach { day ->
                Text(
                    day.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val lead = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date -> DayCell(date, selected, today, byDay[date].orEmpty(), onSelect, Modifier.weight(1f)) }
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
        DayPlan(byDay[selected].orEmpty())
    }
}

@Composable
private fun DayCell(date: LocalDate?, selected: LocalDate, today: LocalDate, marks: List<PlannedMark>, onSelect: (LocalDate) -> Unit, modifier: Modifier) {
    Box(modifier.height(44.dp), contentAlignment = Alignment.Center) {
        if (date == null) return@Box
        val past = date.isBefore(today)
        val isSelected = date == selected
        val count = pluralStringResource(R.plurals.calendar_planned_count, marks.size, marks.size)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                .then(if (past) Modifier.alpha(0.38f) else Modifier.clickable { onSelect(date) })
                .semantics { if (marks.isNotEmpty()) contentDescription = "${date.dayOfMonth}, $count" },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "${date.dayOfMonth}",
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    date == today -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp)) {
                val dot = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.tertiary
                marks.take(MAX_DOTS).forEach { Box(Modifier.size(4.dp).clip(CircleShape).background(dot)) }
                if (marks.size > MAX_DOTS) Text("+", style = MaterialTheme.typography.labelSmall, color = dot, modifier = Modifier.padding(start = 1.dp))
            }
        }
    }
}

/** What is planned on the selected day already, by time. */
@Composable
private fun DayPlan(marks: List<PlannedMark>) {
    val resources = LocalResources.current
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (marks.isEmpty()) {
            Text(stringResource(R.string.calendar_nothing_planned), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Text(stringResource(R.string.calendar_planned_heading), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        marks.sortedBy { it.at }.forEach { mark ->
            Text(
                buildString {
                    append(ScheduleWords.timeText(resources, mark.at))
                    append(" · ")
                    append(if (mark.isThis) resources.getString(R.string.calendar_this_post) else mark.label)
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val MAX_DOTS = 3
