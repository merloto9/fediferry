package app.fediferry.mastodon

/**
 * A failure talking to an instance. [retryable] distinguishes "try again later"
 * (5xx, 429, network) from "this will never work" (401, 422), which is what
 * PostWorker keys its retry decision off.
 */
class MastodonException(
    message: String,
    val code: Int = 0,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
