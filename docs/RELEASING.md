# Releasing Tunnels

Every push builds a **debug** APK (`io.github.stronghorse44.tunnels.debug`, labelled "Tunnels (debug)").
Download it from the run's *Artifacts* section in GitHub Actions. It installs alongside the release build.

Pushing a tag like `v0.1.0` builds a **signed release** APK and attaches it to a GitHub Release.
Obtainium can follow those releases directly.

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
