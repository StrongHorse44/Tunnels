# Tunnels - Claude Code Handoff

> **Note:** The spec as received ends partway through the Architecture section
> (after the `Finding` data class). Sections after that point (e.g. phases beyond
> Phase 0, and the "Guardrails" section referenced by the instructions) were not
> included. Append them here when available.

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

<!-- Spec truncated here as received. -->
