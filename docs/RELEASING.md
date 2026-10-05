# Releasing Tunnels

This repository is **public**. Nothing secret is in it: the release signing key lives only in GitHub
environment secrets and on the owner's phone, and every secret is read by exactly one workflow job.

## Two channels, two packages

| | **Debug build #N** | **vX.Y.Z release** |
| --- | --- | --- |
| Package | `io.github.stronghorse44.tunnels.debug` | `io.github.stronghorse44.tunnels` |
| Version name | `0.1.0-dev.N-debug`, version code `N` (the CI run number) | `X.Y.Z`, version code `X*1000000 + Y*1000 + Z` |
| Signed with | the public key committed in `app/debug.keystore` (anyone can sign with it) | the owner's release key (never in the repository) |
| Published by | `ci.yml`, on every push to the integration branch, as a prerelease named **Debug build #N** (`debug-N`) | `release.yml`, on a `v*` tag or a manual run, as the release **vX.Y.Z** |
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
environment settings). Both are limited to **Deployment branches and tags → Selected**:
branch `ccr-dff99af5-ij1rle`, branch `main`, tag pattern `v*`. A run from any other ref cannot read either
environment's secrets, whatever its workflow file says.

| Environment | Holds | Reviewers | Used by |
| --- | --- | --- | --- |
| `signing` | four secrets: `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` | none | the `sign` job of `release.yml`, and nothing else |
| `publish` | no secrets | `StrongHorse44` (required) | the final job of `release.yml` and the publish job of `ci.yml`: the owner's approval |

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
every earlier release; the run refuses a lower or equal one.

1. **Start the run.** Either:
   - **Manual run (recommended).** github.com in the phone's browser → the repository → **Actions → Release →
     Run workflow**. Branch: `ccr-dff99af5-ij1rle` (or `main`), `version`: `v0.1.0`, `publish`: ticked.
     The workflow creates the tag and the release itself, at the commit it built. The GitHub app can show and
     approve runs but, as far as we know, cannot fill in the Run workflow form; use the browser.
   - **Tag.** In Termux: `git tag v0.1.0 <commit>` and `git push origin v0.1.0`, where `<commit>` is on the
     integration branch or `main`. Do **not** use **Releases → Draft a new release** to make the tag: that
     creates a release first, and the workflow then refuses to overwrite a release it did not make.
2. **Dry run first, whenever the workflow or the build changed.** The same form with `publish` left off builds,
   runs the release gate and rehearses signing with a throwaway key, and publishes nothing; the `sign` job
   does not even start, so the key is not touched.
3. **Wait for the build** (about 15 minutes), the signing job and the release check. If a check fails, the run
   stops before you are asked to approve anything.
4. **Approve.** The run then shows **Waiting for review** on the `publish` environment. In the GitHub app: the
   notification, or the repository → Actions → the run → **Review deployments → publish → Approve**.
5. The release **vX.Y.Z** appears on the Releases page with `tunnels-X.Y.Z.apk` and its `.sha256`.
   Install it: tap the APK in the browser or Obtainium, or use **update** inside an installed release.

Agents do not start release runs or approve them; the program overseer starts the dry run, the owner approves.

### What the workflow does

`build` (no environment, no secrets) builds the release **unsigned**, runs the unit tests and the release gate,
and rehearses signing with a throwaway key; `sign` (environment `signing`, no checkout, no repository code)
checks the commit is on the integration branch or `main`, decodes the key into a private temporary file,
aligns (`zipalign -P 16`), signs with `apksigner` (v2 only, PKCS12) and verifies; `release-check` runs
`gate.py cert --require-pin` against the pinned release certificate and checks package, version name and
version code; `publish` (environment `publish`) attaches a build provenance attestation and creates the release.
Only the `sign` job references the secrets, and only the `publish` job can write to the repository.

Version code: `MAJOR*1000000 + MINOR*1000 + PATCH`, so versions order the way their codes do (`v0.1.0` is
1000, `v1.2.3` is 1002003). The code is computed by the workflow from the tag or the `version` input, never
typed.

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
  this repository only) raises GitHub's request limit or reads a private fork; it is stored in the encrypted
  database, sent to `api.github.com` only, and forgotten after 30 days without a check.

## Moving from the debug install to the release install

1. Install the release APK. It sits beside the debug install and starts empty.
2. In the **debug** install: Snapshots → Export and import → Export, a passphrase, save the `.fwx`.
3. In the **release** install: Snapshots → Export and import → Import, the same file and passphrase. Check the
   snapshots, settings, paired phones and confirmed networks ([EXPORT.md](EXPORT.md)).
4. Keep the debug install until the release one is confirmed; uninstalling it later is the owner's call.
