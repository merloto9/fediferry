package app.fediferry.di

import android.annotation.SuppressLint
import android.content.Context
import app.fediferry.alt.AltTextProvider
import app.fediferry.alt.NoAltTextProvider
import app.fediferry.alt.StaticAltTextProvider
import app.fediferry.alt.VisionAltTextProvider
import app.fediferry.data.ItemRepository
import app.fediferry.data.MediaVault
import app.fediferry.data.SettingsStore
import app.fediferry.data.TokenStore
import app.fediferry.data.db.AppDatabase
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
