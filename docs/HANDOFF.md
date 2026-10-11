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
> **Device census (2026-10, B08):** Home network keeps a list of the devices you accepted on each confirmed Wi-Fi ("These are
> all mine" once, then "Mine" per device) and raises one `UNKNOWN_DEVICE` finding for any other device; `NEW_HOST` is retired.
> A device is recognised by identity tokens (salted hashes of its SSDP UUID or mDNS instance names, or a shape of vendor,
> model and services; never its address or open ports), kept as summaries in the scan's observations and carried from scan to
> scan; acknowledgements are events that expire at 30 days, and Tunnels keeps one Home-network-only snapshot per network
> pinned so the list outlives snapshot retention (it never pins a snapshot that holds other tunnels' data, and it also moves or removes the pin on a Home-network-only snapshot of that network that was pinned by hand). Devices that announce nothing on mDNS or SSDP are invisible to it (no
> address sweep, OQ67). It also closes the B03 follow-ups: the gateway is recorded before discovery and outside the 50-host
> cap, and link-local-only mDNS services have their own count. No new permission, host or dependency. See
> `core/lan` `DeviceCensus`, `DeviceIdentity` and `CensusPins`.
> **Update (2026-10-01):** in-app updates from this repository's GitHub releases (`tunnels/updater`, INTERNET
> approved for user-started checks and downloads only); Surroundings judges following by whether a tag was seen
> on both sides of a move (a move counter and a keyed hash of a ~250 m grid cell, never a position); the
> Timeline and Notifications gates explain Android's restricted settings.
> **Cell logbook (2026-10, B10):** Surroundings can keep, per place, keyed hashes of the cells, tracking areas and operators the
> phone used there (the Q6 exception to rules 3 and 4: no coordinate, raw cell identity, timestamp or order; 64 places, capped
> sets, until cleared). A never-seen cell at a place scanned four times or more, with a second signal, is a NOTICE worded as a
> possible network change. Off until RJ taps "Start the cell logbook"; manual scans only; not exported; no new permission.
> **Wi-Fi over time (2026-10-10):** Surroundings now remembers Wi-Fi access points the way it remembers tracker identities:
> one row per Wi-Fi scan (manual and the monitor's ten-minute scans) in its own events stream, each access point as a keyed hash
> of its address (the Surroundings keystore key) with the name it broadcast, plus the scan's time and place number, kept 30 days
> (RJ's second exception to rule 3). Every access point seen in 5 scans over 30 minutes (RJ sets the number, 2 to 50) is a
> notice wherever it is; one also seen on both sides of a real move (a place change where most of the access points around
> changed too, so a wandering fix at home is not a move) is a warning; one device broadcasting 5+ names with 3+ open is a
> warning (8+ names a notice). Each has a 30-day mute and "Mute all" for the notices. Android shows apps only access points:
> a phone or camera that only joins a network is invisible to it.
> **Bluetooth over time, camera and bug hints (2026-10-11):** RJ asked whether listening devices would be caught. Surroundings
> now keeps fixed-address Bluetooth devices (speakers, TVs, cameras, gadgets; never a tracker, never a changing address, which
> phones, earbuds and watches use) the way it keeps Wi-Fi access points: one row per scan that listened to every advertiser
> (manual scans, and the monitor's passes while the screen is on, at most every ten minutes, because Android pauses an unfiltered
> scan with the screen off) in its own events stream, each device as a keyed hash with its advertised name and hint id, 30 days
> (RJ's third exception to rule 3). Same threshold and levels as Wi-Fi. A small catalog of camera, microphone and spy words in
> names ("cam", "camera", "recorder", "spy", "hidden"...) and camera makers' Bluetooth company ids (GoPro) gives hints: a hinted
> device or access point in range now is a notice at once, a hinted one that keeps turning up is a warning; "Mute all" leaves
> hinted ones out. Bluetooth findings offer Find it (find-it mode follows one device by its keyed id). A card in the tunnel says
> plainly what no phone can hear: SIM-card bugs, analog transmitters and recorders that send nothing.
> **Look closer (2026-10-11):** RJ found the device list limiting and chose, of all suggestions, a detail per device first.
> Tapping a device opens what the scan saw (including which port made the sweep find it) and **Look closer**, which asks
> that one device directly, only on the confirmed network and inside it, every socket bound to it: the catalog's ports plus
> 15 telling extras (iPhone sync 62078, AirPlay 7000, Google Cast 8009, Sonos 1400, ADB 5555, camera protocols 34567 and
> 37777...), a plain GET of `/` for the page title, server and login realm, a TLS handshake broken off once the
> certificate's names are read, the SSH, RTSP and login greetings, a NetBIOS name query, a direct mDNS question and the
> router's name for it, and one ping for the TTL's system hint. Nothing past a greeting is sent. Only a summary is kept
> (kind, maker, a few words, open ports, names, system hint; 30 days, its own events stream); the device list places an
> unidentified device by it at the next scan. Also: open its web page, open the router admin, and steps to track it down.
> A device that refuses (nearly) every door, gives no name, announces nothing and pings like Unix is said to be "a quiet
> device: a phone, tablet, e-reader, game console or cloud-only gadget", as a guess with why and the router-list check;
> the list keeps it under "Not identified" (RJ, 2026-10-11: first "likely Android", broadened once RJ's home, with no
> other Android device, showed it can be wrong). RJ then confirmed .183 is a Chromebook server: ChromeOS refuses every
> door unless its Linux apps' ports are forwarded and answers neither name question, so the guess names "a Chromebook or
> Linux computer" too.
> **Mine by address (2026-10-11):** a quiet device (no other token, announced nothing, gave no name, opened none of the
> scanned doors) on a scan whose discovery, port and network stages were complete, and that the port scan tried, gets
> an `a` census token: a salted hash of "quiet" and its address. Mine (by address), in its detail, on its row and on its
> finding, puts it on the list; it stays listed while a quiet device is at that address, and a device that announces
> itself or opens a door there is judged again (RJ: address + still quiet, everywhere Mine is). Another quiet device at the
> same address would pass, so the screen asks to reserve the address in the router. Notes kept by address move to it.
> **Apple discovery (2026-10-11):** RJ's iPhone, MacBook and Apple Watch never showed up: the scan listened for no Apple
> announcement but AirPlay and file sharing. It now also browses `_companion-link._tcp` (the device's own name, kept),
> `_apple-mobdev2._tcp` (iPhones and iPads; its instance name holds the Wi-Fi hardware address and is never kept) and
> `_rdlink._tcp` (name not kept); no name holding a hardware address is kept from any type (`MdnsTypes.nameToKeep`).
> Discovery's budget grew from 28 to 34 seconds for the fifth batch. A sleeping iPhone or a closed MacBook still says
> nothing, and a Watch is usually on Bluetooth to its iPhone rather than on Wi-Fi.
> **Device notes (2026-10-11):** the second of the three. Each device's detail now also holds your notes for it on that network:
> a name you give it (it titles the device in the list and in Watch findings), how often it is here as counts only (first and
> last day, days and scans seen, and a word: new today, always here, here most days, comes and goes, seen on one day only;
> no timeline, RJ's choice), **Watch** and **Mine**. A watched device raises a sticky notice at the scan that sees it come
> back or open or close a port or change its name ("Stop watching" is its action; gone is not news, the list shows that).
> News comes only with the user's scans: background checks never touch the network. The notes are settings rows in the
> encrypted store (`homenet.presence.<tag>`, `homenet.labels.<tag>`, `homenet.watch`); counts of a device not seen for 30
> days are forgotten, names and the watch list last until cleared, Forget on the network clears them all, and they stay on
> the phone (exports do not carry them). Notes are kept under the device's census token, or its address when it gave
> nothing to recognise it by. A device the user looked closer at that answered with a name is asked for it again at each
> scan (a NetBIOS query and a direct mDNS question, one packet each, to that device only); the answer becomes a census token
> (`c`), so it can go on the list with Mine, and notes kept under its address move to it.
> **Ports opened to the internet (2026-10-11):** the third of the three. When the router offers UPnP port mapping, each scan
> asks it (the gateway only, at the control address its own description gives) for its list of openings, one entry per
> question, at most 64 (RJ: every scan). Each enabled opening is a state finding naming the device and port: a warning for
> remote logins, file sharing, Android debug and camera or recorder protocols, or any opening to a camera; a notice
> otherwise (RJ: all, risky louder). Its actions are **Close it**, which asks the router over UPnP to remove that one
> opening (a second tap within a minute confirms in the inbox; the router card asks in a dialog), the steps to remove it
> and switch UPnP off, and the router admin page (RJ: both). It is the first time Tunnels changes something on the
> router; it sends only to the gateway's own service from the last scan of the network the phone is on, for an opening
> that scan listed. Risky doors Look closer found among the ports only it tries (ADB 5555, telnet 2323, DVR 34567) now
> raise the same risky-service warning, for as long as the look is kept (RJ: yes, as warnings).
> **Address sweep (2026-10-10):** Home network can sweep every address of the confirmed network around the phone (the /24, up to
> 254) for devices that announce nothing: a TCP connection to a few common ports that is accepted or refused means a device is
> there. The router (the gateway only) is then asked for each device's name by reverse DNS, which often returns the name the
> device gave it when it joined; that name becomes a census token (`h`), so the device can be put on the list. One the router
> does not name is listed by its address and raises one finding per device, as before (RJ's choice). Off until switched on in
> Home network; runs only during a scan the user starts, only on a confirmed network, every probe inside it.
> **Breach list for Linx (2026-10, B11-T):** a fourth network module, `tunnels/breaches` (rule 1, RJ's Q7): on a tap, "breaches ›"
> on the home console downloads the public Have I Been Pwned breach list (one HTTPS request to the one host, a User-Agent and no
> account, address, domain or key; CC BY 4.0), reduces it to names, titles, domains, dates, counts, flags and data classes, holds
> it in memory only (ten minutes, or three hand-overs) and hands it to Linx on the same phone through a `content://` address
> served from memory, with an explicit-package send. No file is written anywhere (rules 2, 3 and 5 are untouched). One new
> component (the share provider, not exported), no new permission at APK level, no new dependency. See [BREACHES.md](BREACHES.md).

> **Emissions (2026-10-10):** a fourth Network tunnel for what the phone itself transmits: cellular, Wi-Fi and its scanning,
> Bluetooth and its scanning, NFC, UWB and the hotspot, with a quiet level and what full silence takes (power off and a tested
> Faraday bag; airplane mode is a request to the modem, not proof). With Shizuku, Silence turns everything off in one tap and
> Restore brings back what it turned off; without it each transmitter opens Android's own panel. Findings for transmitters left
> on where they are easy to forget (airplane mode with Wi-Fi or Bluetooth still on, scanning while its radio is off, NFC, UWB,
> a hotspot), each with "Turn off" and its Settings screen. Apps that used a radio in the last 15 minutes are shown, never
> stored. A "Silence" Quick Settings tile and a Faraday bag test. No new permission at APK level; one new service (the tile).
> See [EMISSIONS.md](EMISSIONS.md).

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
3. Store summaries, not raw data. "App X contacted 14 domains today," not packet captures. "Tracker seen at 3 places," not a GPS trail. Exception (Q6, OQ74): the cell logbook's keyed hashed sets per place, with a best-radio-generation digit and a key fingerprint (CLAUDE.md rule 3). Second exception (RJ, 2026-10-10): Surroundings' Wi-Fi scans for 30 days, each access point as a keyed hash with its broadcast name (CLAUDE.md rule 3). Third exception (RJ, 2026-10-11): Surroundings' Bluetooth scans that listened to every advertiser, for 30 days, each fixed-address device as a keyed hash with its advertised name and hint id (CLAUDE.md rule 3).
4. Short retention. Events expire after 30 days. Keep the last 12 snapshots plus pinned ones. Exception (Q6, OQ74): the cell logbook's hashed sets, digit and fingerprint last until cleared, capped in code.
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
