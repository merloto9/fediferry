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
package app.fediferry

import android.app.Application
import java.io.File
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import app.fediferry.di.ServiceLocator
import app.fediferry.log.DebugLog
import app.fediferry.share.ShortcutPublisher
import app.fediferry.work.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FediFerryApp : Application(), SingletonImageLoader.Factory {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Two things Coil does not do on its own.
     *
     * It ships no network fetcher, so a remote URL simply never loads — which
     * went unnoticed until the Sources space, since every image before it came
     * from a local file. And without the video decoder an animated 9GAG post
     * previews as a blank box, though the attachment itself is fine.
     *
     * The app's own OkHttp client is reused, so images share its timeouts and
     * connection pool rather than opening a second stack.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { ServiceLocator.http() }))
                add(VideoFrameDecoder.Factory())
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        ShortcutPublisher.publish(this)

        // Installed before anything can log, and left off until the setting
        // says otherwise; the collector outlives the app's other work.
        DebugLog.install(File(filesDir, "logs"), BuildConfig.VERSION_NAME)
        scope.launch {
            ServiceLocator.settings(this@FediFerryApp).settings
                .map { it.debugLogging }
                .distinctUntilChanged()
                .collect { DebugLog.setEnabled(it) }
        }

        scope.launch {
            val repo = ServiceLocator.items(this@FediFerryApp)
            repo.seedIfEmpty()
            // Anything left POSTING is a survivor of a killed process, not a
            // running send; put it back in the queue.
            repo.recoverStalePosting()
            val days = ServiceLocator.settings(this@FediFerryApp).current().purgePostedAfterDays
            repo.purgePosted(days)
        }
    }
}
