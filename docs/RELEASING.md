# Releasing Tunnels

This repository is **public**. Nothing secret is in it: the release signing key lives only in GitHub
environment secrets, on the owner's phone and in the owner's own backups, and every secret is read by exactly one
workflow job.

## Two channels, two packages

| | **Debug build #N** | **vX.Y.Z release** |
| --- | --- | --- |
| Package | `io.github.stronghorse44.tunnels.debug` | `io.github.stronghorse44.tunnels` |
| Version name | `0.1.0-dev.N-debug`, version code `N` (the CI run number) | `X.Y.Z`, version code `X*1000000 + Y*1000 + Z` |
| Signed with | the public key committed in `app/debug.keystore` (anyone can sign with it) | the owner's release key (never in the repository) |
| Published by | `ci.yml`, on every push to the integration branch, as a prerelease named **Debug build #N** (`debug-N`) | `release.yml`, by a manual run only, as the release **vX.Y.Z** |
| APK name | `tunnels-debug-N.apk` + `.sha256` | `tunnels-X.Y.Z.apk` + `.sha256` |
| Approval | the `publish` environment waits for the owner | the same `publish` environment waits for the owner |
| The in-app updater follows | `debug-N` prereleases only | `vX.Y.Z` releases only |

The two packages differ, so they **install side by side** and neither can update the other (they also have
different signing keys). Both show up as "Tunnels" in the launcher; tell them apart in App info (the package
name) or on the update screen (`installed v0.1.0-dev.93-debug` against `installed v0.1.0`). A release install
starts empty: it gets its data from a `.fwx` export of the debug install ([EXPORT.md](EXPORT.md)).

The updater tells the channels apart by the release **tag** (`debug-N` against `vX.Y.Z`) and the installed
package (a name ending `.debug` follows the debug channel); a release install never offers a debug build and a
debug install never offers a release (`core/updates` tests pin both).

## The signing key (one time, on the phone)

The key is made and kept on the phone. In Termux (`pkg install openjdk-17`), with JDK 17's `keytool`, which
writes a **PKCS12** keystore:

```sh
keytool -genkeypair -v -keystore tunnels-release.jks -storetype PKCS12 -alias tunnels \
  -keyalg RSA -keysize 4096 -sigalg SHA384withRSA -validity 10000
```

Choose one password for the keystore and the key (PKCS12 uses the same one for both, so the two secrets below
hold the same value). Then:

- **Back it up** before anything else: the keystore file and its password, in two places off the phone (an
  encrypted archive plus the password manager). **If the key is lost, releases can no longer update over an
  installed release**: every release install would have to be uninstalled and reinstalled from a new key, with
  an export and import in between.
- Note the certificate fingerprint, which is public:
  `keytool -list -v -keystore tunnels-release.jks -alias tunnels` (the `SHA256:` line). It is pinned in
  `gate/baseline.release.json` (`signing.cert_sha256`, lower-case hex without colons).
- Put the keystore in the `signing` environment (below). Do not paste it anywhere else, do not print the
  password or the base64 text into a chat or a log.

## The two environments

Set up in the repository's **Settings → Environments** (the mobile browser works; the GitHub app has no
environment settings). Each is limited to **Deployment branches and tags → Selected**:
- `signing`: branch `ccr-dff99af5-ij1rle` and branch `main` only. No tag pattern: `release.yml` no longer runs
  on tags, a tag can point at any commit with any workflow file, and with no reviewer on `signing` a tag policy
  would let a pushed tag reach the key.
- `publish`: the same two branches plus tag pattern `v*`, kept by the owner's choice (it has the required
  reviewer, so a tag run still waits for approval).

A run from any other ref cannot read either environment's secrets, whatever its workflow file says.

| Environment | Holds | Reviewers | Used by |
| --- | --- | --- | --- |
| `signing` | four secrets: `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` | none, by design | the `sign` job of `release.yml`, and nothing else |
| `publish` | no secrets | `StrongHorse44` (required) | the final job of `release.yml` and the publish job of `ci.yml`: the owner's approval |

