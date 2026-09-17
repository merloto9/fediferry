# FediFerry

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3%20or%20later-blue.svg)](LICENSE)

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
image and never the caption. That is Instagram's limit, not a bug here: no
sanctioned endpoint returns the media, for public or private accounts (the
reasoning and the tests behind it are in [SPEC.md](SPEC.md)).

So getting an image *and* its attribution into one post takes two shares, in
either order:

1. Share the post from Instagram — you get a draft with the link and no image,
   and the app says so.
2. Screenshot it and share that — the screenshot **joins the draft you just
   made** instead of starting a second one.

You end up with one post carrying both. Only a share supplying exactly the half
that is missing gets joined, and only within ten minutes; a share carrying both
an image and a link stands on its own. If you have already edited a draft's text
by hand, pairing leaves your wording alone.

### Sharing from 9GAG

Share a 9GAG link on its own and the image is fetched for you — no screenshot,
no trimming, and at the original quality rather than a re-encoded screen grab.
Animated posts come across as the video, not a still frame.

The post's title is available to templates as `{caption}`, so a template like
`{caption}\n\n{tags}\n\nvia {link}` carries it over. As always the placeholder
degrades to nothing when it cannot be resolved.

Turn this off with *Settings → Fetch images from shared links*. Instagram
publishes nothing fetchable, so its shares are unaffected either way.

### Trimming

A screenshot includes the whole screen; the post wants the picture. FediFerry
detects the picture and offers the crop itself, so there is no trip through a
photo editor.

- **Compose** shows the proposed crop with draggable corners — approve, adjust,
  take the whole image, or skip.
- **Post now** and **Save for later** apply it silently when the detection is
  confident. Turn that off with *Settings → Trim screenshots automatically*.

The original screenshot is always kept, so **Undo trim** in the editor puts it
back, and re-trimming works from the original rather than cutting into a
previous crop.

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

## License

FediFerry is free software: you can redistribute it and/or modify it under the
terms of the **GNU General Public License, version 3 or later**
(`GPL-3.0-or-later`). The full text is in [LICENSE](LICENSE), and every source
file carries the standard header.

    Copyright (C) 2026 Jasper Ramthun

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.

### Why v3 and not v2

Not a preference — a constraint. Every one of the 192 modules that ships inside
the APK is Apache-2.0 except one, which is BSD-3-Clause. Apache-2.0 is
[incompatible with GPLv2](https://www.gnu.org/licenses/license-list.html#apache2)
because its patent-termination and indemnification terms count as "further
restrictions" under GPLv2 §6, but the FSF accepts it as compatible with GPLv3,
which added §7 to allow exactly these additional terms. BSD-3-Clause is
compatible with both. Releasing under GPLv2 would therefore be unlawful for this
dependency set; GPLv3 is the earliest GPL version that works.

## Dependencies

Licenses below are read from each artifact's published POM (walking to the
parent POM where a module declares none). To regenerate after a dependency
change, resolve `releaseRuntimeClasspath` and re-read the POMs — see
`Full transitive set` for the current snapshot.

### Declared directly

| Dependency | Version | License | Used for |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.19.0 | Apache-2.0 | Kotlin extensions on the framework |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.11.0 | Apache-2.0 | Lifecycle-aware coroutine scopes |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | Apache-2.0 | ViewModels in Compose |
| `androidx.activity:activity-compose` | 1.13.0 | Apache-2.0 | Compose host activity |
| `androidx.compose:compose-bom` | 2026.09.00 | Apache-2.0 | Compose version alignment |
| `androidx.compose.ui:ui` | 1.12.1 | Apache-2.0 | Compose UI toolkit |
| `androidx.compose.ui:ui-graphics` | 1.12.1 | Apache-2.0 | Compose graphics |
| `androidx.compose.ui:ui-tooling` | 1.12.1 | Apache-2.0 | Compose previews (debug only) |
| `androidx.compose.ui:ui-tooling-preview` | 1.12.1 | Apache-2.0 | Compose preview annotations |
| `androidx.compose.material3:material3` | 1.4.0 | Apache-2.0 | Material 3 components |
| `androidx.compose.material:material-icons-extended` | 1.7.8 | Apache-2.0 | Icon set |
| `androidx.navigation:navigation-compose` | 2.10.1 | Apache-2.0 | Inbox / editor / settings navigation |
| `androidx.room:room-runtime` | 2.8.5 | Apache-2.0 | The local item store |
| `androidx.room:room-ktx` | 2.8.5 | Apache-2.0 | Room coroutine and Flow support |
| `androidx.room:room-compiler` | 2.8.5 | Apache-2.0 | Room code generation (KSP, not shipped) |
| `androidx.work:work-runtime-ktx` | 2.11.2 | Apache-2.0 | PostWorker scheduling and retries |
| `androidx.security:security-crypto` | 1.1.0 | Apache-2.0 | Encrypted access-token storage |
| `androidx.browser:browser` | 1.10.0 | Apache-2.0 | Custom Tab for the OAuth flow |
| `androidx.sharetarget:sharetarget` | 1.2.0 | Apache-2.0 | Sharing Shortcuts on API 26-28 |
| `androidx.datastore:datastore-preferences` | 1.2.1 | Apache-2.0 | Settings storage |
| `com.squareup.okhttp3:okhttp` | 5.5.0 | Apache-2.0 | Mastodon and alt-text HTTP calls |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | Apache-2.0 | Mastodon API payloads |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache-2.0 | Coroutines |
| `io.coil-kt.coil3:coil-compose` | 3.6.2 | Apache-2.0 | Image thumbnails in the inbox and editor |

