#!/usr/bin/env bash
# Checks how an APK is signed: with APK Signature Schemes v2 and v3, by exactly one signer.
#
#   scripts/verify-apk-signature.sh <apk>            print the signing certificate's SHA-256 fingerprint
#   scripts/verify-apk-signature.sh --release <apk>  also require an RSA 4096 key whose fingerprint is the one in README.md
#
# Uses apksigner from the newest numbered build-tools under $ANDROID_HOME (apksigner.bat on Windows, from Git Bash).
set -euo pipefail

release=false
if [ "${1:-}" = "--release" ]; then release=true; shift; fi
apk="${1:?usage: $0 [--release] <apk>}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"

fail() { echo "$apk: $*" >&2; exit 1; }

# Only numbered versions: build-tools can also hold folders such as android-15, which sort -V would put last.
newest="$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/[0-9]*/ | sort -V | tail -1)"
apksigner="${newest}apksigner"
[ -f "$apksigner" ] || apksigner="${newest}apksigner.bat"
[ -f "$apksigner" ] || fail "no apksigner in $newest"

out="$("$apksigner" verify --verbose --print-certs "$apk" | tr -d '\r')" || fail "apksigner couldn't verify it"

grep -qx "Verified using v2 scheme (APK Signature Scheme v2): true" <<<"$out" || fail "not signed with APK Signature Scheme v2"
grep -qx "Verified using v3 scheme (APK Signature Scheme v3): true" <<<"$out" || fail "not signed with APK Signature Scheme v3"
grep -qx "Number of signers: 1" <<<"$out" || fail "not signed by exactly one signer"

# The value of a signer field. apksigner labels signers by build-tools version: "Signer #1 certificate SHA-256 digest: …"
# up to 36, "V3.0 Signer: certificate SHA-256 digest: …" (one block per scheme or SDK range) from 37. Every signer
# block must agree, so this prints exactly one value or fails.
signer_field() {
  local values
  values="$(sed -n "s/^.*Signer[^:]*:\{0,1\} $1: //p" <<<"$out" | sort -u)"
  [ -n "$values" ] || fail "apksigner printed no signer $1"
  [ "$(wc -l <<<"$values")" -eq 1 ] || fail "signer blocks disagree on $1: $(tr '\n' ' ' <<<"$values")"
  echo "$values"
}

fingerprint="$(signer_field "certificate SHA-256 digest")"
[[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || fail "apksigner printed no certificate fingerprint"
echo "$fingerprint"

if $release; then
  algorithm="$(signer_field "key algorithm")"
  [ "$algorithm" = RSA ] || fail "the signing key is $algorithm, not RSA"
  bits="$(signer_field "key size (bits)")"
  [ "$bits" = 4096 ] || fail "the signing key is $bits bits, not 4096"
  # The README records the fingerprint, colon-separated as keytool prints it, on the line after this marker.
  recorded="$(sed -n '/<!-- release-certificate-sha256 -->/{n;p;}' "$REPO/README.md" | tr -d '`: \r' | tr 'A-F' 'a-f')"
  [[ "$recorded" =~ ^[0-9a-f]{64}$ ]] || fail "README.md records no release certificate fingerprint (see docs/release-keystore.md)"
  [ "$recorded" = "$fingerprint" ] || fail "signed with certificate $fingerprint, but README.md records $recorded"
fi
