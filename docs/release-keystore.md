# The release signing key

Every Celastrina release is signed with one key, for its whole life. Android installs an update only when it's signed with
the same key as the installed app, so **losing the key means no Household can update without uninstalling first**, and
anyone who gets it can publish an update every Household would install. The key never touches the repository, an agent,
or a networked build box except as a secret of the `release` environment, which only `v*` tags can use.

These steps are the maintainer's to do by hand, once. Agents must not generate the key, create the environment or its
secrets, or push tags.

Environment secrets, deployment tag rules and rulesets need a public repository on GitHub Free (or GitHub Pro for a
private one). Without them the signing key would be a repository secret that any branch's workflow could read.

## 1. Generate the key, offline

On a machine that is offline (or at least not shared), with a JDK 17 `keytool`:

```sh
keytool -genkeypair -v \
  -keystore celastrina-release.jks -storetype PKCS12 \
  -alias celastrina -keyalg RSA -keysize 4096 -validity 10958 \
  -dname "CN=Celastrina, O=Celastrina"
```

- `-validity 10958` is 30 years.
- Use a long random password from a password manager. A PKCS12 keystore uses the same password for the store and the
  key; enter it for both when asked.

## 2. Keep two offline backups

Copy `celastrina-release.jks` to two offline media kept in different places (for example two encrypted USB drives, one
at home and one elsewhere). Keep the password and the alias (`celastrina`) in the password manager, separately from
the drives. Check once that each copy opens:

```sh
keytool -list -keystore /path/to/backup/celastrina-release.jks
```

## 3. Record the certificate's fingerprint in the README

```sh
keytool -list -v -keystore celastrina-release.jks -alias celastrina | grep 'SHA256:'
```

Put the fingerprint (the `AB:CD:…` part) in `README.md` and `INSTALL.md`, on the line after
`<!-- release-certificate-sha256 -->` in each, replacing the placeholder, and merge that to `main`. The unit tests fail
while the two differ. The release workflow refuses to publish an APK whose certificate
isn't the one recorded there (`scripts/verify-apk-signature.sh --release`), so this has to land before the first
release.

## 4. Create the `release` environment and its secrets

With `gh` signed in as a repository admin:

```sh
repo=MasonNight/celastrina

# The environment, usable only from tags matching v*.
gh api -X PUT "repos/$repo/environments/release" --input - <<'JSON'
{"deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
JSON
gh api -X POST "repos/$repo/environments/release/deployment-branch-policies" -f name='v*' -f type=tag

# Its secrets. Each command prompts for the value, so none lands in shell history.
base64 < celastrina-release.jks | tr -d '\n' | gh secret set RELEASE_KEYSTORE_BASE64 --env release --repo "$repo"
gh secret set RELEASE_KEYSTORE_PASSWORD --env release --repo "$repo"
gh secret set RELEASE_KEY_PASSWORD --env release --repo "$repo"   # the same password, for PKCS12
gh secret set RELEASE_KEY_ALIAS --env release --repo "$repo"      # celastrina
gh secret set TMDB_API_KEY --env release --repo "$repo"           # the project TMDB key
```

Or in the web UI: Settings → Environments → New environment `release` → Deployment branches and tags → Selected
branches and tags → add a **tag** rule `v*`; then add the five secrets above.

Optionally, add a tag ruleset (Settings → Rules → Rulesets) that lets only the maintainer create `v*` tags, and a
required reviewer on the `release` environment so each publish waits for approval.

Then delete `celastrina-release.jks` from the machine you generated it on, unless that machine is itself offline storage.

## Releasing

Merge to `main`, then tag a commit on `main` with a version higher than the last release, and push the tag:

```sh
git fetch origin
git tag v0.1.0 origin/main
git push origin v0.1.0
```

The [release workflow](../.github/workflows/release.yml) checks the tag (`vMAJOR.MINOR.PATCH`, on `main`, higher than
every earlier release; no prerelease tags), runs the unit tests, lint and the release gates (the minified APK loads the
test Extension on emulators at API 25 and 34, has no `lib/`, has minSdk 25), builds and signs the APK with v2 and v3,
checks the certificate against the README, and publishes `celastrina-<version>.apk` and `SHA256SUMS` with generated
release notes. `versionCode` is `MAJOR*1_000_000 + MINOR*1_000 + PATCH`.

A failed release leaves no GitHub Release behind. Fix the cause on `main` and tag the next patch version; don't reuse a
tag.
