# Releasing Tunnels

This repository is **public**. Nothing secret is in it: the release signing key lives only in GitHub
environment secrets, on the owner's phone and in the owner's own backups, and every secret is read by exactly one
workflow job.

## One channel: vX.Y.Z

| | **vX.Y.Z release** |
| --- | --- |
| Package | `io.github.stronghorse44.tunnels` |
| Version name | `X.Y.Z`, version code `X*1000000 + Y*1000 + Z`, both from the `VERSION` file |
| Signed with | the owner's release key (never in the repository) |
| Published by | `release.yml`, started by a merge that changes `VERSION` on the integration branch (or by the owner's `publish` run, below), as the release **vX.Y.Z** |
| APK name | `tunnels-X.Y.Z.apk` + `.sha256` |
| Approval | the `publish` environment waits for the owner |
| The in-app updater follows | `vX.Y.Z` releases only |

`ci.yml` still builds the debug package (`io.github.stronghorse44.tunnels.debug`, signed with the public key in
`app/debug.keystore`) and the unsigned release on every run, and gates both, but it **publishes nothing**: no
workflow job other than `release.yml`'s `publish` holds a writing token.

### The old debug install

The "Debug build #N" channel is retired: no new `debug-N` prerelease appears. An installed debug build stays
installed and keeps working. Its updater follows `debug-N` prereleases only, so it sees nothing newer than the
last Debug build; the old `debug-N` prereleases stay on the Releases page. Uninstall it whenever you like (its
data moved to the release install, [EXPORT.md](EXPORT.md)). The two packages differ, so they install side by
side and neither can update the other (they also have different signing keys); tell them apart in App info (the
package name) or on the update screen (`installed v0.1.0-dev.93-debug` against `installed v0.1.0`). The updater
tells channels apart by the release **tag** (`debug-N` against `vX.Y.Z`) and the installed package (a name
ending `.debug` follows the debug tags); a release install never offers a debug build (`core/updates` tests pin
both).

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

A run from any other ref cannot read either environment's secrets, whatever its workflow file says. A release
run started by a merge has the integration branch as its ref, which both policies admit, so release on merge
needs no environment setting to change.

| Environment | Holds | Reviewers | Used by |
| --- | --- | --- | --- |
| `signing` | four secrets: `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` | none, by design | the `sign` job of `release.yml`, and nothing else |
| `publish` | no secrets | `StrongHorse44` (required) | the final job of `release.yml`: the owner's approval |

**What actually protects the key.** `signing` has no reviewer, so that a release needs one approval (at
`publish`), not two. The control on the key is therefore **who can push to the integration branch and to
`main`**: a merge that changes `VERSION` on the integration branch, or a manual run with `publish` ticked from
either branch, signs the commit it ran on without asking anyone. The workflow file on those branches is reviewed
code, and the `sign` job runs no repository code; nothing else can read the secrets. Also:

- A `VERSION` merge starts a publishing run. **The program overseer may merge a reviewed, green pull request
  whose only gate-relevant change is the `VERSION` bump; publishing still waits for the owner's tap at
  `publish`.** A `VERSION` change combined with any real gate change is a gate change, and the owner merges it.
  Rejecting the approval publishes nothing and that version is skipped.
- Agents (Claude sessions) act through tokens tied to the owner's GitHub account, so "agents do not start
  release runs by dispatch or tag, or approve them" is a **policy**, not a control. The program overseer starts
  dry runs (`publish` off) only; a `publish` ticked dispatch and every approval are the owner's. Sessions act
  with the owner's own GitHub identity, so nothing but the rule stops an agent approving or rejecting a pending
  deployment at `publish`: agents never do, and the tap is the owner's.
- A publishing run produces a **release-signed APK as a run artifact (`signed-apk`) before anyone has approved
  anything**. Signed-in users with read access to the repository (it is public) can download artifacts, so the
  artifact is kept for 1 day only. Approve on the same day, or re-run the run. The `plan` job keeps this to runs
  that will ask: a version that is already released, malformed or out of order is refused before anything is
  built or signed.

Secrets are **environment** secrets, not repository secrets. To add them from Termux without showing them
(`pkg install gh`, `gh auth login` once):

```sh
base64 -w0 tunnels-release.jks | gh secret set KEYSTORE_BASE64 --env signing --repo StrongHorse44/Tunnels
printf '%s' tunnels | gh secret set KEY_ALIAS --env signing --repo StrongHorse44/Tunnels
gh secret set KEYSTORE_PASSWORD --env signing --repo StrongHorse44/Tunnels   # prompts; same for KEY_PASSWORD
gh secret set KEY_PASSWORD --env signing --repo StrongHorse44/Tunnels
```

## Cutting a release

A release starts when a pull request that changes the `VERSION` file at the repository root is merged into the
integration branch. The version comes from that file and nowhere else.

**The file.** Exactly `MAJOR.MINOR.PATCH` (each part 0 to 999, no leading zeros, no `v`, no spaces) and at most
one newline after it, as a regular file; `0.0.0` is refused. The tag is `vMAJOR.MINOR.PATCH`. It must be
**higher** than every earlier release (the run refuses a lower or equal one, and a version whose release or tag
already exists at another commit); gaps are fine, a version that was rejected or dropped is skipped, never
lowered back to. The pull request that wants a release bumps it, as its own commit `Release vX.Y.Z`. The CI build
job checks the format on every pull request, so a malformed file is a red pull request, not a red release.

| Event | What runs | What you see |
| --- | --- | --- |
| Merge, `VERSION` unchanged | CI. No Release run. | Nothing to approve |
| Merge, `VERSION` raised to an unreleased higher version | Release: plan, build, sign, release-check, publish (waits) | **Waiting for review**; approve the same day |
| Merge, `VERSION` equals an existing release (a revert) | Release: plan only, a notice, green | Nothing to approve |
| Merge, `VERSION` malformed, missing, lower than the highest release, or its tag exists without a release | Release: plan fails red; nothing is built or signed | A red Release run; fix it in a pull request |
| Run, `publish` off (any branch) | plan, build, gate, signing rehearsal | Nothing; a dry run |

1. **Bump and open the pull request.** `VERSION` to the next version, one commit `Release vX.Y.Z`. CI checks the
   format. (A pull request with a real gate change that also bumps `VERSION` is a gate change like any other.)
2. **Dry run first, whenever the workflow or the build changed.** github.com in the phone's browser → the
   repository → **Actions → Release → Run workflow**. Branch: the pull request's branch; `publish`: **off**. It
   reads that branch's `VERSION`, builds, runs the release gate and rehearses signing with a throwaway key, and
   publishes nothing; the `sign` job does not even start, so the key is not touched. (The program overseer
   starts dry runs.)
3. **Merge it.** The Release run starts by itself within a few minutes: `plan`, then the build, the signing job
   and the release check. If a check fails, the run stops before you are asked to approve anything.
4. **Approve, the same day.** The run then shows **Waiting for review** on the `publish` environment. In the
   GitHub app: the notification, or the repository → Actions → the run → **Review deployments → publish →
   Approve**. The signed APK the run holds expires after 1 day; if it did, publish fails and nothing is
   published: use **Re-run all jobs**, which signs again. Rejecting the deployment publishes nothing and that
   version is skipped; the next bump goes higher.
5. The release **vX.Y.Z** appears on the Releases page with `tunnels-X.Y.Z.apk` and its `.sha256`.
   Install it: tap the APK in the browser or Obtainium, or use **update** inside an installed release.

If two bump pull requests merge close together, their runs build and sign in parallel and publish one at a time
(`publish` has one constant concurrency group, no cancel), and the order re-check refuses a lower version
published after a higher one. GitHub keeps one pending run per group, so if a third arrives while one waits for
approval and another is queued, the middle run shows **cancelled** and that version is skipped. A push that
changes more than 300 files may not start a run at all (GitHub's path filter limit; not verified here): start it
by hand, below.

### Starting a release by hand (recovery)

The same Run workflow form, branch `ccr-dff99af5-ij1rle` (or `main`), `publish` **ticked**, for a run a merge did
not start or that must be tried again. It reads `VERSION` at that branch's head like any other run and is
refused from any other branch. The release must not already exist at another commit; one at the same commit is
re-attached to after the approval. There is no `version` input any more. The GitHub app can show and approve
runs but, as far as we know, cannot fill in the Run workflow form; use the browser.
Agents never start one by dispatch or by tag.

Do not create the tag or the release by hand (**Releases → Draft a new release**): the workflow creates the tag
and the release at the commit it built, or uses the existing tag if it is already at the built commit, and it
stops if a release or tag for that version already exists at another commit. A push run whose version already has
a release ends green in `plan` with a notice; a tag that exists without a release stops a push run in `plan`
(look at the tag by hand). `release-check` looks at the release and the tag separately: an existing release must
have been made for the built commit (its `target_commitish`), and an existing tag ref (`git/ref/tags/<tag>`) must
point at the built commit, whether or not a release exists. An annotated tag is followed **at most 5 levels**
(a tag of a tag counts), and a deeper chain is refused; a tag that ends at a **tree or blob** is refused; if
fetching an annotated-tag object fails (**a 404 included**) the job fails, and so does a GitHub answer that is
not one ref with an object type and sha. Each of these has its own error line. Only an HTTP 404 on the release
or the tag ref means "not there"; any other API error fails the job. The `publish` job repeats the same checks
after the approval wait, and resolves the tag ref the same way before it replaces the APK of an existing release
(`gh release upload --clobber`) or creates one: it **creates the tag and the release, or uses the existing tag if
it is already at the built commit**, and a release at the built commit whose tag points elsewhere is refused, not
updated.

### What the workflow does

`plan` (no checkout, no repository code, no environment) reads `VERSION` at the run's commit through the API,
checks its format, whether the release or the tag already exists and where the version sits in the order, and
decides whether anything is built or signed; `build` (no environment, no secrets) builds the release
**unsigned**, runs the unit tests and the release gate, and rehearses signing with a throwaway key; `sign`
(environment `signing`, no checkout, no repository code) checks the commit is on the integration branch or
`main`, decodes the key into a private temporary file, aligns (`zipalign -P 16`), signs with `apksigner` (v2
only, PKCS12) and verifies; `release-check` re-reads `VERSION` at the built commit (it must be the planned
version), runs `gate.py cert --require-pin` against the pinned release certificate, checks package, version name
and version code, and that no release or tag for this version exists at another commit; `publish` (environment
`publish`) checks the version order again with `gh` only (two approved runs cannot publish a lower version
last), attaches a build provenance attestation, and creates the tag and the release. Only the `sign` job
references the secrets, and only the `publish` job can write to the repository. `gate.py cert` and `aapt2` run
from the pinned build-tools (36.0.0), not the newest installed. `plan` can only narrow what the later jobs'
event-context conditions allow; it can never widen it.

Version code: `MAJOR*1000000 + MINOR*1000 + PATCH`, so versions order the way their codes do (`v0.1.0` is
1000, `v1.2.3` is 1002003). The code is computed by the workflow from `VERSION`, never typed.

## The gate and the release baseline

Two files record what each build's APK can do: `gate/baseline.json` (the debug build, which CI builds and gates
on every run and no longer publishes) and `gate/baseline.release.json` (the release build: not debuggable, no
Compose preview activity, the release package, `releaseRuntimeClasspath`, the release certificate pin). See `gate/README.md`. `ci.yml` builds both
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

For one of the old Debug builds (no new ones are published) use `tunnels-debug-N.apk` and `--signer-workflow
.../ci.yml --source-ref refs/heads/ccr-dff99af5-ij1rle`. A debug attestation shows which workflow built an APK;
it does not make the public debug key private.

## Rolling back

Android does not install a lower version code over a higher one, and the workflow does not publish one. To
undo a bad release: delete or edit the release on GitHub if needed, fix the code, and release a **higher**
version. A release install can always go forward only.

## Updating from inside Tunnels

Home screen → **update ›**. A release build follows the `vX.Y.Z` releases; an old debug install follows the
"Debug build #N" prereleases and sees no new builds, since none are published any more. Tapping **Check** asks
`api.github.com` for this repository's releases; **Download** fetches
the APK (and its `.sha256`) and Tunnels refuses it unless it is the same package, newer, signed with the same key
as the installed copy and matching the checksum. Android then asks you to confirm the update.

- GrapheneOS: turn on Tunnels' **Network** permission for the check (App info → Permissions → Network), and off
  again afterwards. Tunnels never connects in the background.
- The repository is public, so the check needs no token. An optional read-only token (Contents, read-only, on
  this repository only) raises GitHub's request limit; it is stored in the encrypted
  database, sent to `api.github.com` only, and forgotten after 30 days without a check.

## Moving from the debug install to the release install

No new Debug builds are published.

1. Install the release APK. It sits beside the debug install and starts empty.
2. In the **debug** install: Snapshots → Export and import → Export, a passphrase, save the `.fwx`.
3. In the **release** install: Snapshots → Export and import → Import, the same file and passphrase. Check the
   snapshots, settings, paired phones and confirmed networks ([EXPORT.md](EXPORT.md)).
4. Keep the debug install until the release one is confirmed; uninstalling it later is the owner's call.
