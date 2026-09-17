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

- **Scraping the permalink for caption + image URL.** The permalink returns a
  contentless JS shell to unauthenticated clients — no `og:image`, no `og:title`,
  no caption, for public and private accounts alike. Only the web app's own
  static assets appear in the markup. Getting at the media needs a TLS-level
  impersonating client, which breaks silently in production with no signal to
  the user, and violates Instagram's terms.
- **Official oEmbed.** *Re-tested 2026-09-17, and the original objection no
  longer holds:* Meta made public-post oEmbed tokenless in June 2026, so no app,
  `oembed_read` permission or App Review is needed, and it cleanly distinguishes
  a public post (`200`) from anything else (`OAuthException` code 24). It is
  still no use here, for a different reason — the response carries only
  `version`, `provider_name`, `provider_url`, `type`, `width` and `html`. Meta
  stripped `thumbnail_url`, `author_name` and `title`, and the `html` is a
  placeholder blockquote whose entire visible text is "View this post on
  Instagram". There is no image and no caption in it.
- **Private mobile API.** Violates Instagram's terms, risks account termination,
  breaks on their release cadence.
- **Accessibility service reading the screen.** Invasive, Play Store policy
  hostile, fragile against UI changes.
- **Capturing the screenshot automatically.** Structurally impossible in the
  share flow: tapping Share brings our activity to the foreground, so Instagram
  is already gone by the time any of our code runs, and a `MediaProjection`
  capture would photograph our own UI. Capturing earlier needs a separate
  trigger while Instagram is still on screen — a Quick Settings tile or a
  bubble — which costs a per-session consent dialog, a `mediaProjection`
  foreground service and a persistent recording indicator, to save no taps at
  all over pressing the screenshot combination.

The image therefore comes from a **user-taken screenshot**, shared as `image/*`.
Android 13+ offers crop directly from the screenshot notification, so this is two
taps. The permalink can optionally be shared alongside for attribution.

### Trimming the screenshot

A screenshot is the whole screen, and the post wants only the picture. Rather
than sending the user out to a photo editor, `ScreenshotCropper` finds the
picture itself: a feed screenshot is a stack of horizontal bands, and every band
that is not the picture is mostly flat app background with sparse text on it, so
the picture is the tallest run of rows that are largely *not* the background
colour.

It is a heuristic, so it is never trusted blindly. It reports a confidence and
returns nothing rather than guessing. Compose mode shows the proposal with
draggable corners for approval; the two modes that do not stop for input apply it
only above a confidence threshold, and only when `Settings.autoCrop` is on. The
untouched screenshot is kept in `Item.originalMediaPath` either way, so the
editor can always put it back, and re-cropping works from the original rather
than cutting into a previous crop.

Detection runs on a downscaled copy — the band layout does not depend on
resolution — and the result is scaled back up, so the crop itself is lossless.
The crop rectangle is held as fractions of the image, never as view pixels: the
displayed bitmap is whatever size the image loader decided on, and measuring
against that silently shifts the crop.

### Pairing the two halves

Attribution needs two shares — the permalink from Instagram, then the
screenshot — and left alone that produces two unrelated drafts to reconcile by
hand. Instead, a share that supplies exactly the half a recent draft is missing
joins that draft rather than starting its own: a screenshot with no link of its
own adopts the newest link-only draft, and a link with no image adopts the newest
image-only draft. The window is `ItemRepository.PAIRING_WINDOW_MS`.

Only a share carrying precisely one half ever pairs. One carrying both is
self-contained and stands alone, which keeps the rule unambiguous. Pairing a link
into an existing draft re-renders its body so `{link}` resolves — but only when
the body still matches the template's own link-less output, so hand-edited text
is never overwritten.

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
