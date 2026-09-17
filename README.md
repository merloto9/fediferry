# FediFerry

Share a meme screenshot into Mastodon with one tap. See [SPEC.md](SPEC.md) for
the design rationale and [CLAUDE.md](CLAUDE.md) for the constraints.

## Install

Add this repository in [Obtainium](https://github.com/ImranR98/Obtainium):

```
https://github.com/merloto9/fediferry
```

Obtainium reads the latest GitHub release, installs the attached `.apk`, and
offers each later release as an in-place update.

> Releases are currently signed with the repository's **pinned debug key**
> (`keystore/debug.keystore`). It is committed deliberately: AGP generates a
> fresh debug key per machine, so without pinning it every CI run would produce
> a differently-signed APK and Obtainium could not update in place. The key is
> not a secret and offers no integrity guarantee — see *Switching to a real
> release key* below.

## First run

1. **Settings → Accounts**: enter your instance host (`mastodon.social`) and
   tap Connect. A Custom Tab opens the instance's OAuth page; approving it
   returns to the app. The app registers itself per instance on first connect.
2. **Settings → Templates**: edit the seeded `Meme` template. Placeholders are
   `{link}`, `{tags}` and `{date}`. A placeholder with nothing to fill it
   resolves to an empty string and its whole line is dropped, so
   `{tags}\n\nvia {link}` does not post a dangling "via".
3. Screenshot something, share it, and pick one of the three targets.

| Target         | What it does                                       |
|----------------|----------------------------------------------------|
| Post now       | Applies the template and sends, after an undo delay |
| Compose        | Opens the editor prefilled                          |
| Save for later | Keeps it in the inbox as a draft                    |

Instagram's share sheet only ever sends a permalink (`text/plain`) — never the
image and never the caption. Share the permalink alongside a screenshot if you
want `{link}` to resolve.

### Alt text

Each template picks a mode: `NONE`, `STATIC` (a fixed string on the template) or
`VISION` (a request to the endpoint configured in Settings). `VISION` speaks the
OpenAI chat-completions shape, so any compatible server works — hosted, a
gateway, or a local one. A failure never blocks a post: the item goes out
without a description and is flagged so you can fill it in afterwards.

## Development

### Toolchain

- JDK 21 — `brew install openjdk@21`
- Android SDK command-line tools — `brew install --cask android-commandlinetools`
- SDK packages — `sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"`

Point the build at the SDK, either by exporting `ANDROID_HOME` or by writing
`local.properties` (gitignored):

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

Everything else comes from the Gradle wrapper. With Homebrew's `openjdk@21`:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

### Build

```sh
./gradlew assembleDebug            # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleRelease          # minified, signed with the pinned key
```

The debug build uses application ID `app.fediferry.debug` and OAuth scheme
`fediferry-debug`, so it installs alongside a release build.

### Layout

```
data/        Room entities, DAOs, the media vault, the token store
share/       Share receiver, payload parsing, Sharing Shortcuts
template/    Placeholder rendering
alt/         AltTextProvider and its implementations
mastodon/    OAuth, media upload with readiness polling, status creation
work/        PostWorker, scheduling, the undo notification
ui/          Inbox, editor, settings
```

Every share is persisted before anything acts on it, in all three modes. A
failed auto-post therefore lands in the inbox as `FAILED` and can be retried,
instead of disappearing.

## Releasing

```sh
git tag v0.2.0
git push origin v0.2.0
```

`.github/workflows/release.yml` runs the tests, builds a release APK with
`versionName` taken from the tag and a `versionCode` derived from it
(`major*10000 + minor*100 + patch`), prints the signing certificate, and
publishes a GitHub release with the APK attached. Obtainium picks it up from
there. `workflow_dispatch` does the same with a hand-entered version.

The version code must increase with every release, so keep tags monotonic.

### Switching to a real release key

Generate a key, then add four repository secrets. The workflow uses them
automatically and falls back to the pinned debug key when they are absent.

```sh
keytool -genkeypair -v -keystore release.jks -alias fediferry \
  -keyalg RSA -keysize 4096 -validity 10950
base64 -i release.jks | pbcopy
```

| Secret                    | Value                       |
|---------------------------|-----------------------------|
| `RELEASE_KEYSTORE_BASE64` | the base64 blob             |
| `RELEASE_STORE_PASSWORD`  | keystore password           |
| `RELEASE_KEY_ALIAS`       | `fediferry`                 |
| `RELEASE_KEY_PASSWORD`    | key password                |

Changing the signing key breaks in-place updates: everyone already running a
debug-signed build has to uninstall and reinstall once. Do it before you hand
the link to anyone else, and keep the keystore backed up — losing it means the
same forced reinstall again.
