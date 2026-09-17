package app.fediferry.alt

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Calls a user-configured, OpenAI-compatible chat-completions endpoint.
 *
 * The vendor is a setting, not a compile-time choice — any server speaking that
 * shape (a hosted API, a local Ollama, an LLM gateway) works. The posting path
 * only ever sees [AltTextProvider].
 */
class VisionAltTextProvider(
    private val client: OkHttpClient,
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val prompt: String,
) : AltTextProvider {

    override suspend fun describe(image: ByteArray, mimeType: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(endpoint.isNotBlank()) { "no vision endpoint configured" }

                val dataUri = "data:${mimeType.ifBlank { "image/jpeg" }};base64," +
                    Base64.encodeToString(image, Base64.NO_WRAP)

                val payload = buildJsonObject {
                    put("model", model)
                    put("max_tokens", 300)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", prompt)
                                })
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", dataUri) })
                                })
                            })
                        })
                    })
                }

                val request = Request.Builder()
                    .url(endpoint)
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .post(payload.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { response ->
                    // The body may carry the image back in an error echo; never logged.
                    check(response.isSuccessful) { "vision endpoint returned ${response.code}" }
                    val body = response.body.string()
                    val text = json.parseToJsonElement(body)
                        .jsonObject["choices"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("message")
                        ?.jsonObject?.get("content")?.jsonPrimitive?.content
                        ?: error("vision endpoint returned no content")
                    text.trim().ifBlank { error("vision endpoint returned empty description") }
                }
            }
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
    }
}
