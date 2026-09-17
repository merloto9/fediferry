# Mastodon meme sharer — specification

## Problem

Sharing a meme from Instagram to Mastodon currently means: screenshot, open a
Mastodon client, start a post, attach the image, write alt text, write a caption,
post. This app collapses that into a share-sheet tap, with three levels of control
depending on how much you care about the particular post.

## What Instagram actually gives us

Tapping share on an Instagram post fires `ACTION_SEND` with `text/plain`
containing a permalink (`instagram.com/p/…`). That is the entire payload. No
image, no caption, no author handle.

Approaches that were considered and rejected:

- **Scraping the permalink for caption + image URL.** Instagram serves a login
  wall to unauthenticated requests unpredictably. Building the core feature on
  this means posts silently degrade in production with no signal to the user.
- **Official oEmbed.** Requires a Facebook app with `oembed_read`, business
  verification, and App Review. Heavy, and revocable at Meta's discretion.
- **Private mobile API.** Violates Instagram's terms, risks account termination,
  breaks on their release cadence.
- **Accessibility service reading the screen.** Invasive, Play Store policy
  hostile, fragile against UI changes.

The image therefore comes from a **user-taken screenshot**, shared as `image/*`.
Android 13+ offers crop directly from the screenshot notification, so this is two
taps. The permalink can optionally be shared alongside for attribution.

## Workflows

### 1. Post now
No decisions after the share. Image is ingested, the selected template produces
the post text, alt text is resolved (static string or vision-model call), and the
post is enqueued. A notification with an **Undo** action holds the worker for five
seconds.

Fully automatable because nothing in this path depends on Instagram. Latency from
an alt-text API call is invisible since the worker is async.

### 2. Compose
Opens an editor prefilled from the template with the image preview. User can edit
text, regenerate or rewrite alt text, set visibility and content warning, then
send. Target: under ten seconds.

### 3. Save for later
Persists as a draft and stops. User opens the app later, browses the inbox grid,
edits and posts items individually or schedules them spaced out. This is the
workflow that matches actual meme-consumption behaviour — save six while
scrolling, post them over the following days rather than flooding a timeline.

## Data model

```kotlin
@Entity
data class Item(
    @PrimaryKey val id: String,
    val mediaPath: String?,      // app-private file
    val mediaHash: String?,      // dedupe
    val mimeType: String?,
    val sourceUrl: String?,      // Instagram permalink, if shared
    val bodyText: String,        // rendered from template
    val altText: String?,
    val contentWarning: String?,
    val visibility: Visibility,  // PUBLIC, UNLISTED, PRIVATE, DIRECT
    val templateId: String,
    val status: Status,          // DRAFT, QUEUED, POSTING, POSTED, FAILED
    val failureReason: String?,
    val createdAt: Instant,
    val postedAt: Instant?,
    val statusUrl: String?,      // resulting Mastodon post
)
```

Failed auto-posts land in the inbox as `FAILED` and can be retried with one tap.
This is why workflow 1 still writes to the store: it makes the zero-touch path
recoverable instead of silently lossy.

## Templates

A template is a named bundle of: body text with placeholders, default visibility,
default content warning, alt-text mode, and tag set.

Placeholders: `{link}`, `{tags}`, `{date}`. A `{caption}` placeholder may exist
behind a best-effort resolver, but it must degrade to empty string on any failure
and no downstream code may assume it resolved.

Example:

```
{tags}

via {link}
```

## Alt text

```kotlin
interface AltTextProvider {
    suspend fun describe(image: ByteArray, mimeType: String): Result<String>
}
```

Implementations: `StaticAltTextProvider` (returns a configured constant),
`VisionAltTextProvider` (calls a configured vision endpoint), `NoAltTextProvider`.
Selected per template. A failure never blocks the post — it posts without alt text
and flags the item so the user can fill it in afterwards.

## Mastodon integration

- **Registration**: `POST /api/v1/apps` on first connect to the user's instance,
  storing `client_id` / `client_secret` per instance.
- **Auth**: OAuth 2 authorization code via Custom Tab, redirect to an app scheme.
  Scopes: `write:statuses write:media`. Token in EncryptedSharedPreferences.
- **Media**: `POST /api/v2/media` with the image and `description`. May return
  `202`; poll `GET /api/v1/media/:id` until `200` before creating the status.
- **Status**: `POST /api/v1/statuses` with `media_ids`, `status`, `visibility`,
  `spoiler_text`, and an `Idempotency-Key` header derived from the item id so
  retries cannot double-post.

Multi-account support: the account is a field on the item, chosen per template.

## Sharing Shortcuts

Three dynamic shortcuts published via `ShortcutManagerCompat`, each with a
`ShortcutInfoCompat` carrying a category matched by the intent filter. They appear
in the direct-share row of Instagram's share sheet, so the workflow is selected at
share time rather than in settings.

Declare intent filters for `image/*` and `text/plain` on both `ACTION_SEND` and
`ACTION_SEND_MULTIPLE`.

## Screens

- **Inbox** — grid of drafts and failures, multi-select, bulk post, reorder.
- **Editor** — image preview, body text, alt text with regenerate, visibility
  chip, CW field, template picker.
- **Settings** — accounts, templates, alt-text provider config, default undo delay.

## Open questions for implementation

- Scheduled posting: use Mastodon's `scheduled_at` or local WorkManager delays?
  Server-side scheduling survives app uninstall but caps at a fixed horizon.
- Whether to keep posted items in the store as history or purge after N days.
- Video support — Mastodon accepts it, screenshots don't produce it, but a shared
  screen recording would work.
