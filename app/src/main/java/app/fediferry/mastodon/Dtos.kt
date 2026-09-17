package app.fediferry.mastodon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppRegistration(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    val scope: String = "",
)

@Serializable
data class CredentialAccount(
    val id: String,
    val acct: String,
    @SerialName("display_name") val displayName: String = "",
    val avatar: String? = null,
)

@Serializable
data class MediaAttachment(
    val id: String,
    val url: String? = null,
)

@Serializable
data class PostedStatus(
    val id: String,
    val url: String? = null,
)
