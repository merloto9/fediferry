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
package app.fediferry.template

import app.fediferry.data.model.Item

/** Everything an existing item can tell the engine about itself. */
fun TemplateEngine.inputsOf(item: Item) = TemplateEngine.Inputs(link = item.sourceUrl, source = item.origin, fields = item.sourceFields)

/** What [item] will actually post. */
fun TemplateEngine.postTextOf(item: Item): String = finish(item.bodyText, item.hashtagList)