**What actually protects the key.** `signing` has no reviewer, so that a release needs one approval (at
`publish`), not two. The control on the key is therefore **who can push to the integration branch and to
`main`**: a manual run with `publish` ticked from either branch signs the commit it was started on without
asking anyone. The workflow file on those branches is reviewed code, and the `sign` job runs no repository code;
nothing else can read the secrets. Also:

- Agents (Claude sessions) act through tokens tied to the owner's GitHub account, so "agents do not start
  release runs or approve them" is a **policy**, not a control. The program overseer starts dry runs
  (`publish` off) only; a publishing run and every approval are the owner's.
- A run with `publish` ticked produces a **release-signed APK as a run artifact (`signed-apk`) before anyone has
  approved anything**. Signed-in users with read access to the repository (it is public) can download
  artifacts, so the artifact is kept for 1 day only. Approve on the same day, or start the run again.

Secrets are **environment** secrets, not repository secrets. To add them from Termux without showing them
(`pkg install gh`, `gh auth login` once):

```sh
base64 -w0 tunnels-release.jks | gh secret set KEYSTORE_BASE64 --env signing --repo StrongHorse44/Tunnels
printf '%s' tunnels | gh secret set KEY_ALIAS --env signing --repo StrongHorse44/Tunnels
gh secret set KEYSTORE_PASSWORD --env signing --repo StrongHorse44/Tunnels   # prompts; same for KEY_PASSWORD
gh secret set KEY_PASSWORD --env signing --repo StrongHorse44/Tunnels
```

## Cutting a release from the phone

Pick the next version `vMAJOR.MINOR.PATCH` (each part 0 to 999, no leading zeros). It must be **higher** than
every earlier release; the run refuses a lower or equal one, and a version whose release or tag already exists
at another commit. Releases start from a **manual run only**; there is no tag trigger.

1. **Dry run first, whenever the workflow or the build changed.** github.com in the phone's browser → the
   repository → **Actions → Release → Run workflow**. Branch: any; `version`: `v0.1.0`; `publish`: **off**.
   It builds, runs the release gate and rehearses signing with a throwaway key, and publishes nothing; the
   `sign` job does not even start, so the key is not touched. (The program overseer starts dry runs.)
2. **The release.** The same form, branch `ccr-dff99af5-ij1rle` (or `main`), `version` `v0.1.0`, `publish`
   **ticked**. The run refuses any other branch. The workflow creates the tag and the release itself, at the
   commit it built. The GitHub app can show and approve runs but, as far as we know, cannot fill in the Run
   workflow form; use the browser.
3. **Wait for the build.** The build takes several minutes, then the signing job and the release check. If a
   check fails, the run stops before you are asked to approve anything.
4. **Approve, the same day.** The run then shows **Waiting for review** on the `publish` environment. In the
   GitHub app: the notification, or the repository → Actions → the run → **Review deployments → publish →
   Approve**. The signed APK the run holds expires after 1 day; if it did, start the run again.
5. The release **vX.Y.Z** appears on the Releases page with `tunnels-X.Y.Z.apk` and its `.sha256`.
   Install it: tap the APK in the browser or Obtainium, or use **update** inside an installed release.

Do not create the tag or the release by hand (**Releases → Draft a new release**): the workflow creates both,
and it stops if a release or tag for that version already exists at another commit. `release-check` looks at
both separately: an existing release must have been made for the built commit (its `target_commitish`), and an
existing tag ref (`git/ref/tags/<tag>`, an annotated tag followed to its commit) must point at the built commit,
whether or not a release exists. Only an HTTP 404 means "not there"; any other API error fails the job.
The `publish` job repeats the same checks after the approval wait, and resolves the tag ref the same way before
it replaces the APK of an existing release (`gh release upload --clobber`) or creates one: a release at the built
commit whose tag points elsewhere is refused, not updated.

