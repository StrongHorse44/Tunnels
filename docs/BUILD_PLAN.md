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
| — | `tunnels:updater` (INTERNET allowed: user-started update checks) | `core:updates` | — |
| — | `tunnels:crossroads` (derived: joins other tunnels, no system reads) | `core:crossrules` | crossroads |
| — | `tunnels:watch` (findings inbox, background checks, notification) | `core:watchrules` | — |
| — | `tunnels:devicecheck` (device checks screen) | `core:devicecheck` | — |
| — | `tunnels:pairing` (Second phone: QR attestation between two phones, CAMERA) | `core:pairing` | — |
| — | `app` (well home) | `core:metro` (well geometry, depth palette, map layout) | — |

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
  snapshot, diffs against the previous one, derives findings through the rules, applies retention. The
  planning is `ScanPlanner`'s (core:engine, plain Kotlin, unit-tested).
- `DerivedTunnel` (core:model): a tunnel computed from other tunnels' latest observations and open findings
  (Crossroads). The engine runs it after its sources, in the same snapshot, whenever a source was scanned.
- `TunnelModule.volatileKeys`: keys that move with the clock alone (Silicon's patch age). A background check
  (`scan(storeIfUnchanged = false)`) stores no snapshot when only these changed.
- Settings the user chooses (blocking, background checks, the inbox's last visit) live in the encrypted store's
  `settings` table (`TunnelsStore.setting`/`putSetting`), schema 3 with a migration from 2.

## Permissions

Each Android module has a `permissions.allow` file: the permissions its manifest may declare, one
per line. `./gradlew verifyPermissions` fails if a module declares anything else, if INTERNET
appears outside `tunnels/traffic`, `tunnels/homenet` and `tunnels/updater`, or if the app's merged
manifest contains a permission no module allows. `scripts/check-apk-permissions.sh` repeats the check
on the APK.

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
- `ci.yml`: integration, `main`, PRs, and manual runs (`workflow_dispatch`) on any branch. Full build,
  all unit tests, permission checks, the release gate (`gate/`), app emulator smoke test, and a numbered
  debug release on integration pushes only; pull requests and manual runs never publish.
- `release.yml`: `v*` tags; see `docs/RELEASING.md`. `workflow-lint.yml`: zizmor on `.github/**` changes.
- Every Gradle job runs with `--dependency-verification=strict` against `gradle/verification-metadata.xml`;
  every action is pinned to a commit SHA (`gate/README.md`).

## Status (2026-10-01)

All phases are implemented and merged on the integration branch; every module has unit tests and an
instrumented smoke test that ran on an API 36 emulator. Nothing has been verified on the Pixel 10 yet:
each module's report lists the device checks that matter, collected in the pull request description.

## Status (2026-10-02): hardening build-out

Added on `ccr-98346e16-n3l9iz`: Crossroads (cross-tunnel findings), the findings inbox, background checks with
notifications, device checks, and tracker blocking in Traffic sessions. GitHub Actions did not start any job for
this branch that day (every run failed before a runner was assigned), so none of this has been through CI or an
emulator yet. The plain-Kotlin modules' tests pass locally, and the Android sources and instrumented tests were
typechecked against Robolectric's android-all and JetBrains' desktop Compose with stubs for the Android-only
AndroidX APIs; Room's KSP step, AGP and resources were not exercised. First CI run to watch: the 2 to 3 store
migration (`StoreMigrationTest`) and the new modules' instrumented tests in `ci.yml`'s smoke job.

Device checks to make on the Pixel 10 (the Device checks screen walks through most of them):
- The Network and Sensors toggle checks pass (turn one app's toggle off first).
- A background check runs on schedule with the phone idle; Device checks reports the last one. If GrapheneOS defers
  it, battery use Unrestricted should fix it.
- Blocking: with Private DNS on Automatic, a session's tracker lookups get NXDOMAIN, the app still works, and the
  counts show blocked lookups. With Private DNS set to a host, the panel warns and nothing is seen.
- Crossroads: a sideloaded app with an accessibility service on raises the critical finding; turning the service off
  clears it on the next scan.

## Follow-ups (deferred, not blockers)

- Updates: when the repository goes public the token becomes optional (the API answers without one, at 60
  requests an hour per address). Debug releases now carry `tunnels-debug-N.apk.sha256` for the updater.
- Surroundings places: verify on device how quickly a fix arrives indoors on GrapheneOS (fused provider,
  network location off by default) and how often scans end with "no fix"; those scans fall back to time.
- Restricted settings: verify the Timeline and Notifications gates on GrapheneOS after "Allow restricted
  settings", and that the direct screens (Tunnels' own Usage access and listener pages) open.

- Surroundings (v3, per-identity following): verify on device how often non-Apple tags (SmartTag,
  Tile, Chipolo, Find Hub) rotate their key while separated. Per-key following only catches a tag whose
  key stays stable for 30+ min; ROTATING_TRACKER is a NOTICE backstop until this is verified. Also
  verify DULT (Query tag / Play sound) and the AirTag short-frame battery/kind read with a real tag.
- Surroundings: mute rows live in their own events stream (`surroundings.mutes`) because TunnelsDao has
  no kind filter; add `eventsOfKinds(tunnelId, kinds, limit)` and fold them back. The open twin of an
  open hotspot SSID (xfinitywifi, attwifi) cannot be detected by security type. CELL_DOWNGRADED only
  fires on a snapshot-to-snapshot change; add a change rule on cell:downgradesRecorded for the monitor.
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
