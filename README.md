# Tunnels

An offline Android app for GrapheneOS that digs into layers of the phone. **No internet permission**:
CI fails the build if one ever appears, both in the merged manifest and in the built APK.

Working now:

- **Installer**: open an APK or bundle (.apks, .xapk, .apkm). Before installing you see the version change,
  signing certificate (checked against the installed copy), processor support, SDK levels, permissions and
  test-only/debug flags. If Android refuses, you get the real reason instead of "App not installed".
- **Unzip**: zip (including password-protected), 7z, tar, tar.gz, tar.xz, tar.bz2, gz, xz, bz2. Browse first,
  extract all or some into a folder you pick. Blocks path-traversal entries, symlinks and archive bombs.
  APKs inside an archive can go straight to the installer.

Everything else on the home screen is a placeholder for later phases. See [docs/HANDOFF.md](docs/HANDOFF.md).

Data stays on the device in an SQLCipher database whose key is wrapped by the Android Keystore
(StrongBox when available). Backups are disabled; uninstalling erases everything.

Builds: every push produces a debug APK in GitHub Actions; `v*` tags produce signed releases.
See [docs/RELEASING.md](docs/RELEASING.md).
