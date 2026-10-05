# Tunnels - Claude Code Handoff

> **Scope update (2026-10-01):** Phase 0 now ships two working tunnels on top of the foundation:
> **Installer** (inspect + install APKs and .apks/.xapk/.apkm bundles via PackageInstaller sessions,
> with real failure reasons) and **Unzip** (zip incl. password-protected, 7z, tar, tar.gz/xz/bz2, gz, xz, bz2).
> Decisions: application ID `io.github.stronghorse44.tunnels`; minSdk 34 / target 36; strata home kept
> with the other tunnels as placeholders; foundation (SQLCipher store, snapshot/diff engine) built now.
> Dependencies added for Unzip: Apache Commons Compress + XZ for Java, zip4j (all Apache-2.0, no network).
> **Design update (2026-10-01):** the strata home is replaced by a **glass metro map** under a
> **console readout**. Tunnels are stations grouped into metro lines (Files, Inspect, System, Activity,
> Network, Explore), each line drawn as a colored liquid in its own glass tube; live stations glow,
> unbuilt ones are hollow on a dashed segment. Explore stays a separate curiosity-only line.
> **Design update (2026-10-02): Prism well.** The metro map is replaced. Home looks down a spiral stairwell:
> one ring per stratum (Surface magenta, Topsoil orange, Bedrock amber, Core green), each tunnel a bead on its
> ring, the open-findings count in a glory at the bottom; Explore is a dashed side shaft. The UI darkens with
> depth: home is dusk violet, a tunnel is a step darker (deeper strata darker still), a finding is darker again
> (`core:metro` `Depth`). A tunnel's header is its stratum's ring; a finding opens full screen with its subject
> in the glory; a running scan shows the glory and ripples on the water, counting the tunnel's items. New launcher
> icon to match. Home lists the groups as a depth gauge (plain names: Network, Apps, System, Deep checks, Explore;
> the strata stay internal to the code). Panels are Prism edge glass (deep violet, iridescent rim); Scan and
> other actions are dark buttons with a spectrum rim. Starting a scan drops the tunnel's bead from the header band
> into the panel, where the glory blooms and counts the tunnel's items.
> **Build decisions (2026-10-01, phases 1–6):** all six phases built straight through, each with its own
> CI (unit + emulator tests); tracker signatures from an own curated list; `main` created from Phase 0 and
> the single PR targets it; Traffic refuses to start while another VPN is active; Deep mode built fully
> against Shizuku; Surroundings gets an optional background monitor (foreground service, separate
> background-location opt-in). INTERNET approved for `tunnels/traffic` and `tunnels/homenet` only.
> **Status (2026-10-01):** phases 0-6 are built and merged; see README for the tunnel list and
> docs/BUILD_PLAN.md for follow-ups and device checks.
> RAR is not supported (no good open-source RAR5 decoder; unrar's licence is restrictive).
> **Hardening build-out (2026-10-02):** a findings inbox across tunnels; opt-in background checks (JobScheduler,
> offline tunnels only, a snapshot only when something changed, a notification that names tunnels and kinds, never
> apps); Crossroads, a derived tunnel for findings that need two tunnels (sideloaded accessibility service, data SDKs
> beside location or contacts, microphone use while unopened, new signer plus new permissions); a device checks
> screen for the "verify on device" items; and tracker blocking in Traffic sessions (NXDOMAIN answered on the
> phone, by category, per-app exemptions, off by default). No new permission outside the module allowlists; no INTERNET.
> **Export (2026-10, B05a):** snapshot export is now one FWX v1 file (`.fwx`, app ID `tunnels`, bundle schema 1) that also
> carries the settings, second-phone pins and confirmed network fingerprints; import verifies the whole file, stages it in
> memory and swaps it in with one transaction, and an old `TSNAPE1` export still imports. See [EXPORT.md](EXPORT.md).
> **Backups (2026-10, B06):** a tunnel that watches the folder RJ keeps his exports in (picked once with the system folder
> picker; a read-only tree grant, no permission). It reads only the plaintext header of each `.fwx` (and the old `.tsnap`)
> bundle, through the B05a codec copy in `core/export` and never past byte 202, finds when each app's newest bundle was made,
> and raises a finding when a watched app's newest bundle is older than the limit (default 30 days, RJ sets it) or gone,
> with "Open <app>" as its action; a restore drill date RJ sets raises a reminder after 90 days. It stores summaries only
> (app ID, newest time, file count), joins the opt-in background check, and asks for no passphrase. See [BACKUPS.md](BACKUPS.md).
> **Update (2026-10-01):** in-app updates from this repository's GitHub releases (`tunnels/updater`, INTERNET
> approved for user-started checks and downloads only); Surroundings judges following by whether a tag was seen
> on both sides of a move (a move counter and a keyed hash of a ~250 m grid cell, never a position); the
> Timeline and Notifications gates explain Android's restricted settings.

