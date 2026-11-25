# Celastrina

An open-source streaming app for Fire TV that lets a household browse movies and series with rich artwork and play them from whichever Source can currently serve them.

## Building

Celastrina's Catalogue comes from TMDB. A build takes the project's TMDB key from the `TMDB_API_KEY` Gradle property or environment variable. A build without one still runs, and shows "No TMDB key" until a household sets its own key:

```sh
adb shell content call --uri content://dev.celastrina.configuration --method set-tmdb-key --arg "$(printf '{"key":"<your TMDB key>"}' | base64 | tr -d '\n')"
```

`clear-tmdb-key` goes back to the project key, and the `status` command says which key is in use without revealing it.

## Releases

Each [GitHub Release](https://github.com/MasonNight/celastrina/releases) holds `celastrina-<version>.apk` and its
`SHA256SUMS`. Every release is signed with the same key, whose certificate has this SHA-256 fingerprint:

<!-- release-certificate-sha256 -->
`PLACEHOLDER: the maintainer records the fingerprint here before the first release (docs/release-keystore.md)`

Check a download with `apksigner verify --print-certs celastrina-<version>.apk`: its `certificate SHA-256 digest` is this
fingerprint, without the colons, in lower case.

## Attribution

<img src="docs/images/tmdb-logo.svg" alt="TMDB" width="240">

This product uses the TMDB API but is not endorsed or certified by TMDB.
