#!/usr/bin/env bash
# Builds the release APK exactly as F-Droid does (unsigned `assembleRelease`) and signs it with
# apksigner, producing the `app-release.apk` to attach to the GitHub release `v<versionName>`.
#
# F-Droid's reproducible-build check rebuilds the same tag, copies the signature of this APK onto
# its own unsigned build and verifies it: it only passes if both unsigned builds are byte-identical
# (see the vcsInfo / ArtProfile settings in app/build.gradle.kts) and the signing certificate
# matches `AllowedAPKSigningKeys` in the fdroiddata metadata.
#
# Usage:  KEYSTORE=/path/release.jks KEY_ALIAS=release fdroid/sign-release.sh
#         (apksigner prompts for the passwords; or set KS_PASS / KEY_PASS, e.g. "env:MY_VAR")
#         SKIP_BUILD=1 signs the APK already built by `./gradlew clean :app:assembleRelease` (CI
#         builds without the secrets in its environment, then signs in a step that runs no Gradle).
#
# First time only - create the key (keep it and its passwords safe: losing it means F-Droid users
# can never update, changing it means they must uninstall):
#   keytool -genkeypair -keystore release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000
# Fingerprint for AllowedAPKSigningKeys (lowercase hex, no colons):
#   apksigner verify --print-certs app-release.apk | grep 'SHA-256' | awk '{print $NF}'
set -euo pipefail

: "${KEYSTORE:?set KEYSTORE to the release keystore path}"
: "${KEY_ALIAS:?set KEY_ALIAS to the key alias}"

cd "$(dirname "$0")/.."

SDK_DIR="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}"
APKSIGNER="$(ls -d "$SDK_DIR"/build-tools/*/ | sort -V | tail -1)apksigner"

UNSIGNED=app/build/outputs/apk/release/app-release-unsigned.apk
OUT=app/build/outputs/apk/release/app-release.apk

if [ "${SKIP_BUILD:-}" != 1 ]; then
  if [ -n "$(git status --porcelain)" ]; then
    echo "Working tree not clean: F-Droid builds the committed tag, commit first." >&2
    exit 1
  fi
  ./gradlew --no-daemon clean :app:assembleRelease
elif [ ! -f "$UNSIGNED" ]; then
  echo "SKIP_BUILD=1 but $UNSIGNED is missing: run ./gradlew clean :app:assembleRelease first." >&2
  exit 1
fi
rm -f "$OUT"

signer_args=(--ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS")
[ -n "${KS_PASS:-}" ] && signer_args+=(--ks-pass "$KS_PASS")
[ -n "${KEY_PASS:-}" ] && signer_args+=(--key-pass "$KEY_PASS")

# minSdk 26: v2 + v3 schemes only (no v1 JAR signature, no v4 .idsig side file).
# --alignment-preserved: without it apksigner re-pads the zip entries, so the signed APK is no
# longer "F-Droid's unsigned build + this signature" and F-Droid's apksigcopier check fails
# (verified: CHUNKED_SHA256 digest mismatch without it, passes with it).
"$APKSIGNER" sign "${signer_args[@]}" --alignment-preserved true \
  --v1-signing-enabled false --v4-signing-enabled false --out "$OUT" "$UNSIGNED"
"$APKSIGNER" verify --print-certs "$OUT" | grep -E 'Signer #1 certificate (DN|SHA-256)'

VERSION="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
echo
echo "Signed: $OUT"
echo "Attach it as 'app-release.apk' to the GitHub release v$VERSION (tag on $(git rev-parse --short HEAD))."
