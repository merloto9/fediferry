# Changelog

All notable changes to FediFerry are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

Each release's APK is attached to its
[GitHub release](https://github.com/merloto9/fediferry/releases) and is what
Obtainium installs.

## [0.10.0] — 2026-09-22

### Added

- **Sharing from Reddit.** Share a post from the Reddit app and what it carries
  is fetched instead of waiting for a screenshot: the original picture, a
  gallery's first picture, a GIF as its much smaller video, or a video with its
  sound. The post's title fills `{caption}`. The app's `/s/` share links, full
  post URLs and `redd.it` links all work, and no Reddit account is needed.
- Videos without a download that carries their sound are declined rather than
  posted silent, and text posts fall back to the screenshot path as before.

## [0.9.0] — 2026-09-22

### Changed

- **Waiting says what it is waiting for.** Every fetch from a server now shows
  a loading screen naming what is being fetched and from where: a channel's
  community posts, a picture being downloaded from YouTube, the picture behind
  a shared Pinterest or 9GAG link, a channel being checked before it is added,
  the Mastodon sign-in, and an AI clean-up. Pictures in a channel hold their
  place while they load, and a fetch that runs long says it is still working.
- Sharing a link used to wait up to fifteen seconds with nothing on screen
  while its picture was fetched. It now shows a card over the app the share
  came from. Screenshot shares stay invisible.
- Adding a channel closed the dialog and then showed nothing until YouTube
  answered. It now shows that the channel is being checked.

## [0.8.0] — 2026-09-22

### Added

- **Sharing from Pinterest.** Share a pin, by `pin.it` link or full URL, and the
  picture is fetched instead of waiting for a screenshot — at the size Pinterest
  keeps, not the 736-pixel preview. The pin's title fills `{caption}`, without
  the keyword tail Pinterest appends for search engines. No Pinterest account is
  needed or asked for.
- Video pins are declined rather than resolved. Their preview is a cover frame,
  and posting a still of a video without saying so is the failure the 9GAG
  resolver was written to avoid; the share falls back to the screenshot path.
- **An opt-in log, and a button to send it.** *Settings → Diagnostics* records
  what the app does — a share arriving, a resolver declining, a post failing and
  why — and hands the file to any messenger through the share sheet. Off by
  default, one rotation kept, a quarter of a megabyte each. Events only: no
  access tokens, no post text, and links reduced to their host.

## [0.7.0] — 2026-09-22

### Added

- **Content warning presets, German first and English second.** 33 warnings in
  seven groups — violence and death, mental health, sexuality and body, hate and
  discrimination, substances and food, stimuli and phobias, animals, tone and
  context — reachable from a sheet next to the content-warning field in the
  editor. Picking one fills the field, which stays editable, so a preset can be
  reworded or replaced by something hand-written. Picking a second appends it,
  because posts warn for violence and blood at once far more often than for
  either alone; picking an applied one takes it back out again.
- **A template's default content warning is finally reachable.** The field
  existed in the data model and was copied onto every item the template made,
  but no screen ever showed it. The template card in Settings now has it, with
  the same preset sheet.

## [0.6.0] — 2026-09-18

### Added

- **A Sources space beside the Inbox.** Follow YouTube channels, browse their
  community posts at the size they deserve, and tap a picture to land in the
  same editor a share or a screenshot reaches. The post's own text feeds
  `{caption}` and its permalink feeds `{link}`.
- Only the subscription is stored. Posts are fetched live and never persisted —
  they are someone else's content, and only the picture actually chosen becomes
  an item.

### Fixed

- **Typing was close to impossible.** Every text field was bound to a value that
  travelled through a StateFlow, and for the settings fields through DataStore
  and back off disk. Each keystroke was followed a frame later by the field
  being handed an older string, so the cursor jumped and characters arrived out
  of order. Fields now own their text while focused and follow outside changes
  only when they do not.
- **Settings could be lost on the way out.** Saving ran on the screen's own
  coroutine scope, which is cancelled the moment the screen is left — so typing
  a value and going straight back discarded it. Saving now outlives the screen
  that asked for it.
- **A failed call to a configured endpoint said only its status code.** "vision
  endpoint returned 404" cannot distinguish a mistyped URL from a model the
  service does not have, and those need opposite fixes — while the server had
  said which all along. Both the alt-text and image endpoints now quote the
  server's own explanation and add a hint for the common codes. Only the error
  message is quoted, never the whole body, since a rejected request can echo the
  image back.

### Notes

- YouTube publishes no API for community posts: the Data API covers videos,
  channels, playlists and comments and stops there. The channel page's own
  embedded JSON is the only route, which makes this the most fragile thing in
  the app. It is written to fail loudly rather than quietly — "the page changed"
  and "this channel has no posts" are reported differently.

## [0.5.1] — 2026-09-18

### Added

- A prompt field on the Clean up screen, so the model can be told what to do for
  *this* image rather than only through the default in Settings. It appears once
  an area is actually going to the model.

### Fixed

- **"Erase with AI" looked like a dead button.** When no model was configured —
  or the call failed — the marked areas were quietly filled from their
  surroundings and nothing said so. The pipeline had reported the fallback all
  along; the repository discarded it. The outcome now reaches the user, and
  choosing the treatment says up front when no model is set up.

## [0.5.0] — 2026-09-17

### Added

- **Erase with AI**, a fifth cleanup treatment for overlays sitting on detail,
  where filling from the surroundings only smears. `ImageEditProvider` is an
  interface beside `AltTextProvider`, with the endpoint, model, key and
  instruction configured in Settings — no vendor is named in the code.
- Because inpainting has no standard request shape, the wire format is a setting
  rather than a guess: multipart for the OpenAI images/edits family, JSON with
  base64 for the Stable Diffusion derived servers. Mask polarity is configurable
  the same way.
- All AI regions go in one request carrying the whole picture and one mask, so
  the model sees the context around every hole and it costs one call rather than
  one per area.
- Failure degrades rather than blocking: an unconfigured, unreachable or unhappy
  model means those regions are filled locally, exactly as they would have been
  without a model. A reply whose dimensions differ from the original is refused,
  since every other rule is expressed in fractions of the picture.

### Fixed

- **Sharing something again reopened the old item instead of making a new one.**
  The duplicate check matched any item that was not FAILED, so a repeated share
  — a 9GAG repost, or the same post shared twice — folded into whatever already
  held those bytes, including posts that had already gone out. It now folds only
  into an unsent draft. Re-sharing a meme you already posted starts a fresh
  draft, as it should.
- The trim step was offered for images fetched from a link. Those are already
  exactly the picture, and the screenshot detector proposed a crop through the
  middle of the meme. Trimming is now offered for screenshots only.

## [0.4.0] — 2026-09-17

### Added

- **Clean up**: draw over anything that should go and choose what happens
  there — fill in, crop away, blur or pixelate. The treatment belongs to each
  area, not to the profile, because the kind of artefact varies as much as its
  position.
- **Cleanup profiles.** Tick *Remember* and the areas are saved against a named
  profile, so the next screenshot from the same place needs no drawing. Profiles
  are data, editable in Settings: when a layout moves, adjust the rule instead of
  waiting for an update. Marking a profile default applies it on its own to
  shares that do not stop for input; leaving none default means nothing ever
  happens unasked.
- **In-app placeholder reference** behind the info icon on the post text field,
  and from the template editor. Lists every placeholder, says when each is
  empty, and explains that a line whose placeholders all came back empty is
  dropped whole.

### Fixed

- **Crop handles could not be dragged**, only nudged a step at a time. The
  gesture detector was keyed on the crop rectangle, so changing the crop
  restarted the detector and cancelled the drag in progress. Handles now follow
  the finger.
- A resolved 9GAG animation was uploaded with a `.jpg` filename whatever it
  actually was. Instances key off the filename as well as the content type.
- Trim and Clean up are hidden for video, since both decode a still.

### Changed

- Database schema version 3, adding cleanup profiles and their rules, with a
  migration and tests covering 1→2, 2→3 and the whole path 1→3.
- *Undo trim* is now *Undo edits*: it reverts trimming and cleanup alike, back
  to exactly what was shared.

## [0.3.0] — 2026-09-17

### Added

- **Shared 9GAG links resolve to the image.** Share a `9gag.com/gag/…` link on
  its own and the picture is fetched for you — no screenshot, nothing to trim,
  and at the source's own quality instead of a re-encoded screen grab.
  Animated posts come across as the video rather than a still frame.
- A `LinkResolver` seam beside the existing `AltTextProvider` one, so other
  services can be added as single classes. Resolution runs *after* the item is
  persisted and every failure is a no-op that leaves the item with its link, so
  an unsupported host, a service declining or a failed download all just mean
  the screenshot flow works as before.
- A resolved post's title feeds the `{caption}` placeholder. It fills only when
  the body is still exactly the template's own output, so hand-edited text is
  never overwritten.
- Setting: **Fetch images from shared links**.

### Fixed

- A resolved animation attached correctly but previewed as a blank box; Coil's
  video decoder is now registered.
- `MediaFetcher` read downloads with `InputStream.readNBytes`, which is API 33
  against a minSdk of 26 and would have crashed on older devices.

### Notes

- The resolver uses 9GAG's post endpoint rather than the page's `og:image`.
  `og:image` is the more obviously public surface and was the original plan, but
  for an animated post it returns a still frame and the page carries no
  `og:video` — so nothing would indicate the animation had been lost. Silent
  degradation is the one failure mode this project refuses.
- Instagram publishes nothing fetchable, for public and private accounts alike.
  Its shares are unaffected and still go through the screenshot path.

## [0.2.0] — 2026-09-17

### Added

- **Screenshots are trimmed in the app**, so cropping no longer means a trip
  through a photo editor. A feed screenshot is a stack of horizontal bands, and
  the picture is the tallest run of rows that are largely not the background
  colour.
- The detection is a heuristic and is never trusted blindly: Compose mode shows
  the proposal with draggable corners, and the modes that do not stop for input
  apply it only above a confidence threshold.
- The untouched screenshot is kept, so **Undo trim** restores it and re-trimming
  works from the original rather than cutting into a previous crop.
- Setting: **Trim screenshots automatically**.

### Changed

- Database schema version 2, adding `Item.originalMediaPath`, with a migration
  that preserves existing drafts.

## [0.1.2] — 2026-09-17

### Added

- **An Instagram permalink pairs with its screenshot.** A share supplying
  exactly the half a recent draft is missing joins that draft instead of
  starting its own, in either order, so one post carries both the image and its
  attribution instead of leaving two drafts to reconcile by hand.
- Ingest now reports what it did: a permalink on its own previously produced a
  silent "Saved to the inbox" with no hint that the post had no image.

### Changed

- `SPEC.md` corrected on Instagram link resolution. Meta made public-post oEmbed
  tokenless in June 2026, so the App Review objection no longer holds — but the
  response omits `thumbnail_url`, `author_name` and `title`, so it still carries
  neither image nor caption. Also records why automatic screenshot capture
  cannot work: the share flow puts our own activity in the foreground, so
  Instagram is gone before any of our code runs.

## [0.1.1] — 2026-09-17

### Fixed

- **Sharing failed on every device in 0.1.0.** The template renderer built its
  placeholder pattern with an unmatched `}`. Android implements
  `java.util.regex` over ICU, which rejects that, while the OpenJDK engine the
  unit tests run on accepts it — so it passed on the host and threw on a phone.
  Because the pattern is a field initialiser, the throw took the whole class
  initialiser with it and no share could be ingested at all.
- The share receiver logged only `error.message`, which for a classloader error
  is the mangled class name and nothing else. It now logs the throwable and
  reports the root cause's type.

### Added

- Licensed under **GPL-3.0-or-later**, with the full text, per-file notices and
  a dependency audit in the README. GPLv2 is not available to this project:
  of the 192 modules inside the APK, 191 are Apache-2.0, which is incompatible
  with GPLv2 but compatible with GPLv3.
- An instrumented test for the template renderer. A host-side unit test
  structurally cannot catch the regex bug above.

## [0.1.0] — 2026-09-17

Initial release. **Superseded — this build cannot ingest any share**; see 0.1.1.

### Added

- Share-sheet entry points for Post now, Compose and Save for later, published
  as Sharing Shortcuts.
- Room-backed inbox, Compose editor and settings.
- Mastodon OAuth, media upload with readiness polling, and status creation with
  an idempotency key derived from the item id.
- Templates with `{link}`, `{tags}` and `{date}` placeholders.
- `AltTextProvider` with none, static and vision implementations.
- GitHub Actions release pipeline producing an Obtainium-installable APK.

[0.8.0]: https://github.com/merloto9/fediferry/releases/tag/v0.8.0
[0.7.0]: https://github.com/merloto9/fediferry/releases/tag/v0.7.0
[0.6.0]: https://github.com/merloto9/fediferry/releases/tag/v0.6.0
[0.5.1]: https://github.com/merloto9/fediferry/releases/tag/v0.5.1
[0.5.0]: https://github.com/merloto9/fediferry/releases/tag/v0.5.0
[0.4.0]: https://github.com/merloto9/fediferry/releases/tag/v0.4.0
[0.3.0]: https://github.com/merloto9/fediferry/releases/tag/v0.3.0
[0.2.0]: https://github.com/merloto9/fediferry/releases/tag/v0.2.0
[0.1.2]: https://github.com/merloto9/fediferry/releases/tag/v0.1.2
[0.1.1]: https://github.com/merloto9/fediferry/releases/tag/v0.1.1
[0.1.0]: https://github.com/merloto9/fediferry/releases/tag/v0.1.0
