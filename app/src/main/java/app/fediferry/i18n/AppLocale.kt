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
package app.fediferry.i18n

import androidx.core.content.edit
import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The languages the app's own screens come in, and which one is in use.
 *
 * English is the default and the fallback for anything not translated.
 * Adding a language is two steps: a `values-<tag>/` folder with its strings,
 * and its tag in [SUPPORTED]. The Settings list, its names and the system's
 * per-app language screen all follow from that.
 *
 * Android 13 and later keeps the choice itself, per app, and shows it under
 * System settings → Apps → FediFerry → Language as well. Older versions have
 * no such setting, so the choice is kept here and applied to every screen as
 * it opens.
 */
object AppLocale {

    /** Language tags with a full set of strings, English first. */
    val SUPPORTED = listOf("en", "de")

    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    /** The chosen language's tag, or null to follow the phone. */
    fun current(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
                .takeIf { !it.isEmpty }?.get(0)?.language
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
        }

    /**
     * Switches the app to [tag], or back to the phone's language with null.
     * On Android 13+ the system redraws open screens; before that, [activity]
     * is recreated so the change shows at once.
     */
    fun set(context: Context, tag: String?, activity: Activity? = null) {
        require(tag == null || tag in SUPPORTED) { "Unsupported language: $tag" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_TAG, tag) }
            activity?.recreate()
        }
    }

    /**
     * Before Android 13: [base] with the chosen language applied, for an
     * activity's or the application's `attachBaseContext`. Unchanged otherwise.
     */
    // Shipped as an APK with every language in it, so there are no language
    // splits to download first.
    @SuppressLint("AppBundleLocaleChanges")
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    /** A language's name in that language itself — "English", "Deutsch" — as menus show it. */
    fun nativeName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
