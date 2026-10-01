# Build plan: phases 1–6

Lead: the main session. Builders: one agent per module, each on a phase branch, CI as the compile
oracle. Integration branch: `ccr-dff99af5-ij1rle`. Final PR: integration → `main`.

## Branch model

| Branch | Purpose | CI |
| --- | --- | --- |
| `phase/<n>-<module>` | one agent's work on one module | `compile-check` (build + unit tests + permission checks, ~4 min) and that phase's `ci-phase-<n>` |
| integration | merged phases | full `ci` incl. emulator smoke, publishes numbered debug builds |
| `main` | Phase 0 now; receives the single PR | full `ci` |

## Modules

Android modules (compile in CI only) are thin; logic lives in plain-Kotlin `core/*` modules that
compile and test locally.

| Phase | Android module | Plain-Kotlin logic module | Tunnel ids |
| --- | --- | --- | --- |
| 0 | `tunnels:installer`, `tunnels:unzip` | `core:install`, `core:archive` | installer, unzip |
| 1 | `tunnels:permissions` | `core:permrules` | permissions |
| 1 | `tunnels:apk` | `core:trackers` | apk_excavation |
| 1 | `tunnels:hardening` | `core:elf` | hardening |
| 1 | `tunnels:doors` | — | doors |
| 1 | `tunnels:truststore` | `core:certs` | trust_store |
| 1 | `tunnels:syspackages` | `core:syspkg` (package knowledge base) | system_packages |
| 1 | `tunnels:silicon` | `core:attestation` | silicon |
| 1, 4 | `tunnels:explore` | — | sensors, cameras (1); satellites, radio (4) |
| 1 | `tunnels:snapshots` (snapshot list, pin, diff viewer, export/import, app lock) | `core:export` (file format, crypto) | — |
| 2 | `tunnels:timeline` | — | timeline |
| 2 | `tunnels:notifications` | `core:notifrules` | notifications |
| 3 | `tunnels:traffic` (INTERNET allowed) | `core:dns` | traffic |
| 4 | `tunnels:surroundings` | `core:ble` | surroundings |
| 5 | `tunnels:homenet` (INTERNET allowed) | `core:lan` | home_network |
| 6 | `tunnels:deepmode` | — | deep_mode |

Shared, lead-owned: `core:model`, `core:engine`, `core:store`, `core:common`, `core:runtime`, `app`.

## Runtime contract (core:runtime)

- `TunnelModule` (core:model): `id`, `line`, `requiredPermissions` (runtime permissions with a
  one-line reason), `specialAccess` (settings-granted access such as Usage Access, with a deep
  link), `suspend fun scan(progress): List<Observation>`, `rules: List<FindingRule>`,
  `actionsFor(draft)`.
- `TunnelProvider` (core:runtime): `fun create(context: Context): List<TunnelModule>`; discovered
  with `ServiceLoader`. Register it in
  `src/main/resources/META-INF/services/io.github.stronghorse44.tunnels.runtime.TunnelProvider`.
- `TunnelUi` (core:runtime): optional custom Compose content for a tunnel; without it the generic
  tunnel screen (scan button, observations by subject, findings with actions) is used.
- `SnapshotEngine` (core:runtime): runs scans off the main thread with progress, stores a
  snapshot, diffs against the previous one, derives findings through the rules, applies retention.

## Permissions

Each Android module has a `permissions.allow` file: the permissions its manifest may declare, one
per line. `./gradlew verifyPermissions` fails if a module declares anything else, if INTERNET
appears outside `tunnels/traffic` and `tunnels/homenet`, or if the app's merged manifest contains
a permission no module allows. `scripts/check-apk-permissions.sh` repeats the check on the APK.

## Local testing of plain-Kotlin modules

A scratch settings file under the scratchpad includes only `core:*` JVM modules against Maven
Central (see `scripts/jvm-check/`). `scripts/jvm-check/run.sh :core:elf:test` runs one module's
tests; Maven Central rate-limits (HTTP 429) are transient, retry.

## CI

- `compile-check.yml`: `phase/**` pushes. Fast.
- `ci-phase-<n>.yml`: that phase's branches, integration and main, path-filtered to its modules; unit
  tests always, emulator (`connectedDebugAndroidTest`) on integration/main and on a phase branch only
  when the commit message contains `[emulator]` (tag your final commit so the smoke test runs once).
  The repo is private, so Actions minutes are limited: do not tag every push. The phase workflows are
  path-filtered, so the `[emulator]` commit must change a file under your module (an empty commit
  does not trigger a run).
- `ci.yml`: integration, `main`, PRs. Full build, all unit tests, permission checks, app emulator
  smoke test, numbered debug release on integration pushes.

## Status (2026-10-01)

All phases are implemented and merged on the integration branch; every module has unit tests and an
instrumented smoke test that ran on an API 36 emulator. Nothing has been verified on the Pixel 10 yet:
each module's report lists the device checks that matter, collected in the pull request description.

## Follow-ups (deferred, not blockers)

- Surroundings: FollowingHeuristic aggregates by tracker type; require the same pseudonymous key across
  3+ sessions for CRITICAL and keep type-level aggregation for WARN. CELL_DOWNGRADED only fires on a
  snapshot-to-snapshot change; add a change rule on cell:downgradesRecorded for the background monitor.
- Notifications: a model-level OpenSettings variant carrying a package extra would let the engine own
  the per-app notification-settings intent (currently a Perform action).
- Deep mode: ShellRunner timeout kills only the shell, not grandchildren; a single dumpsys appops parse
  instead of one appops get per package.
- Silicon: KnownBootKeys and Google roots come from the Auditor source as of this build; refresh them
  with new Pixels and roots. The expired 2016 RSA root is still accepted (matches Auditor).
- Trust store: DistrustedRoots matches by subject only; add verified fingerprints.
- System packages: Pixel vendor/SoC packages (com.shannon.*, com.samsung.slsi.*, …) are unknown to the
  knowledge base and surface as UNKNOWN_SYSTEM_PACKAGE until added.
- Timeline: scans without usage access clear non-sticky data findings; consider holding them.
- Runtime: a shared "is system app" helper for the PackageManager tunnels; a hard cap on the generic
  observations list for tunnels with hundreds of subjects.
