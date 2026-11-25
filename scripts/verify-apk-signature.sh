#!/usr/bin/env bash
# Checks how an APK is signed: with APK Signature Schemes v2 and v3, by exactly one signer.
#
#   scripts/verify-apk-signature.sh <apk>            print the signing certificate's SHA-256 fingerprint
#   scripts/verify-apk-signature.sh --release <apk>  also require an RSA 4096 key whose fingerprint is the one in README.md
#
# Uses apksigner from the newest build-tools under $ANDROID_HOME.
set -euo pipefail

release=false
if [ "${1:-}" = "--release" ]; then release=true; shift; fi
apk="${1:?usage: $0 [--release] <apk>}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"

apksigner="$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/*/apksigner | sort -V | tail -1)"
out="$("$apksigner" verify --verbose --print-certs "$apk")"

fail() { echo "$apk: $*" >&2; exit 1; }
grep -qx "Verified using v2 scheme (APK Signature Scheme v2): true" <<<"$out" || fail "not signed with APK Signature Scheme v2"
grep -qx "Verified using v3 scheme (APK Signature Scheme v3): true" <<<"$out" || fail "not signed with APK Signature Scheme v3"
grep -qx "Number of signers: 1" <<<"$out" || fail "not signed by exactly one signer"

fingerprint="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$out")"
[ -n "$fingerprint" ] || fail "apksigner printed no certificate fingerprint"
echo "$fingerprint"

if $release; then
  grep -qx "Signer #1 key algorithm: RSA" <<<"$out" || fail "the signing key isn't RSA"
  grep -qx "Signer #1 key size (bits): 4096" <<<"$out" || fail "the signing key isn't 4096 bits"
  # The README records the fingerprint, colon-separated as keytool prints it, on the line after this marker.
  recorded="$(sed -n '/<!-- release-certificate-sha256 -->/{n;p;}' "$REPO/README.md" | tr -d '`: \r' | tr 'A-F' 'a-f')"
  [[ "$recorded" =~ ^[0-9a-f]{64}$ ]] || fail "README.md records no release certificate fingerprint (see docs/release-keystore.md)"
  [ "$recorded" = "$fingerprint" ] || fail "signed with certificate $fingerprint, but README.md records $recorded"
fi
