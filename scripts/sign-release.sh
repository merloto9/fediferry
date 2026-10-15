#!/usr/bin/env bash
#
# Signs an unsigned release APK with the release key, rotated from the pinned
# debug key that signed every earlier release.
#
#     scripts/sign-release.sh app-release-unsigned.apk fediferry-1.2.3.apk
#
# Every earlier release — and the original FediFerry app — was signed with
# keystore/debug.keystore, which is public. Signing with the release key alone
# would make Android refuse the update, and everyone would have to uninstall
# (losing their drafts). Key rotation (APK Signature Scheme v3) avoids that:
# the APK carries a lineage in which the debug key vouches for the release
# key. Android 9 and later then update in place, and from then on accept no
# APK signed with the debug key alone. Android 8 (API 26–27) has no rotation
# and keeps checking the debug key's v2 signature; that cannot be helped
# without breaking updates there.
#
# Reads the release key from the environment:
#   RELEASE_KEYSTORE        path to the release keystore (.jks)
#   RELEASE_STORE_PASSWORD  its password
#   RELEASE_KEY_ALIAS       the key's alias
#   RELEASE_KEY_PASSWORD    the key's password
# and the build tools from BUILD_TOOLS, or the newest under $ANDROID_HOME.

set -euo pipefail

in=${1:?unsigned APK}
out=${2:?signed APK to write}

: "${RELEASE_KEYSTORE:?the release keystore is required; releases are never signed with the public debug key alone}"
: "${RELEASE_STORE_PASSWORD:?}" "${RELEASE_KEY_ALIAS:?}" "${RELEASE_KEY_PASSWORD:?}"
export RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD

root=$(cd "$(dirname "$0")/.." && pwd)
tools=${BUILD_TOOLS:-$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

old=(--ks "$root/keystore/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android)
new=(--ks "$RELEASE_KEYSTORE" --ks-pass env:RELEASE_STORE_PASSWORD --ks-key-alias "$RELEASE_KEY_ALIAS" --key-pass env:RELEASE_KEY_PASSWORD)

# The debug key vouches for the release key. Rollback stays off (the default),
# so an APK signed with the debug key alone cannot replace a rotated install.
"$tools/apksigner" rotate --out "$work/lineage" --old-signer "${old[@]}" --new-signer "${new[@]}"

"$tools/zipalign" -p -f 4 "$in" "$work/aligned.apk"

# v1/v2 carry the debug key for Android 8, v3 carries the release key and the
# lineage from Android 9 on (28, not apksigner's default of 33, so that the
# rotation reaches every phone that understands it).
"$tools/apksigner" sign "${old[@]}" --next-signer "${new[@]}" \
  --lineage "$work/lineage" --rotation-min-sdk-version 28 \
  --out "$out" "$work/aligned.apk"

"$tools/apksigner" verify --verbose --print-certs "$out"
