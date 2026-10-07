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
package app.fediferry.module.reddit

import app.fediferry.BuildConfig
import app.fediferry.R
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.SourceField
import app.fediferry.module.SourceModule
import okhttp3.OkHttpClient

object RedditModule : SourceModule {
    override val source = ContentSource.REDDIT
    override val name = "Reddit"
    override val summary = R.string.extra_module_reddit_summary
    override val recognises = listOf("reddit.com/r/…/s/…", "reddit.com/r/…/comments/…", "redd.it/…", "i.redd.it/…")
    override val fields = listOf(
        SourceField("title", R.string.extra_field_reddit_title),
        SourceField("subreddit", R.string.extra_field_reddit_subreddit),
    )
    override val defaultRecipes = mapOf("caption" to "{title}")

    override fun resolver(http: OkHttpClient) = RedditResolver(http, BuildConfig.VERSION_NAME)
}
