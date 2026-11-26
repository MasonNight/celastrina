# Celastrina

An open-source streaming app for Fire TV that lets a household browse movies and series with rich artwork and play them from whichever Source can currently serve them.

## Installing

Point your AI agent at [INSTALL.md](INSTALL.md), the Install Runbook: it installs, updates and uninstalls Celastrina on
your Fire TV over ADB from macOS, Linux or Windows, and asks you only for what it can't do itself.

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
`79:BB:5C:DC:7A:B5:0E:08:42:42:DF:F8:39:EB:37:53:D8:21:8F:CD:C7:42:6A:59:DA:15:F5:6A:9C:3C:21:71`

Check a download with `apksigner verify --print-certs celastrina-<version>.apk`: its `certificate SHA-256 digest` is this
fingerprint, without the colons, in lower case.

## Attribution

<img src="docs/images/tmdb-logo.svg" alt="TMDB" width="240">

This product uses the TMDB API but is not endorsed or certified by TMDB.

## License

Celastrina is free software under the [GNU General Public License v3.0](LICENSE). It bundles CloudStream's `library`,
which is GPL-3.0 too.
