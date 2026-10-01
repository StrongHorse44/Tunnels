# Tunnels

An offline Android app for GrapheneOS that digs into layers of the phone most people never see. Each
layer is a *tunnel*; every security finding comes with an action. Home screen: a console readout over a
clickable glass metro map, one line per tunnel group.

**No network egress by design.** CI fails the build if any module declares a permission outside its own
`permissions.allow`, and `android.permission.INTERNET` is allowed in exactly two modules (Traffic and
Home network), used only during sessions you start. The built APK is audited again with `aapt2`.

## Tunnels

| Line | Tunnel | What it does |
| --- | --- | --- |
| Files | Installer | Inspect an APK or bundle before installing: signer vs installed copy, ABI, SDK levels, real failure reasons |
| Files | Unzip | zip (incl. password), 7z, tar, tar.gz/xz/bz2, gz, xz, bz2; zip-slip and archive-bomb guards |
| Inspect | Permissions | Declared vs granted per app, GrapheneOS Network/Sensors toggles, risky combinations, changes since last scan |
| Inspect | APK excavation | 167-SDK curated tracker catalog matched in each app's dex, signing certs with lineage, installer, native ABIs |
| Inspect | Doors | Exported components, unprotected exports, link and share handlers |
| Inspect | Hardening audit | ELF parser: PIE, NX, RELRO, BIND_NOW, stack canaries, FORTIFY on every native library |
| System | System packages | Knowledge base of AOSP/GrapheneOS packages, unknown packages, enabled-state changes, OTA detection |
| System | Trust store | System vs user CAs, baseline on first scan, user-CA and distrusted-root findings |
| System | Silicon | Hardware key attestation (StrongBox first): verified boot state and key, patch levels, chain check |
| System | Deep mode | Via Shizuku: app-ops history (camera, mic, location, clipboard), hidden settings, direct revoke/disable |
| Activity | Timeline | Usage and data per app over 30 days (Usage access), unused apps, background data |
| Activity | Notifications | Counts and flags per app from a notification listener (never contents), noise and lock-screen exposure |
| Network | Traffic | DNS-only VPN sessions you start: registrable domains per app, tracker domains; refuses while another VPN is up |
| Network | Surroundings | BLE trackers (AirTag, SmartTag, Tile, FMDN), Wi-Fi security and evil twins, cell downgrade; optional background monitor |
| Network | Home network | Own-network gate, mDNS/SSDP discovery, TCP port scan, UPnP IGD and DNS-hijack checks |
| Explore | Sensors, Cameras, Satellites, Radio | Curiosity only: hardware facts, no findings |

Plus a **Snapshots** screen: on-demand full snapshot of every tunnel, history with pinning, a diff viewer
between any two snapshots, encrypted export/import (PBKDF2 + AES-GCM), and the optional app lock.

## Principles

Offline by default; nothing leaves the device; summaries, not raw data; 30-day events and 12 snapshots
plus pinned; SQLCipher with a Keystore-wrapped key (StrongBox when available); permissions requested only
when a tunnel that needs them is opened; every security finding has an action; open source so the
no-egress claim can be verified. See [docs/HANDOFF.md](docs/HANDOFF.md).

## Building

- Every push to the integration branch publishes a numbered debug build (`Debug build #N`) on the
  Releases page; `v*` tags produce signed releases ([docs/RELEASING.md](docs/RELEASING.md)).
- Android modules compile in GitHub Actions; the plain-Kotlin `core/*` modules also build and test
  locally with `scripts/jvm-check/run.sh :core:NAME:test`.
- Layout, CI and conventions: [docs/BUILD_PLAN.md](docs/BUILD_PLAN.md).
