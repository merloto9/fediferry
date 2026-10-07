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
package app.fediferry.ui.settings

import androidx.compose.ui.platform.LocalConfiguration
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.fediferry.R
import app.fediferry.i18n.AppLocale
import java.util.Locale

/**
 * Picks the language of the app's own screens. One row per language in
 * [AppLocale.SUPPORTED], each named in its own language, so adding one needs
 * no change here.
 */
@Composable
internal fun LanguageSection() {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(AppLocale.current(context)) }
    val shown = LocalConfiguration.current.locales[0]

    fun pick(tag: String?) {
        chosen = tag
        AppLocale.set(context, tag, context.findActivity())
    }

    Text(stringResource(R.string.language_intro), style = MaterialTheme.typography.bodySmall)
    Column(Modifier.selectableGroup()) {
        LanguageRow(
            title = stringResource(R.string.language_system),
            // The phone's own language, not the app's: the two differ once one is picked here.
            detail = stringResource(
                R.string.language_system_detail,
                AppLocale.nativeName(Resources.getSystem().configuration.locales[0].language),
            ),
            selected = chosen == null,
            onClick = { pick(null) },
        )
        AppLocale.SUPPORTED.forEach { tag ->
            val name = AppLocale.nativeName(tag)
            // The name in the language shown now too, when that differs: "Deutsch — German".
            val here = Locale.forLanguageTag(tag).getDisplayName(shown).replaceFirstChar { it.titlecase(shown) }
            LanguageRow(
                title = name,
                detail = here.takeIf { it != name },
                selected = chosen == tag,
                onClick = { pick(tag) },
            )
        }
    }
    Text(
        stringResource(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.language_footer_system else R.string.language_footer,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LanguageRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    )
}

/** The settings summary: the chosen language's own name, or that the phone's is followed. */
internal fun languageSummary(context: Context): String =
    AppLocale.current(context)?.let(AppLocale::nativeName) ?: context.getString(R.string.language_system)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
