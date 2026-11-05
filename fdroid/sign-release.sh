#!/usr/bin/env bash
# Builds the release APKs exactly as F-Droid does (unsigned `assembleRelease`) and signs them with
# apksigner, producing the APKs to attach to the GitHub release `v<versionName>`:
#   app-armeabi-v7a-release.apk, app-arm64-v8a-release.apk, app-x86_64-release.apk  (F-Droid's
#   `binary` for each build block) and app-universal-release.apk (GitHub only, every ABI).
# The ABI splits in app/build.gradle.kts make all four in one build; F-Droid builds each ABI alone
# (`-PtargetAbi=<abi>`), which gives a byte-identical APK.
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
# A failing command must say so: a bare `exit 1` from set -e leaves an empty CI log.
trap 'echo "sign-release.sh: line $LINENO: \"$BASH_COMMAND\" failed (exit $?)" >&2' ERR

: "${KEYSTORE:?set KEYSTORE to the release keystore path}"
: "${KEY_ALIAS:?set KEY_ALIAS to the key alias}"

cd "$(dirname "$0")/.."

SDK_DIR="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}"
APKSIGNER="$(ls -d "$SDK_DIR"/build-tools/*/ | sort -V | tail -1)apksigner"

OUT_DIR=app/build/outputs/apk/release
# Every APK the release ships, as named by the ABI splits (keep in sync with ABI_DIGITS).
VARIANTS=(armeabi-v7a arm64-v8a x86_64 universal)

if [ "${SKIP_BUILD:-}" != 1 ]; then
  if [ -n "$(git status --porcelain)" ]; then
    echo "Working tree not clean: F-Droid builds the committed tag, commit first." >&2
    exit 1
  fi
  ./gradlew --no-daemon clean :app:assembleRelease
fi
for v in "${VARIANTS[@]}"; do
  if [ ! -f "$OUT_DIR/app-$v-release-unsigned.apk" ]; then
    echo "$OUT_DIR/app-$v-release-unsigned.apk is missing: run ./gradlew clean :app:assembleRelease first." >&2
    exit 1
  fi
  rm -f "$OUT_DIR/app-$v-release.apk"
done

signer_args=(--ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS")
[ -n "${KS_PASS:-}" ] && signer_args+=(--ks-pass "$KS_PASS")
[ -n "${KEY_PASS:-}" ] && signer_args+=(--key-pass "$KEY_PASS")

# The release key's certificate, as `AllowedAPKSigningKeys` in the fdroiddata recipe
# (metadata/com.bustramnantes.app.yml in fdroiddata, kept outside this repository): change both together.
expected="744e045d95b339648687b5a1517fcb6a98fc557157980bbaad8b035ee94829b1"

# minSdk 26: v2 + v3 schemes only (no v1 JAR signature, no v4 .idsig side file).
# --alignment-preserved: without it apksigner re-pads the zip entries, so the signed APK is no
# longer "F-Droid's unsigned build + this signature" and F-Droid's apksigcopier check fails
# (verified: CHUNKED_SHA256 digest mismatch without it, passes with it).
for v in "${VARIANTS[@]}"; do
  unsigned="$OUT_DIR/app-$v-release-unsigned.apk"
  out="$OUT_DIR/app-$v-release.apk"
  "$APKSIGNER" sign "${signer_args[@]}" --alignment-preserved true \
    --v1-signing-enabled false --v4-signing-enabled false --out "$out" "$unsigned"

  # Check the signing certificate against the one F-Droid will accept (AllowedAPKSigningKeys in the
  # fdroiddata recipe): a wrong key in the CI secrets fails here, not in F-Droid's verification.
  # Matched on the hex digest alone: the labels change between apksigner versions
  # ("Signer #1 certificate SHA-256 digest" in 35, "V3.0 Signer: certificate SHA-256 digest" in 37).
  certs="$("$APKSIGNER" verify --print-certs "$out")"
  actual="$(grep -i 'SHA-256 digest' <<<"$certs" | grep -oE '[0-9a-f]{64}' | sort -u || true)"
  echo "$(basename "$out"): certificate SHA-256 ${actual:-<none found>}"
  if [ -z "$actual" ] || [ "$(wc -l <<<"$actual")" -ne 1 ]; then
    echo "Expected exactly one signing certificate, apksigner reported:" >&2
    printf '%s\n' "$certs" >&2
    exit 1
  fi
  if [ -n "$expected" ] && [ "$actual" != "$expected" ]; then
    echo "Signed with the wrong key: the fdroiddata recipe's AllowedAPKSigningKeys is $expected." >&2
    exit 1
  fi
done
echo "Signed with $APKSIGNER"

VERSION="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
echo
echo "Attach these to the GitHub release v$VERSION (tag on $(git rev-parse --short HEAD)), names unchanged:"
for v in "${VARIANTS[@]}"; do echo "  $OUT_DIR/app-$v-release.apk"; done
