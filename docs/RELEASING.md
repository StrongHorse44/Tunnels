# Releasing Tunnels

Every push to the integration branch, and every manual run of the **CI** workflow on any other branch
(Actions → CI → Run workflow, pick the branch), builds a **debug** APK (`io.github.stronghorse44.tunnels.debug`)
and publishes it to Releases. Work on another branch reaches the phone the same way: start CI on it. It is
labelled "Tunnels" like the release build; the version name ends in `-debug` and the package name tells them apart.
Each one is published as its own prerelease, **Debug build #N**, with a plain `tunnels-debug-N.apk`
you can tap on the phone. The newest is at the top of the Releases page and the last 10 are kept.
Debug builds install alongside release builds, use the build number as their version code, and are
signed with a public debug key committed in `app/debug.keystore`, so each one updates over the last.

Pushing a tag like `v0.1.0` builds a **signed release** APK and attaches it to a GitHub Release.
Obtainium can follow those releases directly.

## Updating from inside Tunnels

Home screen → **update ›**. A debug build follows the "Debug build #N" prereleases, a release build the
`vX.Y.Z` releases. Tapping **Check** asks `api.github.com` for this repository's releases; **Download** fetches
the APK (and its `.sha256`) and Tunnels refuses it unless it is the same package, newer, signed with the same key
as the installed copy and matching the checksum. Android then asks you to confirm the update.

- GrapheneOS: turn on Tunnels' **Network** permission for the check (App info → Permissions → Network), and off
  again afterwards. Tunnels never connects in the background.
- While the repository is private, GitHub needs a token: github.com → Settings → Developer settings →
  Fine-grained tokens → Generate new token, repository access **only StrongHorse44/Tunnels**, permission
  **Contents: read-only**. Paste it on the update screen. It is stored in the encrypted database, sent to
  `api.github.com` only, and forgotten after 30 days without a check.

## One-time: create the signing key

Do this on a computer you trust, not on the phone. Keep an offline backup of the keystore and passwords:
**if you lose it, updates won't install over the existing app** and you'd have to uninstall (losing data).

```sh
keytool -genkeypair -v -keystore tunnels-release.jks -alias tunnels \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 tunnels-release.jks > tunnels-release.jks.b64   # macOS: base64 -i tunnels-release.jks
```

In GitHub: **Settings → Secrets and variables → Actions → New repository secret**, add:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | contents of `tunnels-release.jks.b64` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `tunnels` |
| `KEY_PASSWORD` | key password (same as keystore password unless you set a different one) |

Then delete the `.b64` file.

## Cutting a release

```sh
git tag v0.1.0
git push origin v0.1.0
```

The version code is derived from the tag (`v1.2.3` → `1002003`), so tags must increase.

## Verifying a release

Each release includes `tunnels-<version>.apk.sha256`. The workflow log prints the signing certificate
(`apksigner verify --print-certs`); note its SHA-256 once and compare it on later releases.
