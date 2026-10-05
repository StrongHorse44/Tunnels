# Tunnels

An offline Android app for GrapheneOS that digs into layers of the phone most people never see. Each
layer is a *tunnel*; every security finding comes with an action. Home screen: a stairwell seen from above,
one lit ring per stratum with each tunnel a bead on its ring; the app grows darker the deeper you go.

**No network egress by design.** CI fails the build if any module declares a permission outside its own
`permissions.allow`, and `android.permission.INTERNET` is allowed in exactly three modules (Traffic, Home
network and the updater), used only during sessions you start. The built APK is audited again with `aapt2`.

## Tunnels

| Line | Tunnel | What it does |
| --- | --- | --- |
| Files | Installer | Inspect an APK or bundle before installing: signer vs installed copy, ABI, SDK levels, real failure reasons |
| Files | Unzip | zip (incl. password), 7z, tar, tar.gz/xz/bz2, gz, xz, bz2; zip-slip and archive-bomb guards |
| Files | Backups | Watches the folder you keep your exports in: reads only each bundle's plaintext header (never a passphrase) to find when each app's newest export was made, flags one that is older than your limit or gone, with "Open <app>"; reminds you to do a restore drill every 90 days. See [docs/BACKUPS.md](docs/BACKUPS.md) |
| Inspect | Permissions | Declared vs granted per app, GrapheneOS Network/Sensors toggles, risky combinations, changes since last scan |
| Inspect | APK excavation | 167-SDK curated tracker catalog matched in each app's dex, signing certs with lineage, installer, native ABIs |
| Inspect | Doors | Exported components, unprotected exports, link and share handlers |
| Inspect | Hardening audit | ELF parser: PIE, NX, RELRO, BIND_NOW, stack canaries, FORTIFY on every native library |
| System | System packages | Knowledge base of AOSP/GrapheneOS packages, unknown packages, enabled-state changes, OTA detection |
| System | Trust store | System vs user CAs, baseline on first scan, user-CA and distrusted-root findings |
| System | Silicon | Hardware key attestation (StrongBox first): verified boot state and key, patch levels, chain check |
| System | Deep mode | Via Shizuku: app-ops history (camera, mic, location, clipboard), hidden settings, direct revoke/disable. GrapheneOS posture: auto reboot, USB-C, VPN lockdown, Private DNS, PIN scrambling, auto-off, clipboard, Sensors default (the USB-C port mode is a reminder: no app can read it on this build) |
| Activity | Timeline | Usage and data per app over 30 days (Usage access), unused apps, background data |
| Activity | Notifications | Counts and flags per app from a notification listener (never contents), noise and lock-screen exposure |
| Network | Traffic | DNS-only VPN sessions you start: registrable domains per app, tracker domains, optional blocking of tracker lookups (answered "no such domain" on the phone); refuses while another VPN is up |
| Network | Surroundings | BLE trackers (AirTag, SmartTag, Tile, FMDN) judged per identity, including whether one travelled with you between places (a move counter, never a position); Wi-Fi security and evil twins, cell downgrade; cell logbook: an unfamiliar tower at a familiar place, keyed hashes only (off until you start it); optional background monitor |
| Network | Home network | Own-network gate, mDNS/SSDP discovery, TCP port scan, UPnP IGD and DNS-hijack checks, and a device census per confirmed network: you accept the devices you know once, and any other device that announces itself raises one finding (devices that announce nothing are invisible to it) |
| Explore | Sensors, Cameras, Satellites, Radio | Curiosity only: hardware facts, no findings |
| Central | Crossroads | Findings that take two tunnels to see: an accessibility service in an app from a file, ad or location SDKs in an app holding your location or contacts, a microphone used while the app sat unopened, a new signing key plus new permissions |

**Findings** (home → findings ›): every open finding from every tunnel in one list, most severe first, each with
its actions, new ones marked since your last visit. It also holds the opt-in **background checks**: a scheduled,
offline re-check of Permissions, Trust store, System packages, Silicon and Backups (plus APK excavation, Doors and Hardening
when apps changed, after a restart, or once a day) that stores a snapshot only when something changed and can notify
you about new findings. The notification names tunnels and kinds, never apps; the lock screen shows only "New
findings to review".

**Device checks** (home → checks ›): confirms on the phone the readings Tunnels relies on (the GrapheneOS Network and
Sensors toggles, verified boot, the store key, package visibility, background-check health, Private DNS and VPN state
for Traffic, every special access), with the checks only a person with a tracker tag can make.

Plus a **Snapshots** screen: on-demand full snapshot of every tunnel, history with pinning, a diff viewer
between any two snapshots, passphrase-encrypted export/import, and the optional app lock. An export is one `.fwx`
file (the program's FWX v1 container: PBKDF2-HMAC-SHA256, AES-256-GCM) that holds the snapshots, the settings you
chose (Traffic blocking and resolver, background checks), the second phones you paired and the Wi-Fi networks you
confirmed; it is written and read back through the system file picker with no permission, and an import verifies the
whole file before it changes anything. What is in it, what an import does and how to check one on the phone:
[docs/EXPORT.md](docs/EXPORT.md). An export from an earlier version (`.tsnap`) still imports.

And **in-app updates** (home screen → update ›): when you tap Check, Tunnels asks this repository's GitHub
releases for a newer build (release builds follow the vX.Y.Z tags; an old debug install follows the retired
"Debug build #N" tags and sees nothing new), downloads it, checks that it is the same app, newer, signed
with the same key and matching the published SHA-256, and hands it to Android's installer. The repository is
public, so no token is needed; an optional read-only token (a higher GitHub request limit) is kept in the
encrypted store.

## Principles

Offline by default; nothing leaves the device; summaries, not raw data; 30-day events and 12 snapshots
plus pinned; SQLCipher with a Keystore-wrapped key (StrongBox when available); permissions requested only
when a tunnel that needs them is opened; every security finding has an action; open source so the
no-egress claim can be verified. See [docs/HANDOFF.md](docs/HANDOFF.md).

## Building

- One release channel ([docs/RELEASING.md](docs/RELEASING.md)). A `vX.Y.Z` release (package `...tunnels`, signed
  with the owner's release key) starts when a pull request that changes the `VERSION` file is merged into the
  integration branch, and is published after the owner approves it. CI builds and gates the debug package
  (`...tunnels.debug`, the public debug key) on every run but publishes nothing; the "Debug build #N" prereleases
  are retired.
- Android modules compile in GitHub Actions; the plain-Kotlin `core/*` modules also build and test
  locally with `scripts/jvm-check/run.sh :core:NAME:test`.
- Layout, CI and conventions: [docs/BUILD_PLAN.md](docs/BUILD_PLAN.md).

## Third-party data

- `tunnels/traffic/src/main/assets/blocklists/adaway.txt`: the AdAway default hosts list
  ([github.com/AdAway/adaway.github.io](https://github.com/AdAway/adaway.github.io)) by the AdAway contributors,
  licensed under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). Reduced to hostnames, otherwise
  unchanged; refreshed before releases with `scripts/update-blocklists.sh`. The app never downloads it.
