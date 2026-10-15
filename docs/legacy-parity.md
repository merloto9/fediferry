# Parity with the legacy FediFerry app

What the legacy app ([merloto9/fediferry](https://github.com/merloto9/fediferry),
`main` at `bd2fdb8`) could do that this app, in phone-only mode, cannot do any more.
Legacy users land in phone-only mode after the upgrade, so that is the mode compared.

Deliberate changes don't count as gaps here: templates became channel post settings,
the inbox became Review, the library is gone, published posts are purged, and there are
two backends. New features of this app aren't listed either.

Paths starting with `legacy:` are under the legacy app's `app/src/main/java/app/fediferry/`.
All other paths are under this repo's `app/src/main/java/app/fediferry/`.

## Gaps

### Essential

**1. ~~You can no longer draw clean-up areas, so there is no clean-up for a single picture.~~**
Fixed: *Clean up* in the editor opens a drawing dialog with a per-picture instruction and
model picker (`drafts/CleanupDialog.kt`), on the phone and with a server.
- **Legacy:** the editor opened a Clean up screen (`legacy:ui/cleanup/CleanupScreen.kt`,
  route in `legacy:ui/Nav.kt:162-174`). You drew areas, picked a treatment for each (fill,
  crop away, blur, pixelate, Erase with AI with its own instruction and model), and could
  tick *Remember* to save them as a profile.
- **Now:** there is no clean-up screen and no route to one. The editor's *Clean up* button
  only shows when profiles already exist, and it can only apply one of them
  (`drafts/DraftEditorScreen.kt:405-413`). Settings → Clean up can list, make default and
  delete profiles, but cannot create one (`ui/settings/SettingsViewModel.kt:309-321`).
- **Result:** someone with no profiles (every fresh install, and every legacy user who never
  ticked *Remember*) cannot clean a picture at all. Nobody can erase a one-off overlay or
  use Erase with AI on a single picture.
- The engine is still there (`CleanupPipeline`, `BitmapCleaner`, `PhoneBackend.derive`), so
  this is missing UI. `settings_cleanup_no_profiles` still tells people to "draw on Clean up
  in the editor", which no longer exists.

### Minor

**2. ~~The alt text has no per-picture AI model choice.~~** Fixed: *Model: … ▾* under the
alt text, per post; Settings sets the default.
- **Legacy:** "Model: … ▾" under the alt text (`legacy:ui/editor/EditorScreen.kt:277-283`).
- **Now:** `suggestAlt` always uses the default model. `ui/ModelPicker.kt` is never called.

**3. ~~The crop dialog no longer offers the detected crop.~~** Fixed: the dialog starts on
the suggestion for an untouched picture and offers *Suggested*. Shares to a server are now
trimmed and cleaned on intake too.
- **Legacy:** a Compose share opened the trim step set to the detected crop, with a
  *Suggested* button (`legacy:ui/crop/CropViewModel.kt:94-120`).
- **Now:** only a confident auto-crop (≥ 0.72) is applied, silently, at share time. The crop
  dialog always starts on the whole image (`drafts/DraftEditorScreen.kt:452-453`), so a less
  certain suggestion is lost.

**4. There is no "Posted" or "Scheduled" notification.**
- **Legacy:** "Posted" with a link to the post, or "Scheduled for …"
  (`legacy:work/PostWorker.kt`).
- **Now:** `phone/PhonePublishWorker.kt` only notifies on a final failure. After *Post now*,
  nothing confirms the post went out.

**5. Mastodon's queue fails silently, with no reconnect prompt.**
- **Legacy:** the Queue offered *reconnect* on 401/403 or a missing `read:statuses` scope
  (`legacy:ui/queue/QueueViewModel.kt:101-107`).
- **Now:** `PhoneBackend.mastodonQueue()` swallows the error, so Publishing shows an empty
  queue while Mastodon still holds posts, and slot planning ignores them.

**6. "Make a draft, keep this" is gone.**
- **Legacy:** a post Mastodon held could become a draft while staying scheduled
  (`queue_draft_and_keep`, the string is still in `strings_queue.xml`).
- **Now:** taking a post back always cancels it at Mastodon. Cancelling it outright takes two
  steps (back to review, then delete), because delete isn't offered on a Mastodon-held post
  (`review/ReviewPostScreen.kt`, `DELETABLE`).

**7. There is no `{tags}` hint in the editor.**
- **Legacy:** `TagsHint` showed what `{tags}` would become, and warned when hashtags were
  picked but the text had no `{tags}` (`legacy:ui/editor/EditorScreen.kt:327-340`).
- **Now:** nothing warns about picked hashtags that will never be posted.

**8. "Clean older links" no longer finds anything.**
- The prompt and the Settings *Check* button still read the old `items` table
  (`data/ItemRepository.kt:387`). After the upgrade, every unposted item has moved into
  the phone project's tables, so the feature always comes back empty.

## What the upgrade does not carry over

`phone/LegacyInbox.kt` moves the legacy inbox into Review when the new app first starts.
These parts are lost or changed on the way:

- **Posts that were queued or scheduled when the upgrade happened** go to Review, and their
  pending work is cancelled. Legacy kept the scheduled time only in the WorkManager data,
  never on the item, so the time can't be recovered. The user has to plan them again.
  `failureReason` is dropped too.
- **Templates** that were neither the default nor tied to an account don't become channels.
  They stay in the `templates` table, where nothing reads them.
- **Alt-text mode:** a migrated post's alt-text mode now comes from its channel, not from the
  template it was written with (`templateId` is ignored).
- **Fixed in this pass:** each migrated post now keeps the account it was set to.
  `adopt` used to put every legacy post on the default channel, so on a phone with more than
  one account posts could have gone to the wrong one.

Cleanup profiles, AI models, hashtags and their usage counts, placeholders, sources and
settings carry over unchanged. They live in the same Room tables and DataStore, and the
11 → 12 auto-migration only adds tables.

## Present and working in phone mode

- The three share targets (Post now with undo, Compose, Save for later), with the same
  manifest and shortcuts.
- Instagram permalink and screenshot pairing (10-minute window), and duplicate detection by
  hash.
- Link cleaning and media fetching for 9GAG, Pinterest and Reddit.
- Auto-crop and the default clean-up profile applied on share. Manual crop and undoing an edit.
- Alt text: edit, generate, and static or vision mode at send time.
- Content warning with presets, visibility, the hashtag panel with source hashtags and usage
  order, placeholder help, and the warning for links that may identify you.
- Multiple Mastodon accounts, as channels: sign-in, default, removal.
- Scheduling: posts at least 5.5 minutes ahead go to Mastodon, sooner ones are sent from the
  phone. Mastodon-held posts can be moved or taken back.
- Publishing: the queue, failed posts with *retry now*, and Published read live from Mastodon.
- Stacks became review folders, which can be moved, renamed and deleted. Multi-select delete
  with confirmation.
- Sources space, which turns YouTube channels into drafts.
- Settings: Hashtags, Placeholders, Sharing, Clean up (list only, see gap 1), Alt-text and
  image AI models with *Test*, Appearance, Language, and Diagnostics with log export.
- German translation complete. Retries with backoff and an idempotency key, and recovery of
  posts interrupted mid-send.
