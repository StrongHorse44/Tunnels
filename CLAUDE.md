# CLAUDE.md — Tunnels

Native Android (Kotlin + Jetpack Compose) offline phone inspector for GrapheneOS.
Full spec: `docs/HANDOFF.md`. Read it before starting work.

## Hard rules (never violate)

1. **No network egress, except three modules.** Only `tunnels/traffic` and `tunnels/homenet`
   (approved 2026-10-01 for user-started sessions) and `tunnels/updater` (approved 2026-10-01
   for user-started update checks and downloads from the project's own GitHub releases, nothing
   else) may declare `android.permission.INTERNET`. Every other module's manifest is checked
   against its `permissions.allow` file and CI fails the build on any permission not listed
   there. Do not add dependencies that open sockets outside those three modules, and never work
   around a missing permission. The GrapheneOS Network toggle stays off outside a session.
2. **Nothing leaves the device.** No analytics, crash reporting, telemetry,
   cloud sync, ads, remote config, or any SDK that phones home (no Firebase,
   Play Services, Sentry, Crashlytics, etc.). Exports are manual, user-initiated,
   encrypted files only.
3. **Store summaries, not raw data.** Aggregate counts and facts ("App X contacted
   14 domains today"), never packet captures, full traffic, or location trails.
   One exception, hashed sets (RJ, Q6, 2026-10-03): the Surroundings cell logbook may keep,
   per place, a keyed hash of the place's ~250 m grid cell with sets of keyed hashes of the
   serving cells, tracking areas and operators the phone used there, and a capped scan count.
   The key is an HMAC key in the Android keystore that never leaves it. Never a coordinate,
   a raw cell identity, a timestamp or an order of visits.
4. **Short retention.** Events expire after 30 days. Keep the last 12 snapshots
   plus pinned ones. Enforce in code, not by convention. The hashed sets allowed under
   rule 3 are the one exception: they last until the user clears them, and their size is
   capped in code (64 places; per place 48 cells, 16 tracking areas, 8 operators).
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
- `core:metro` home well geometry, depth palette, and the older metro map layout (plain Kotlin, unit-tested)
- `core:updates` + `tunnels:updater` in-app updates from GitHub releases (user-started; INTERNET allowed). Two
  channels, told apart by the package: `io.github.stronghorse44.tunnels.debug` (Debug build #N) and
  `io.github.stronghorse44.tunnels` (`vX.Y.Z`); the repo is public, so the update token is optional
- `core:crossrules` + `tunnels:crossroads` Crossroads, a derived tunnel (joins other tunnels' data; a bead in the well)
- `core:watchrules` + `tunnels:watch` findings inbox and opt-in background checks (JobScheduler, offline tunnels only)
- `core:devicecheck` + `tunnels:devicecheck` device checks: confirms on the phone the readings Tunnels relies on
- `core:pairing` + `tunnels:pairing` Second phone: one phone verifies another's hardware attestation through two QR codes (CAMERA, no network)
- `app` well home (the strata as rings of a stairwell, Prism well design); `verifyPermissions` runs before every assemble

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

## Build conventions (all agents)

- Read `docs/BUILD_PLAN.md` for the phase plan, module list and branch model.
- Google's Maven is unreachable from the dev container: Android modules compile only in
  GitHub Actions. Put every piece of Android-free logic (parsers, rules, matchers, models)
  in a plain-Kotlin module under `core/` with unit tests, and run them locally with the
  scratch project described in BUILD_PLAN. Android modules stay thin adapters.
- Work on your assigned branch (`phase/<n>-<name>`); push; read the `compile-check` run for
  that branch via the GitHub tools; fix; repeat until green. Never push to another branch.
  Put `[emulator]` in the message of your final commit so the phase emulator job runs once
  for your branch (the repo is public and unmetered, but emulator runs are slow; do not tag every push).
- Touch only the modules you own plus their `permissions.allow`. Shared files
  (`settings.gradle.kts`, `app/`, `core/common`, `core/runtime`, `core/model`, the catalog,
  CI, the workflows and `gate/`) are owned by the lead; ask instead of editing them. A build the
  lead assigns to those files (a release-channel or gate change) edits them with that assignment.
- A tunnel module registers itself by listing its `TunnelProvider` implementation in
  `src/main/resources/META-INF/services/io.github.stronghorse44.tunnels.runtime.TunnelProvider`.
- Every module ships unit tests (JVM) and at least one instrumented smoke test under
  `src/androidTest` that runs its `scan()` on the emulator.
- Observations are summaries (counts, names, hashes, booleans), never raw payloads.
- Every change goes up as a pull request from its own branch. Never push to the integration branch or
  `main` directly. Two release channels exist, and neither publishes without the owner's approval (a merge alone
  never publishes):
  - "Debug build #N" prereleases (`ci.yml`): only a push to the integration branch publishes one, and
    only after the release gate (`gate/baseline.json`, pinned to the committed public debug key), the
    emulator smoke job and the certificate check pass; the `publish` environment then waits for the
    owner's approval. Manual `ci.yml` runs build and test only and never publish; agents still don't
    start them by hand, the program overseer may on a reviewed branch.
  - `vX.Y.Z` releases (`release.yml`, since #15): started only by a manual run of `release.yml` with
    `publish` ticked, from the integration branch or `main`, signed in the `sign` job under the
    `signing` environment (the owner's release key, no reviewer; the environment admits only those two
    branches) and gated against `gate/baseline.release.json`; the `publish` job waits for the owner's
    approval and creates the tag. The owner starts publishing runs and approves them. The overseer may
    start a dry run (`publish` off) on a reviewed branch; it builds, gates and rehearses and publishes
    nothing. Agents never start a publishing run. Who can push to the two branches is the control on
    the key.
  Builder and reviewer agents never merge. The overseer may merge a PR only when review passed,
  CI is green and the PR has no gate change (no new or changed permission, network host, runtime
  dependency, native library, build type, signing config, release workflow or either baseline); a PR
  with a gate change waits for the owner to merge.

## Workflow

- Phases 1–6 are being built straight through on the integration branch, one PR at the end.
- Stop only for decisions that change scope or hard rules.