### Shipped in the APK

All **192** modules on the release runtime classpath, transitive
dependencies included:

- **Apache-2.0** — 191 modules
- **BSD-3-Clause** — 1 module

Both are GPLv3-compatible, so the combined work may be distributed under
GPL-3.0-or-later.

<details>
<summary>Full transitive set (192 modules)</summary>

**Apache-2.0**

```
androidx.activity:activity-compose:1.13.0
androidx.activity:activity-ktx:1.13.0
androidx.activity:activity:1.13.0
androidx.annotation:annotation-experimental:1.5.0
androidx.annotation:annotation-jvm:1.10.0
androidx.annotation:annotation:1.10.0
androidx.appcompat:appcompat-resources:1.7.1
androidx.arch.core:core-common:2.2.0
androidx.arch.core:core-runtime:2.2.0
androidx.autofill:autofill:1.0.0
androidx.browser:browser:1.10.0
androidx.collection:collection-jvm:1.5.0
androidx.collection:collection-ktx:1.5.0
androidx.collection:collection:1.5.0
androidx.compose.animation:animation-android:1.12.1
androidx.compose.animation:animation-core-android:1.12.1
androidx.compose.animation:animation-core:1.12.1
androidx.compose.animation:animation:1.12.1
androidx.compose.foundation:foundation-android:1.12.1
androidx.compose.foundation:foundation-layout-android:1.12.1
androidx.compose.foundation:foundation-layout:1.12.1
androidx.compose.foundation:foundation:1.12.1
androidx.compose.material3:material3-android:1.4.0
androidx.compose.material3:material3:1.4.0
androidx.compose.material:material-icons-core-android:1.7.8
androidx.compose.material:material-icons-core:1.7.8
androidx.compose.material:material-icons-extended-android:1.7.8
androidx.compose.material:material-icons-extended:1.7.8
androidx.compose.material:material-ripple-android:1.12.1
androidx.compose.material:material-ripple:1.12.1
androidx.compose.runtime:runtime-android:1.12.1
androidx.compose.runtime:runtime-annotation-android:1.12.1
androidx.compose.runtime:runtime-annotation:1.12.1
androidx.compose.runtime:runtime-retain-android:1.12.1
androidx.compose.runtime:runtime-retain:1.12.1
androidx.compose.runtime:runtime-saveable-android:1.12.1
androidx.compose.runtime:runtime-saveable:1.12.1
androidx.compose.runtime:runtime:1.12.1
androidx.compose.ui:ui-android:1.12.1
androidx.compose.ui:ui-geometry-android:1.12.1
androidx.compose.ui:ui-geometry:1.12.1
androidx.compose.ui:ui-graphics-android:1.12.1
androidx.compose.ui:ui-graphics:1.12.1
androidx.compose.ui:ui-text-android:1.12.1
androidx.compose.ui:ui-text:1.12.1
androidx.compose.ui:ui-tooling-preview-android:1.12.1
androidx.compose.ui:ui-tooling-preview:1.12.1
androidx.compose.ui:ui-unit-android:1.12.1
androidx.compose.ui:ui-unit:1.12.1
androidx.compose.ui:ui-util-android:1.12.1
androidx.compose.ui:ui-util:1.12.1
androidx.compose.ui:ui:1.12.1
androidx.compose:compose-bom:2026.09.00
androidx.concurrent:concurrent-futures-ktx:1.1.0
androidx.concurrent:concurrent-futures:1.1.0
androidx.core:core-ktx:1.19.0
androidx.core:core-viewtree:1.0.0
androidx.core:core:1.19.0
androidx.customview:customview-poolingcontainer:1.0.0
androidx.datastore:datastore-android:1.2.1
androidx.datastore:datastore-core-android:1.2.1
androidx.datastore:datastore-core-okio-jvm:1.2.1
androidx.datastore:datastore-core-okio:1.2.1
androidx.datastore:datastore-core:1.2.1
androidx.datastore:datastore-preferences-android:1.2.1
androidx.datastore:datastore-preferences-core-android:1.2.1
androidx.datastore:datastore-preferences-core:1.2.1
androidx.datastore:datastore-preferences-proto:1.2.1
androidx.datastore:datastore-preferences:1.2.1
androidx.datastore:datastore:1.2.1
androidx.emoji2:emoji2:1.4.0
androidx.exifinterface:exifinterface:1.4.2
androidx.graphics:graphics-path:1.0.1
androidx.interpolator:interpolator:1.0.0
androidx.lifecycle:lifecycle-common-java8:2.11.0
androidx.lifecycle:lifecycle-common-jvm:2.11.0
androidx.lifecycle:lifecycle-common:2.11.0
androidx.lifecycle:lifecycle-livedata-core-ktx:2.11.0
androidx.lifecycle:lifecycle-livedata-core:2.11.0
androidx.lifecycle:lifecycle-livedata:2.11.0
androidx.lifecycle:lifecycle-process:2.11.0
androidx.lifecycle:lifecycle-runtime-android:2.11.0
androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0
androidx.lifecycle:lifecycle-runtime-compose:2.11.0
androidx.lifecycle:lifecycle-runtime-ktx-android:2.11.0
androidx.lifecycle:lifecycle-runtime-ktx:2.11.0
androidx.lifecycle:lifecycle-runtime:2.11.0
androidx.lifecycle:lifecycle-service:2.11.0
androidx.lifecycle:lifecycle-viewmodel-android:2.11.0
androidx.lifecycle:lifecycle-viewmodel-compose-android:2.11.0
androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0
androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0
androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.11.0
androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0
androidx.lifecycle:lifecycle-viewmodel:2.11.0
androidx.navigation:navigation-common-android:2.10.1
androidx.navigation:navigation-common:2.10.1
androidx.navigation:navigation-compose-android:2.10.1
androidx.navigation:navigation-compose:2.10.1
androidx.navigation:navigation-runtime-android:2.10.1
androidx.navigation:navigation-runtime:2.10.1
androidx.navigationevent:navigationevent-android:1.1.2
androidx.navigationevent:navigationevent-compose-android:1.1.2
androidx.navigationevent:navigationevent-compose:1.1.2
androidx.navigationevent:navigationevent:1.1.2
androidx.profileinstaller:profileinstaller:1.4.1
androidx.room:room-common-jvm:2.8.5
androidx.room:room-common:2.8.5
androidx.room:room-ktx:2.8.5
androidx.room:room-runtime-android:2.8.5
androidx.room:room-runtime:2.8.5
androidx.savedstate:savedstate-android:1.5.0
androidx.savedstate:savedstate-compose-android:1.5.0
androidx.savedstate:savedstate-compose:1.5.0
androidx.savedstate:savedstate-ktx:1.5.0
androidx.savedstate:savedstate:1.5.0
androidx.security:security-crypto:1.1.0
androidx.sharetarget:sharetarget:1.2.0
androidx.sqlite:sqlite-android:2.6.2
androidx.sqlite:sqlite-framework-android:2.6.2
androidx.sqlite:sqlite-framework:2.6.2
androidx.sqlite:sqlite:2.6.2
androidx.startup:startup-runtime:1.2.0
androidx.tracing:tracing-ktx:1.2.0
androidx.tracing:tracing:1.2.0
androidx.vectordrawable:vectordrawable-animated:1.1.0
androidx.vectordrawable:vectordrawable:1.1.0
androidx.versionedparcelable:versionedparcelable:1.1.1
androidx.window:window-core-android:1.5.0
androidx.window:window-core:1.5.0
androidx.window:window:1.5.0
androidx.work:work-runtime-ktx:2.11.2
androidx.work:work-runtime:2.11.2
com.google.accompanist:accompanist-drawablepainter:0.37.3
com.google.code.gson:gson:2.8.9
com.google.crypto.tink:tink-android:1.8.0
com.google.guava:listenablefuture:1.0
com.squareup.okhttp3:okhttp-android:5.5.0
com.squareup.okhttp3:okhttp:5.5.0
com.squareup.okio:okio-jvm:3.18.1
com.squareup.okio:okio:3.18.1
io.coil-kt.coil3:coil-android:3.6.2
io.coil-kt.coil3:coil-compose-android:3.6.2
io.coil-kt.coil3:coil-compose-core-android:3.6.2
io.coil-kt.coil3:coil-compose-core:3.6.2
io.coil-kt.coil3:coil-compose:3.6.2
io.coil-kt.coil3:coil-core-android:3.6.2
io.coil-kt.coil3:coil-core:3.6.2
io.coil-kt.coil3:coil:3.6.2
org.jetbrains.androidx.lifecycle:lifecycle-common:2.9.6
org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6
org.jetbrains.androidx.lifecycle:lifecycle-runtime:2.9.6
org.jetbrains.androidx.savedstate:savedstate-compose:1.3.6
org.jetbrains.androidx.savedstate:savedstate:1.3.6
org.jetbrains.compose.animation:animation-android:1.12.0
org.jetbrains.compose.animation:animation-core-android:1.12.0
org.jetbrains.compose.animation:animation-core:1.12.0
org.jetbrains.compose.animation:animation:1.12.0
org.jetbrains.compose.foundation:foundation-android:1.12.0
org.jetbrains.compose.foundation:foundation-layout-android:1.12.0
org.jetbrains.compose.foundation:foundation-layout:1.12.0
org.jetbrains.compose.foundation:foundation:1.12.0
org.jetbrains.compose.runtime:runtime-android:1.12.0
org.jetbrains.compose.runtime:runtime-saveable-android:1.12.0
org.jetbrains.compose.runtime:runtime-saveable:1.12.0
org.jetbrains.compose.runtime:runtime:1.12.0
org.jetbrains.compose.ui:ui-android:1.12.0
org.jetbrains.compose.ui:ui-geometry-android:1.12.0
org.jetbrains.compose.ui:ui-geometry:1.12.0
org.jetbrains.compose.ui:ui-graphics-android:1.12.0
org.jetbrains.compose.ui:ui-graphics:1.12.0
org.jetbrains.compose.ui:ui-text-android:1.12.0
org.jetbrains.compose.ui:ui-text:1.12.0
org.jetbrains.compose.ui:ui-unit-android:1.12.0
org.jetbrains.compose.ui:ui-unit:1.12.0
org.jetbrains.compose.ui:ui-util-android:1.12.0
org.jetbrains.compose.ui:ui-util:1.12.0
org.jetbrains.compose.ui:ui:1.12.0
org.jetbrains.kotlin:kotlin-stdlib-common:2.4.20
org.jetbrains.kotlin:kotlin-stdlib:2.4.20
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0
org.jetbrains.kotlinx:kotlinx-serialization-bom:1.11.0
org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0
org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0
org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0
org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0
org.jetbrains:annotations:23.0.0
org.jspecify:jspecify:1.0.0
```

**BSD-3-Clause**

```
androidx.datastore:datastore-preferences-external-protobuf:1.2.1
```

</details>

### Not distributed

These run on the build machine or in tests and are never packaged into the APK,
so they place no condition on the licence of the released binary:

| Dependency | License | Scope |
|---|---|---|
| `com.android.tools.build:gradle` (AGP) | Apache-2.0 | Build |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | Apache-2.0 | Build |
| `com.google.devtools.ksp` | Apache-2.0 | Build |
| `androidx.room:room-compiler` | Apache-2.0 | Build (KSP processor) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | Apache-2.0 | Test |
| `androidx.test.ext:junit`, `androidx.test:runner` | Apache-2.0 | Instrumented test |
| `junit:junit` | **EPL-1.0** | Test |
| `org.hamcrest:hamcrest-core` | BSD-3-Clause | Test (via JUnit) |

JUnit 4 is the one item worth calling out: EPL-1.0 is **not** GPL-compatible.
It is a test-only dependency, never linked into or shipped with the APK, so it
does not affect the distributed work — but it would have to go if test code were
ever distributed as part of a GPL binary.

Hamcrest declares no licence in its POM; BSD-3-Clause above is taken from the
upstream project rather than from published metadata.