## Instructions for Claude Code
This is the full spec for Tunnels, a native Android app for my GrapheneOS Pixel 10.

Before writing code:
1. Save this spec as docs/HANDOFF.md.
2. Create CLAUDE.md with the hard rules from "Principles" and "Guardrails" so they persist across sessions.
3. Propose the Phase 0 plan: module layout, the TunnelModule interface, the Finding and Snapshot models, the diff engine, and the SQLCipher + Keystore setup. Wait for my approval.

Then build Phase 0 only:
- Kotlin, Jetpack Compose, Room + SQLCipher, no third-party SDKs that touch the network
- Merged manifest must declare no INTERNET permission; add a CI check that fails the build if it does
- GitHub Actions workflow that builds a signed release APK on tag
- Strata home screen (Surface, Topsoil, Bedrock, Core, plus Explore) with placeholder tunnels

Stop after Phase 0 so I can install and test on the device. Flag anything in this spec you think is wrong or not possible without root.

## Overview
Tunnels digs into layers of the phone and its surroundings most people never see. Each layer is a "tunnel": apps and permissions, embedded trackers, the trust store, the hardware's integrity report, network traffic, nearby radios, the home network.

Core rule: every finding comes with an action. Seeing is detection, acting is defense. A finding like "this app embeds Firebase Analytics" ships with buttons to revoke its Network permission, open its settings, or uninstall it. Curiosity-only layers (satellites, sensors, cameras) live in a separate Explore area and never pose as security findings.

v1 is the offline inspector: reads static phone state, diffs it over time via snapshots, declares no internet permission.

Design language: geological cross-section. Home screen is a stack of strata, surface to core. Drilling into a tunnel descends a level (app -> permissions -> network -> domains).
- Surface: Surroundings, Home network, Traffic
- Topsoil: Permissions, APK excavation, Doors, Notifications, Timeline
- Bedrock: System packages, Trust store, Hardening audit
- Core: Silicon (attestation), Deep mode
- Explore (side area): Satellites, Sensors, Cameras, Radio

## Principles (hard rules)
1. Offline by default. Phase 1 declares no INTERNET permission. Later, the GrapheneOS Network toggle stays off except during a session.
2. Nothing leaves the device. No analytics, crash reporting, cloud sync, or SDKs that phone home. Snapshot exports are manual, encrypted files.
3. Store summaries, not raw data. "App X contacted 14 domains today," not packet captures. "Tracker seen at 3 places," not a GPS trail.
4. Short retention. Events expire after 30 days. Keep the last 12 snapshots plus pinned ones.
5. Encrypted at rest. SQLCipher with key wrapped by Android Keystore (StrongBox when available). Optional app lock via device credentials.
6. Opt-in per tunnel. Fresh install asks for nothing. Permissions requested only when opening the tunnel that needs them, with a one-line reason.
8. Every security finding has an action. Findings without one go in Explore.
9. Open source, so the no-egress claim can be verified.

## Architecture
Native Kotlin + Compose. VpnService, BLE, notification listening, key attestation, and Shizuku are Android-native APIs.

Data flow:
Android system APIs -> Tunnel modules (one per tunnel) -> Encrypted store -> Snapshot engine + Findings engine -> Strata UI -> Actions (Settings deep links, revoke, uninstall) and Export/Import (encrypted snapshot file for Private Space)

Tunnel modules never talk to the UI directly. They write observations to the store; the engines turn those into findings.

```kotlin
interface TunnelModule {
    val id: String
    val stratum: Stratum            // SURFACE, TOPSOIL, BEDROCK, CORE, EXPLORE
    val requiredPermissions: List<PermissionSpec>
    suspend fun scan(): List<Observation>
    fun actionsFor(finding: Finding): List<FindingAction>
}

data class Finding(
    val tunnelId: String,
    val subject: String,            // package name, cert alias, device
    val kind: String,               // e.g. TRACKER_SDK, NEW_USER_CA, PERMISSION_GAINED
    val severity: Severity,         // INFO, NOTICE, WARN, CRITICAL
    val firstSeen: Instant,
    val lastSeen: Instant,
    val evidence: String,
    val actions: List<FindingAction>
)
```


Stack: Kotlin, Compose, coroutines, Room + SQLCipher. Minimal, offline dependencies.
One Gradle module per tunnel group once Phase 1 grows, so each tunnel's permissions stay visible in its own manifest.
GitHub Actions builds a signed release APK on tag.

## Tunnel catalog
"Verify" = confirm on-device before designing around it.

