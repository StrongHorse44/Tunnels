# Release gate

Two baselines record what each channel's APK can do. `baseline.json` is the **debug** build, published as
"Debug build #N" (package `io.github.stronghorse44.tunnels.debug`, `debuggable: true`, signed with the public key
committed in `app/debug.keystore`). `baseline.release.json` is the **release** build, published as `vX.Y.Z`
(package `io.github.stronghorse44.tunnels`, `debuggable: false`, no Compose `PreviewActivity`,
`releaseRuntimeClasspath`, signed with the owner's release key, whose certificate is pinned in
`signing.cert_sha256`). Each records permissions (with `maxSdkVersion` and `usesPermissionFlags`), components
another app or the system can reach (exported ones, plus every service, receiver and provider) with their
intent-filter actions, categories and data, package queries, native libraries and any code outside the main dex
files, network API types, host and URL literals, runtime and file dependencies, SDK levels, `debuggable`, backup
and cleartext-traffic flags, the network security config's trust anchors, cleartext rules and pins, and the
signing certificate. `ci.yml` builds both variants on every run, runs `gate.py check` on the debug APK against
`baseline.json` and on the unsigned release APK against `baseline.release.json`, and fails on any difference,
before anything is published; it fails closed if either file is missing. `release.yml` checks the release APK
against the release baseline again and the signed file's certificate against its pin. So a change to what
Tunnels can do only gets in if the same pull request changes the matching baseline, where the owner sees it
before merging. In a pull request the gate also lists, as warnings, every field the pull request changes in
either baseline.

It sits next to the per-module `permissions.allow` files and the `verifyPermissions` task, which stay: a
new permission needs a line in the module's `permissions.allow` and in `permissions.requested` here.

**The debug pin proves little; the release pin does not.** Anyone can sign an APK with `app/debug.keystore`,
so a match with `baseline.json`'s pin says nothing about who built the APK; it only catches an accidental key
change. The release key lives only in the `signing` environment's secrets, on the owner's phone and in the owner's own backups, so a match
with `baseline.release.json`'s pin shows the APK was signed with it. `release.yml`'s `release-check` job runs
`gate.py cert --baseline gate/baseline.release.json --require-pin` on the signed APK before the `publish` job
(`ci.yml`'s runs `gate.py cert --require-pin` against `baseline.json` for the debug build): either refuses an
APK that is unsigned or signed with another certificate. See `docs/RELEASING.md`.

The debug baseline records debug-specific content because it ships: `debuggable: true` and the `.debug`
provider authorities. Compose's `PreviewActivity` (from `ui-tooling`, exported by its library manifest) is
removed from the debug APK by `app/src/debug/AndroidManifest.xml`, so neither baseline has it.
`DebugProbesKt.bin` in `extra_code` is not debug-only: it is a resource of kotlinx-coroutines-core-jvm 1.10.2
(not kotlinx-coroutines-debug), and both builds carry it (minification is off and there is no packaging
exclude for it).

The full contract (fields, normalisation, exit codes) is `specs/gate-baseline.md` in the program repo.
`gate.py` uses only the Python standard library; in CI it cross-checks its manifest reading against
`aapt2 dump badging` and `aapt2 dump permissions`. `file-dependencies.init.gradle` lists file
dependencies and desugaring modules, which `gradle dependencies` doesn't show. `gate.py`, `test_gate.py`,
`annotate-file.sh`, `file-dependencies.init.gradle` and `testdata/` are copied byte for byte from Prikey's
`gate/`; a fix goes to Prikey first.

## When the gate fails

The log lists each difference as `GATE + field: value` (new in the APK), `GATE - field: value` (gone
from the APK) or `GATE ~ field: old -> new`, and then prints the whole baseline this APK would need
(also as `baseline-generated` notice annotations for the debug build and `baseline-release-generated` for the
release build, see below). If the change is intended, replace `baseline.json` or `baseline.release.json` with it in the pull request, give every new `hosts` or `network_api` entry a `note`
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

## Gate 2.1 (`python3 gate/gate.py --version`; the baseline schema is still 2)

`gate.py` 2.1 adds optional sections to both baselines. A baseline without them still loads and checks: a
missing section counts as empty, so it only differs from an APK that has entries (`GATE + uses_features: ...`),
and `generate` writes a section only when the APK has entries. Never land a new `gate.py` without both
regenerated baselines: CI is red until they match.

- `uses_features`: sorted `NAME;required=true|false` (`glEsVersion=0x30000;required=true` for a GL version,
  `NAME;version=0x400003;required=true` for a feature with a version). Tunnels records the pairing screen's
  optional camera.
- `meta_data`: sorted application-level `<meta-data>` as `NAME;value=TEXT` or `NAME;resource=TEXT`; a reference
  is resolved through `resources.arsc` (a file becomes `NAME;resource=sha256:DIGEST`, a framework resource
  `NAME;resource=android:0x...`, values that differ by configuration are joined with `|`). A reference that is a
  file in one configuration and a value in another, or that does not resolve, is exit 2. A component's own
  meta-data is not recorded. Tunnels records Shizuku's `moe.shizuku.client.V3_SUPPORT` marker.
- `provider_resources`: for every provider `<meta-data android:resource>` (FileProvider's paths file)
  `{provider, meta, sha256, elements}`, so a widened sharing boundary is a baseline change. Tunnels has none.
  The canonical form of the compiled XML is frozen: one JSON array per element or text node
  (`[DEPTH,"tag",[["key","value"],...]]`, attributes sorted by key; a key is `android:NAME` for an attribute whose
  resource ID the gate knows, whatever its namespace (a name that disagrees with its known ID is exit 2), and
  also for an attribute in the Android namespace with no known ID (exit 2 if its name is a known one with a
  missing or different ID); any other attribute is its plain `NAME` without a namespace and `{namespace URI}NAME`
  in any other namespace; a value is the raw string,
  else `true`/`false`, an unsigned integer, `null` for a reference, or `(type 0xTT)0xDDDDDDDD`). An element with
  two attributes that read as one key, a string attribute with no raw copy or with differing copies, and a
  reference that also carries a raw string are exit 2. `sha256` is the digest of the lines joined by newlines;
  configuration variants are separated by a `---` line.
- `assets`: `{path, sha256, size}` for top-level APK entries matching `scan.asset_digest_paths` (case-insensitive
  zip globs; a missing key means the defaults `*.tflite`, `*.onnx`, `*.ort`, `*.task`; `[]` turns it off; `null`
  is an error). Tunnels ships no model files and leaves the key out.
- `network_api` also records array descriptors (`[Landroid/net/Network;`), and `hosts` also records suffix
  literals with their leading dot (`*.compute.amazonaws.com` is `.compute.amazonaws.com`). Each needs a note
  like any other entry. A `hosts` rule on `example.com` covers `.example.com`; `amazonaws.com` cannot be a
  rule (patterns and public suffixes are refused), so those suffixes get a note each.
- `--compare-to` names the baseline file it was given in its "the base branch has no ..." message, so a
  missing `baseline.release.json` on the base branch is reported as that file.

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
