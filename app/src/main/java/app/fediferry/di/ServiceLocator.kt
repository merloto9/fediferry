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
package app.fediferry.di

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.fediferry.alt.AltTextProvider
import app.fediferry.alt.NoAltTextProvider
import app.fediferry.alt.StaticAltTextProvider
import app.fediferry.alt.VisionAltTextProvider
import app.fediferry.data.ItemRepository
import app.fediferry.data.MediaVault
import app.fediferry.data.SettingsStore
import app.fediferry.data.TokenStore
import app.fediferry.data.db.AppDatabase
import app.fediferry.link.LinkResolver
import app.fediferry.link.NineGagResolver
import app.fediferry.link.OkHttpMediaFetcher
import app.fediferry.media.cleanup.EditWireFormat
import app.fediferry.media.cleanup.HttpImageEditProvider
import app.fediferry.media.cleanup.ImageEditProvider
import app.fediferry.media.cleanup.MaskPolarity
import app.fediferry.media.cleanup.NoImageEditProvider
import app.fediferry.source.YouTubeSourceClient
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.Template
import app.fediferry.mastodon.AuthManager
import app.fediferry.mastodon.MastodonClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled singletons. The app has one graph and no test doubles worth a DI
 * framework; workers and receivers reach in through [Context] alone.
 */
@SuppressLint("StaticFieldLeak") // every holder below is built from applicationContext
object ServiceLocator {

    @Volatile private var db: AppDatabase? = null
    @Volatile private var repo: ItemRepository? = null
    @Volatile private var http: OkHttpClient? = null
    @Volatile private var api: MastodonClient? = null
    @Volatile private var tokenStore: TokenStore? = null
    @Volatile private var settingsStore: SettingsStore? = null
    @Volatile private var authManager: AuthManager? = null
    @Volatile private var resolverHttp: OkHttpClient? = null
    @Volatile private var youtubeClient: YouTubeSourceClient? = null

    /**
     * For work that must finish even though the screen that started it is gone.
     *
     * Saving a setting is the case that matters: viewModelScope is cancelled
     * the moment the settings screen is left, so typing a value and going
     * straight back lost it.
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    fun database(context: Context): AppDatabase = db ?: synchronized(this) {
        db ?: AppDatabase.build(context.applicationContext).also { db = it }
    }

    fun items(context: Context): ItemRepository = repo ?: synchronized(this) {
        repo ?: run {
            val database = database(context)
            ItemRepository(
                items = database.items(),
                templates = database.templates(),
                accounts = database.accounts(),
                media = MediaVault(context.applicationContext),
                cleanupDao = database.cleanup(),
                editProvider = { imageEditProvider(context) },
                maskPolarity = { maskPolarity(context) },
                editInstruction = { settings(context).current().imageInstruction },
                resolvers = linkResolvers(),
                fetcher = OkHttpMediaFetcher(linkHttp()),
            )
        }.also { repo = it }
    }

    fun http(): OkHttpClient = http ?: synchronized(this) {
        http ?: OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
            .also { http = it }
    }

    /**
     * Services whose links can be turned back into media. Order matters only in
     * that the first match wins; no two resolvers claim the same host.
     */
    /** Reads followed channels' community posts. */
    fun youtube(): YouTubeSourceClient = youtubeClient ?: synchronized(this) {
        youtubeClient ?: YouTubeSourceClient(http()).also { youtubeClient = it }
    }

    fun linkResolvers(): List<LinkResolver> = listOf(NineGagResolver(linkHttp()))

    /**
     * A tighter-deadline client for link resolution. A share must not sit
     * waiting on a slow third party — if it takes this long, fall back to the
     * screenshot flow instead.
     */
    private fun linkHttp(): OkHttpClient = resolverHttp ?: synchronized(this) {
        resolverHttp ?: http().newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
            .also { resolverHttp = it }
    }

    fun mastodon(context: Context): MastodonClient = api ?: synchronized(this) {
        api ?: MastodonClient(http()).also { api = it }
    }

    fun tokens(context: Context): TokenStore = tokenStore ?: synchronized(this) {
        tokenStore ?: TokenStore(context.applicationContext).also { tokenStore = it }
    }

    fun settings(context: Context): SettingsStore = settingsStore ?: synchronized(this) {
        settingsStore ?: SettingsStore(context.applicationContext).also { settingsStore = it }
    }

    fun auth(context: Context): AuthManager = authManager ?: synchronized(this) {
        authManager ?: AuthManager(
            context = context.applicationContext,
            client = mastodon(context),
            accounts = database(context).accounts(),
            tokens = tokens(context),
        ).also { authManager = it }
    }

    /**
     * The image model behind the "Erase with AI" treatment, or
     * [NoImageEditProvider] when none is configured. The cleanup pipeline falls
     * back to a local fill either way, so an absent model is not an error.
     */
    suspend fun imageEditProvider(context: Context): ImageEditProvider {
        val s = settings(context).current()
        if (s.imageEndpoint.isBlank()) return NoImageEditProvider
        return HttpImageEditProvider(
            client = http(),
            endpoint = s.imageEndpoint,
            model = s.imageModel,
            apiKey = s.imageApiKey,
            wireFormat = runCatching { EditWireFormat.valueOf(s.imageWireFormat) }
                .getOrDefault(EditWireFormat.MULTIPART),
        )
    }

    suspend fun maskPolarity(context: Context): MaskPolarity =
        runCatching { MaskPolarity.valueOf(settings(context).current().imageMaskPolarity) }
            .getOrDefault(MaskPolarity.TRANSPARENT_HOLE)

    /**
     * Resolves the provider a template asks for. The posting path only ever sees
     * the interface — no vision vendor is named anywhere downstream of here.
     */
    suspend fun altTextProvider(context: Context, template: Template): AltTextProvider =
        when (template.altTextMode) {
            AltTextMode.NONE -> NoAltTextProvider
            AltTextMode.STATIC -> StaticAltTextProvider(template.staticAltText.orEmpty())
            AltTextMode.VISION -> {
                val s = settings(context).current()
                VisionAltTextProvider(
                    client = http(),
                    endpoint = s.visionEndpoint,
                    model = s.visionModel,
                    apiKey = s.visionApiKey,
                    prompt = s.visionPrompt,
                )
            }
        }
}