### Phase 1 (v1, offline)
- **Permissions:** declared vs granted per app, incl. GrapheneOS Network/Sensors toggles and Storage/Contact Scopes. PackageManager + requestedPermissionsFlags, QUERY_ALL_PACKAGES. Verify toggle states are readable. Action: open app details, revoke.
- **APK excavation:** embedded tracker SDKs, signing certs, install source, target SDK, native libs, diffs per update. Read sourceDir/splitSourceDirs, scan dex class prefixes against a bundled signature list; GET_SIGNING_CERTIFICATES, getInstallSourceInfo. Action: revoke Network, uninstall, flag cert change.
- **Hardening audit:** ELF parsing of lib/*.so for PIE, RELRO, BIND_NOW, stack canaries, non-exec stack, 64-bit-only. Action: deprioritize or replace weak apps.
- **Doors (IPC):** exported activities/services/receivers/providers, URL and share handlers. PackageManager component flags, queryIntentActivities. Action: open app details, change default handlers.
- **Trust store:** system and user CAs diffed against a baseline. KeyStore.getInstance("AndroidCAStore"). Action: remove user CA via security settings.
- **System packages:** every system package, disabled apps, what each does. Action: disable where safe, explain.
- **Silicon:** verified boot state and key, device locked, OS version, patch level, StrongBox. Keystore key attestation, parse OID 1.3.6.1.4.1.11129.2.1.17; compare boot key to GrapheneOS's published hash. Action: alert on mismatch.
- **Snapshots:** full state of every enabled tunnel, diffed over time and across OS updates. Changes surface as findings.

### Later phases
- **Phase 2:** Timeline (UsageStatsManager, NetworkStatsManager, PACKAGE_USAGE_STATS). Notifications (NotificationListenerService: frequency, lock-screen leaks, spoofed urgency).
- **Phase 3:** Traffic (VpnService, getConnectionOwnerUid, adds INTERNET). DNS logging per app first, then per-connection. Sessions only.
- **Phase 4:** Surroundings. BLE tracker detection, Wi-Fi security and evil twins, cell tower changes. BLUETOOTH_SCAN, NEARBY_WIFI_DEVICES, ACCESS_FINE_LOCATION; background location as separate opt-in. Satellites (Explore) rides along.
- **Phase 5:** Home network. Own-network gate, then LAN discovery (NsdManager, SSDP with MulticastLock), then TCP connect port scan, then router checks (UPnP, DNS rewriting).
- **Phase 6:** Deep mode via Shizuku. Sensor/mic/camera/clipboard access history (appops), hidden Settings keys (apps targeting API 31+ can't read them otherwise), plus the GrapheneOS posture of those keys (B07, `core:posture`): a fixed allowlist read from the same shell, each item good, weak, unknown or n/a, with a Settings deep link for each weak one. On RJ's build (step 0, 2026-10-05) the USB-C port mode has no readable property, so it is a reminder row like the duress PIN; its `getprop` read stays in `core:posture` behind `confirmed = false`, as do the Wi-Fi, Bluetooth and NFC auto-off timers (no key appeared).
- **Explore:** Satellites (GnssStatus, GNSS measurements), Sensors (SensorManager), Cameras (CameraCharacteristics). No actions, curiosity only.
- **Backlog:** NFC (display only, never stored), ultrasonic beacon listener, SDR (receive-only, public broadcasts), OBD-II, packet path, leak test, breach footprint.

## v1 acceptance criteria
- [ ] Merged manifest declares no INTERNET permission (checked in CI)
- [ ] Fresh install requests zero permissions until a tunnel needs one
- [ ] Strata home screen; each Phase 1 tunnel opens from it
- [ ] Every security finding has at least one working action
- [ ] On-demand snapshot; two snapshots produce a readable diff
- [ ] SQLCipher DB with Keystore-wrapped key; uninstall wipes everything
- [ ] Snapshots (and settings, paired phones, confirmed networks) export/import as one encrypted file (built, docs/EXPORT.md; tick after the on-phone check)
- [ ] Full scan of all apps runs off the main thread with progress, no ANR
- [ ] GitHub Actions builds a signed release APK on tag

## Build order
Each phase ends with a signed APK tested on the Pixel 10 before the next starts.

0. Foundation: repo, Gradle (Kotlin DSL, version catalog), Compose, strata home, SQLCipher + Keystore, Finding/Snapshot models, TunnelModule interface, diff engine, CI release build
1. Inspector: Permissions -> APK excavation -> Hardening -> Trust store -> Silicon -> Doors -> System packages, each wired into Snapshots. This is v1.
2. Activity: Timeline, Notifications
3. Traffic
4. Surroundings
5. Home network
6. Deep mode (Shizuku)
7. Backlog

## Open questions
- [ ] Application ID and repo name
- [ ] Surfshark vs Traffic: one VPN at a time. Pause Surfshark during sessions, or never run both?
- [ ] Are GrapheneOS Network/Sensors toggle states readable via requestedPermissionsFlags?
- [ ] Private Space vs secondary user on my build, and how a file moves between them
- [ ] Tracker signature source: Exodus Privacy's database, check license before bundling
- [ ] Distribution: GitHub Releases only, or Obtainium too? Where the signing key lives
- [ ] minSdk: set to what the Pixel 10 runs
