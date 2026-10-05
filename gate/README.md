# Release gate

`baseline.json` records what the shipped APK can do. Tunnels ships its **debug** build today (package
`io.github.stronghorse44.tunnels.debug`, `debuggable: true`, signed with the public key committed in
`app/debug.keystore`), so that is the build recorded: its permissions (with `maxSdkVersion` and
`usesPermissionFlags`), components another app or the system can reach (exported ones, plus every service,
receiver and provider) with their intent-filter actions, categories and data, package queries, native
libraries and any code outside the main dex files, network API types, host and URL literals, runtime and
file dependencies, SDK levels, `debuggable`, backup and cleartext-traffic flags, the network security
config's trust anchors, cleartext rules and pins, and the signing certificate. The CI workflow builds the
debug APK, runs `gate.py check` against this file, and fails on any difference, before anything is
published. So a change to what Tunnels can do only gets in if the same pull request changes
`baseline.json`, where the owner sees it before merging. In a pull request the gate also lists, as
warnings, every field the pull request changes in `baseline.json` itself.

It sits next to the per-module `permissions.allow` files and the `verifyPermissions` task, which stay: a
new permission needs a line in the module's `permissions.allow` and in `permissions.requested` here.

**The certificate pin proves little while the key is public.** Anyone can sign an APK with
`app/debug.keystore`, so a match says nothing about who built the APK; the pin only catches an accidental
key change. A release key (kept in `publish` environment secrets, not in the repository) is what will make
the check meaningful; see `docs/RELEASING.md`. The `release-check` job runs `gate.py cert --require-pin`
before the `publish` job: it refuses to publish an APK that is unsigned or signed with a certificate other
than `signing.cert_sha256`.

Debug-specific content is recorded because it ships: `debuggable: true`, Compose's exported
`PreviewActivity` and the `.debug` provider authorities. `DebugProbesKt.bin` in `extra_code` is not
debug-only: it is a resource of kotlinx-coroutines-core-jvm 1.10.2 (not kotlinx-coroutines-debug). The
release build has minification off and no packaging exclude for it, so B02's release APK will carry it too
unless the build excludes it. `release.yml` checks a release build against `gate/baseline.release.json`,
which does not exist yet; the workflow stops at its first step until it does.

The full contract (fields, normalisation, exit codes) is `specs/gate-baseline.md` in the program repo.
`gate.py` uses only the Python standard library; in CI it cross-checks its manifest reading against
`aapt2 dump badging` and `aapt2 dump permissions`. `file-dependencies.init.gradle` lists file
dependencies and desugaring modules, which `gradle dependencies` doesn't show. `gate.py`, `test_gate.py`,
`annotate-file.sh`, `file-dependencies.init.gradle` and `testdata/` are copied byte for byte from Prikey's
`gate/`; a fix goes to Prikey first.

## When the gate fails

The log lists each difference as `GATE + field: value` (new in the APK), `GATE - field: value` (gone
from the APK) or `GATE ~ field: old -> new`, and then prints the whole baseline this APK would need
(also as `baseline-generated` notice annotations, see below). If the change is intended, replace
`baseline.json` with it in the pull request, give every new `hosts` or `network_api` entry a `note`
saying why it is there (an entry without a note fails the check), and list the change under GATE
CHANGES in the pull request description.

Most `hosts` entries are the tracker and public-suffix lists (`core/dns` `TrackerDomains.kt`,
`PublicSuffix.kt`), matched locally and never contacted. They have a note each, not a catch-all rule, so a
host that appears in the APK without being in a list has no note and fails the check until someone looks.
`notes` rules exist for the cases where many entries share one owner or library: `{"field": "hosts",
"suffix": "doubleclick.net", "note": "..."}` (a literal domain of two labels or more; it matches that
domain and its subdomains) or `{"field": "network_api", "prefix": "Lorg/bouncycastle/", "note": "..."}`
(a type package of two segments or more). Patterns are refused. Every network type's note names the module
and the toggle that gates it, except read-only state types (`ConnectivityManager`, `LinkProperties`,
`NetworkCapabilities`, `TransportInfo`, `WifiInfo`, `WifiManager`, `ScanResult`, `WifiSsid`), whose notes say
they are read-only state getters with no sockets and no egress, and library-only references (AndroidX,
Apache commons, zxing), whose notes say so. Hosts and network types must trace to `tunnels/traffic`, `tunnels/homenet` or
`tunnels/updater`, to a data set matched locally, or to a library string that is never contacted.

With the Android SDK and Google's Maven repository at hand, the same can be done locally:

```sh
./gradlew --init-script gate/file-dependencies.init.gradle -Pgate.configuration=debugRuntimeClasspath \
  assembleDebug :app:dependencies --configuration debugRuntimeClasspath gateFileDependencies > build/gradle.log
python3 gate/gate.py generate --apk app/build/outputs/apk/debug/app-debug.apk --deps build/gradle.log
```

`python3 gate/gate.py fingerprint --apk <published apk>` prints the signing certificate's SHA-256 from
the APK's signing block, for setting the pin where the SDK is missing. It reads one scheme (the highest
of v3.1, v3, v2) and each signer's first certificate, so it fits single-signer v2/v3 apps without key
rotation; the publish check's `gate.py cert`, which verifies with `apksigner`, is authoritative. Run the
script's own tests with `python3 -m unittest discover -s gate`.

## Dependency verification metadata

`gradle/verification-metadata.xml` pins the SHA-256 of every artifact Gradle downloads, and CI runs
Gradle with `--dependency-verification=strict`, so a swapped dependency or plugin fails the build before
it runs. It was written on Linux, so it pins the Linux `aapt2`; a local build on macOS or Windows needs
`--dependency-verification=lenient` (never in CI). After changing a dependency, delete the file in the
pull request: the next run writes a new one from the real build tasks (every Gradle job writes its own and
attaches it as `verification-metadata-<job>`), and fails. Commit the union of the files.

## Bringing a file back from CI without downloading artifacts

Agent sessions can't download artifacts, so CI also prints these files xz-compressed and
base64-encoded in numbered notice annotations titled `<label> <i>/<n> <sha256>`
(`gate/annotate-file.sh`): `verification-metadata-<job>` and `baseline-generated`. Read the check run's
annotations through the API, keep the chunks with the label and digest you want, check none is missing
or repeated, join them in order, decode, decompress, and compare the SHA-256 with the title.