### What the workflow does

`build` (no environment, no secrets) builds the release **unsigned**, runs the unit tests and the release gate,
and rehearses signing with a throwaway key; `sign` (environment `signing`, no checkout, no repository code)
checks the commit is on the integration branch or `main`, decodes the key into a private temporary file,
aligns (`zipalign -P 16`), signs with `apksigner` (v2 only, PKCS12) and verifies; `release-check` runs
`gate.py cert --require-pin` against the pinned release certificate and checks package, version name and
version code, and that no release or tag for this version exists at another commit; `publish` (environment
`publish`) checks the version order again with `gh` only (two approved runs cannot publish a lower version last),
attaches a build provenance attestation, and creates the tag and the release. Only the `sign` job references the
secrets, and only the `publish` job can write to the repository. `gate.py cert` and `aapt2` run from the pinned
build-tools (36.0.0), not the newest installed.

Version code: `MAJOR*1000000 + MINOR*1000 + PATCH`, so versions order the way their codes do (`v0.1.0` is
1000, `v1.2.3` is 1002003). The code is computed by the workflow from the `version` input, never typed.

## The gate and the release baseline

Two files record what each channel's APK can do: `gate/baseline.json` (the debug build) and
`gate/baseline.release.json` (the release build: not debuggable, no Compose preview activity, the release
package, `releaseRuntimeClasspath`, the release certificate pin). See `gate/README.md`. `ci.yml` builds both
variants on every pull request and push (the release one unsigned) and fails when either differs from its
baseline; `release.yml` checks the release again. A pull request that changes what Tunnels can do changes the
matching baseline and lists it under GATE CHANGES. The release baseline cannot be missing: both workflows fail
closed without it.

## Verifying a release

The `sign` job log prints the signing certificate; it must match the pin in `gate/baseline.release.json`.
From Termux (`pkg install gh`, `gh auth login`):

```sh
gh attestation verify tunnels-X.Y.Z.apk --repo StrongHorse44/Tunnels \
  --signer-workflow StrongHorse44/Tunnels/.github/workflows/release.yml
sha256sum -c tunnels-X.Y.Z.apk.sha256
```

For a debug build use `tunnels-debug-N.apk` and `--signer-workflow .../ci.yml --source-ref
refs/heads/ccr-dff99af5-ij1rle`. A debug attestation shows which workflow built an APK; it does not make the
public debug key private.

## Rolling back

Android does not install a lower version code over a higher one, and the workflow does not publish one. To
undo a bad release: delete or edit the release on GitHub if needed, fix the code, and release a **higher**
version. A release install can always go forward only.

## Updating from inside Tunnels

Home screen → **update ›**. A debug build follows the "Debug build #N" prereleases, a release build the
`vX.Y.Z` releases. Tapping **Check** asks `api.github.com` for this repository's releases; **Download** fetches
the APK (and its `.sha256`) and Tunnels refuses it unless it is the same package, newer, signed with the same key
as the installed copy and matching the checksum. Android then asks you to confirm the update.

- GrapheneOS: turn on Tunnels' **Network** permission for the check (App info → Permissions → Network), and off
  again afterwards. Tunnels never connects in the background.
- The repository is public, so the check needs no token. An optional read-only token (Contents, read-only, on
  this repository only) raises GitHub's request limit; it is stored in the encrypted
  database, sent to `api.github.com` only, and forgotten after 30 days without a check.

## Moving from the debug install to the release install

1. Install the release APK. It sits beside the debug install and starts empty.
2. In the **debug** install: Snapshots → Export and import → Export, a passphrase, save the `.fwx`.
3. In the **release** install: Snapshots → Export and import → Import, the same file and passphrase. Check the
   snapshots, settings, paired phones and confirmed networks ([EXPORT.md](EXPORT.md)).
4. Keep the debug install until the release one is confirmed; uninstalling it later is the owner's call.
