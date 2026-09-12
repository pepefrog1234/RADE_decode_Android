#!/usr/bin/env bash
# Verify the actual distributable, including its persistent signing identity.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT_DIR/app/build/outputs/apk/release/app-release.apk}"
if [[ -z "${APKSIGNER:-}" ]]; then
  SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "$SDK_DIR" && -f "$ROOT_DIR/local.properties" ]]; then
    SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | tr -d '\r')"
  fi
  if [[ -z "$SDK_DIR" ]]; then
    echo "Set ANDROID_HOME or APKSIGNER to locate Android SDK Build Tools." >&2
    exit 1
  fi
  # This project's minSdk needs v2 support; all installed modern Build Tools work.
  for candidate in "$SDK_DIR"/build-tools/*/apksigner; do
    [[ ! -x "$candidate" ]] || APKSIGNER="$candidate"
  done
fi
if [[ ! -x "${APKSIGNER:-}" || ! -f "$APK" ]]; then
  echo "APK or apksigner was not found." >&2
  exit 1
fi

REPORT="$(mktemp)"
trap 'rm -f "$REPORT"' EXIT
"$APKSIGNER" verify --verbose --print-certs "$APK" | tee "$REPORT"
EXPECTED="$(tr -d ':[:space:]' < "$ROOT_DIR/docs/signing/release-certificate.sha256" | tr '[:upper:]' '[:lower:]')"
# Build Tools 37 labels v3 certificates as "V3.0 Signer:"; older versions
# use "Signer #1". Require every reported signer certificate to match the pin.
ACTUAL="$(sed -nE 's/^(Signer #[0-9]+|V[0-9.]+ Signer):? certificate SHA-256 digest: ([[:xdigit:]]+)$/\2/p' "$REPORT" | tr '[:upper:]' '[:lower:]' | sort -u | tr -d '[:space:]')"
if [[ ${#EXPECTED} -ne 64 || "$ACTUAL" != "$EXPECTED" ]]; then
  echo "APK certificate does not match the pinned release identity." >&2
  exit 1
fi
if ! grep -qx 'Number of signers: 1' "$REPORT"; then
  echo "Unexpected APK signer count." >&2
  exit 1
fi

AAPT="$(dirname "$APKSIGNER")/aapt"
BADGING="$("$AAPT" dump badging "$APK")"
if [[ "$BADGING" != "package: name='yakumo2683.RADEdecode' "* ]]; then
  echo "Unexpected APK package name." >&2
  exit 1
fi
if grep -q '^application-debuggable' <<< "$BADGING"; then
  echo "Refusing to distribute a debuggable APK as a release." >&2
  exit 1
fi
echo "Release APK verified: correct certificate, package name, and non-debuggable build."
