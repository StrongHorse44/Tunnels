# CLAUDE.md — Tunnels

Native Android (Kotlin + Jetpack Compose) offline phone inspector for GrapheneOS.
Full spec: `docs/HANDOFF.md`. Read it before starting work.

## Hard rules (never violate)

1. **No network egress.** The merged manifest must never declare
   `android.permission.INTERNET`. CI fails the build if it does. Do not add
   dependencies that open sockets, and do not work around the missing permission.
   INTERNET arrives only in Phase 3 (Traffic), and only for user-started
   sessions; the GrapheneOS Network toggle stays off otherwise. Changing the CI
   check requires explicit approval.
2. **Nothing leaves the device.** No analytics, crash reporting, telemetry,
   cloud sync, ads, remote config, or any SDK that phones home (no Firebase,
   Play Services, Sentry, Crashlytics, etc.). Exports are manual, user-initiated,
   encrypted files only.
3. **Store summaries, not raw data.** Aggregate counts and facts ("App X contacted
   14 domains today"), never packet captures, full traffic, or location trails.
4. **Short retention.** Events expire after 30 days. Keep the last 12 snapshots
   plus pinned ones. Enforce in code, not by convention.
5. **Encrypted at rest.** All persistent data goes through Room + SQLCipher. The
   DB key is random and wrapped by an Android Keystore key (StrongBox when
   available). No plaintext DataStore/SharedPreferences for observations or
   findings. Optional app lock via device credentials.
6. **Opt-in per tunnel.** Fresh install requests no runtime permissions. Request a
   permission only when the user opens the tunnel that needs it, with a
   one-line reason.
7. **Every security finding has an action** (Settings deep link, revoke flow,
   uninstall, etc.). Anything without an action is not a finding: it lives in
   Explore and must not be styled as a security issue.
8. **Open source / verifiable.** Keep the build reproducible and dependencies
   minimal and auditable.

## Stack

Kotlin, Jetpack Compose, coroutines, Room + SQLCipher. Gradle Kotlin DSL with a
version catalog. Minimal, offline dependencies; no DI framework unless approved.
`android:allowBackup="false"` with backup/data-extraction rules excluding
everything, so uninstall wipes all data and nothing goes to cloud backup.

## Layout

- `core:model` tunnel catalog, Finding/Snapshot/Observation, `TunnelModule` (plain Kotlin)
- `core:engine` diff engine, findings merge, retention policy (plain Kotlin, unit-tested)
- `core:archive` archive readers with zip-slip and bomb guards (plain Kotlin, unit-tested)
- `core:install` pre-install checks, bundle split selection, failure explanations (plain Kotlin)
- `core:store` Room + SQLCipher, Keystore-wrapped key
- `core:common` theme, shared composables, private staging area for incoming files
- `tunnels:installer`, `tunnels:unzip` one module per tunnel group; each declares its own permissions
- `app` metro home (console readout + glass metro lines); `verifyNoInternet` Gradle task runs before every assemble

Keep Android-free logic in the plain Kotlin modules so it can be tested without an emulator.
Google's Maven is not reachable from the cloud dev container: Android modules only compile in CI.

## Architecture guardrails

- Tunnel modules never talk to the UI. They emit `Observation`s to the store;
  the Snapshot and Findings engines derive findings; the UI reads findings.
- One module per tunnel, implementing `TunnelModule`. Once Phase 1 grows, one
  Gradle module per tunnel group, so each group's permissions are declared in
  its own manifest.
- "Verify" in the spec means confirm on-device before designing around it.
  Don't build on an unverified assumption; stub it and flag it.
- Don't claim capabilities that need root. Prefer Settings deep links and
  system confirmation flows (e.g. uninstall intent) over pretending to act
  directly on other apps.

## Workflow

- Work phase by phase; stop at phase boundaries for on-device testing.
- Propose plans for new phases and wait for approval before building.
