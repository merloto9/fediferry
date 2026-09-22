# Project: Mastodon meme sharer (Android)

An Android app that receives shared images via the system share sheet and posts
them to Mastodon. Built for sharing memes out of Instagram with minimal friction.

Read `SPEC.md` for the full design rationale before starting new work.

## Hard constraints — do not design around these

- **Instagram provides no API for this.** The Basic Display API was shut down in
  December 2024. The Graph API only covers a Business/Creator account's own media
  and cannot read a home feed. Do not add Instagram credentials, do not scrape,
  do not attempt to build an Instagram feed view.
- **Instagram's share sheet sends a permalink only** (`text/plain`), never the
  image and never the caption. The image always comes from a user screenshot
  shared as `image/*`.
- Original captions and hashtags are **not** recoverable. All post text comes
  from local templates the user configures.

## Architecture

Single pipeline, three entry points. Every share — regardless of mode — is
persisted to the local store first, then acted on. Modes differ only in where
the pipeline pauses.

```
share sheet → ShareReceiver → ingest+persist → [mode] → PostWorker → Mastodon
```

Modes are exposed as three separate Sharing Shortcuts targets:

| Target         | Behaviour                                        |
|----------------|--------------------------------------------------|
| Post now       | Applies template, enqueues immediately, 5s undo  |
| Compose        | Opens editor prefilled, user sends               |
| Save for later | Persists as draft, stays in inbox                |

## Stack

Kotlin, Jetpack Compose, Room, WorkManager, OkHttp/Retrofit, AndroidX Security
for token storage. Min SDK 26, target current.

## Build order

1. Data layer + inbox UI (workflow 3)
2. Compose editor (workflow 2) — a screen on top of 1
3. Post-now shortcut (workflow 1) — 2 with the pause removed

Do not build 1 before 3. The store is the dependency, not the other way round.

## Conventions

- Media lives in app-private storage, never MediaStore. Hash on ingest to dedupe.
- `AltTextProvider` is an interface. Static and LLM implementations both satisfy it.
  Never hardcode a vision vendor into the posting path.
- Anything that can fail remotely degrades to empty rather than aborting the post.
- Never log access tokens or post bodies.
- Each source is a module under `module/<name>/` (resolver or client, fields,
  default recipes). Source-specific code goes there, never in the pipeline.
- `{tags}` is resolved at send time only. Never write hashtags into a draft body.
