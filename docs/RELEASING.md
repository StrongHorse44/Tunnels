# Releasing Tunnels

Every push to the integration branch builds a **debug** APK (`io.github.stronghorse44.tunnels.debug`),
checks it against the release gate (`gate/baseline.json`, see `gate/README.md`) and publishes it to Releases.
A pull request or a manual run of the **CI** workflow builds, tests and checks the gate too, but never
publishes. It is labelled "Tunnels" like the release build; the version name ends in `-debug` and the package
name tells them apart. Each one is published as its own prerelease, **Debug build #N**, with a plain
`tunnels-debug-N.apk` you can tap on the phone. The newest is at the top of the Releases page and the last 10
are kept. Debug builds install alongside release builds, use the build number as their version code, and are
signed with a public debug key committed in `app/debug.keystore`, so each one updates over the last. Because
that key is public, the certificate pin in `gate/baseline.json` only catches an accidental key change; it
does not show who built the APK.

The publish job runs in the `publish` environment and attaches a build provenance attestation to the APK.
With required reviewers set on that environment (Settings, Environments, `publish`), each publish of the
unmodified workflow waits for approval first. What that does and does not give you:

- The certificate pin in `gate/baseline.json` is the certificate of the public debug keystore. Anyone can
  sign an APK with it, so it proves nothing about who built an APK.
- The `publish` environment only stops the workflow as it is checked in. A branch in this repository can
  edit `ci.yml` and publish a `debug-N` release with its own run's token, without going through that
  environment. The in-app updater checks only the release tag, the asset name and that the APK is signed with
  the same key, and that key is public.
- Until the release key exists (B02), the only control is who can push to this repository.

To check where an APK came from, from Termux (`pkg install gh`, then `gh auth login`):

```sh
gh attestation verify tunnels-debug-<N>.apk --repo StrongHorse44/Tunnels \
  --signer-workflow StrongHorse44/Tunnels/.github/workflows/ci.yml \
  --source-ref refs/heads/ccr-dff99af5-ij1rle
```

A passing check shows the APK was built by that workflow file on that branch. It does not make the public
key private, and anyone who can push to that branch can change what the workflow does.

Pushing a tag like `v0.1.0` builds a **signed release** APK and attaches it to a GitHub Release, once the
release baseline `gate/baseline.release.json` exists (it is added with the build that moves the app to
release builds; until then the Release workflow stops at its first step, after the `publish` environment's approval, because
its build job runs in that environment). The tag's commit must be on the
integration branch or `main`.
Obtainium can follow those releases directly.

## Updating from inside Tunnels

Home screen → **update ›**. A debug build follows the "Debug build #N" prereleases, a release build the
`vX.Y.Z` releases. Tapping **Check** asks `api.github.com` for this repository's releases; **Download** fetches
the APK (and its `.sha256`) and Tunnels refuses it unless it is the same package, newer, signed with the same key
as the installed copy and matching the checksum. Android then asks you to confirm the update.

- GrapheneOS: turn on Tunnels' **Network** permission for the check (App info → Permissions → Network), and off
  again afterwards. Tunnels never connects in the background.
- The repository is public, so the update check needs no token. (For a private copy of it GitHub does:
  github.com → Settings → Developer settings →
  Fine-grained tokens → Generate new token, repository access **only StrongHorse44/Tunnels**, permission
  **Contents: read-only**. Paste it on the update screen. It is stored in the encrypted database, sent to
  `api.github.com` only, and forgotten after 30 days without a check.)

## One-time: create the signing key

Do this on a computer you trust, not on the phone. Keep an offline backup of the keystore and passwords:
**if you lose it, updates won't install over the existing app** and you'd have to uninstall (losing data).

```sh
keytool -genkeypair -v -keystore tunnels-release.jks -alias tunnels \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 tunnels-release.jks > tunnels-release.jks.b64   # macOS: base64 -i tunnels-release.jks
```

In GitHub: **Settings → Environments → `publish` → Environment secrets → Add environment secret** (not
repository secrets: the `publish` environment is limited to the integration branch, `main` and `v*` tags,
so a run from any other branch cannot read them), add:

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

The version code is derived from the tag (`v1.2.3` → `1002003`), so tags must increase. Tag only a commit
that is on the integration branch or `main` and contains the release gate; the workflow refuses a commit that
is on neither.

## Verifying a release

Each release includes `tunnels-<version>.apk.sha256`. The workflow log prints the signing certificate
(`apksigner verify --print-certs`); note its SHA-256 once and compare it on later releases. The release
job also refuses to publish a certificate other than the one pinned in `gate/baseline.release.json`.
