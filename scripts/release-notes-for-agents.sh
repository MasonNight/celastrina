#!/usr/bin/env bash
# Prints the release notes' "For agents" line when the configuration provider's protocol_version differs between two
# revisions, and nothing when it doesn't (spec #20: a "For agents" line whenever protocol_version changes).
#
#   scripts/release-notes-for-agents.sh <previous release tag> [<this release's revision, default HEAD>]
#
# With no previous release (an empty first argument), it prints nothing: there's no change to announce.
set -euo pipefail

previous="${1-}"
current="${2:-HEAD}"
[ -n "$previous" ] || exit 0

protocol_version() {
  git grep -h -E 'const val PROTOCOL_VERSION = [0-9]+' "$1" -- core/src/main | grep -oE '[0-9]+$' \
    || { echo "No PROTOCOL_VERSION in core/src/main at $1" >&2; exit 1; }
}

was="$(protocol_version "$previous")"
now="$(protocol_version "$current")"
if [ "$was" != "$now" ]; then
  echo "**For agents:** the configuration provider's \`protocol_version\` is now $now (it was $was in $previous). Pull the latest INSTALL.md before installing, updating or configuring this release."
fi
