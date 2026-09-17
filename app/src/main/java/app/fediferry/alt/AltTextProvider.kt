package app.fediferry.alt

/**
 * Resolves alt text for an image.
 *
 * Implementations must never throw: a failure is a [Result.failure], and the
 * posting path treats that as "post without a description" rather than as a
 * reason to abort. Nothing in the posting path may name a concrete vendor.
 */
interface AltTextProvider {
    suspend fun describe(image: ByteArray, mimeType: String): Result<String>
}

/** Emits no description at all. */
object NoAltTextProvider : AltTextProvider {
    override suspend fun describe(image: ByteArray, mimeType: String): Result<String> =
        Result.failure(UnsupportedOperationException("alt text disabled"))
}

/** Emits a fixed string configured on the template. */
class StaticAltTextProvider(private val text: String) : AltTextProvider {
    override suspend fun describe(image: ByteArray, mimeType: String): Result<String> =
        if (text.isBlank()) Result.failure(IllegalStateException("no static alt text configured"))
        else Result.success(text)
}
