package app.fediferry.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A connected Mastodon account. The access token is *not* stored here — it lives
 * in [app.fediferry.data.TokenStore], backed by EncryptedSharedPreferences.
 */
@Entity(tableName = "accounts")
data class Account(
    /** "$instance/$acct", stable across re-auth of the same handle. */
    @PrimaryKey val id: String,
    /** Host only, no scheme: "mastodon.social". */
    val instance: String,
    val acct: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isDefault: Boolean = false,
)

/** Per-instance OAuth client credentials, obtained from `POST /api/v1/apps`. */
@Entity(tableName = "instance_apps")
data class InstanceApp(
    @PrimaryKey val instance: String,
    val clientId: String,
    val clientSecret: String,
)
