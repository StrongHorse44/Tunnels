---
name: steward
description: How an agent drives a Tunnels pull request from opening to release - the release habit (every app-changing PR bumps VERSION by one patch so its merge starts the release), what to watch after the merge, and RJ's working preferences. Read before opening or driving any PR in this repository.
---

# Steward: from pull request to release

RJ's plan of 2026-10-11: releases follow from merges without extra steps, while signing and publishing keep RJ's taps
(two since 2026-10-15: `signing`, then `publish`).
CLAUDE.md's release rules stay in force; this file says how an agent works inside them.

## The release habit

1. **Every PR that changes the app bumps `VERSION` in the same PR.**
   - "Changes the app" means any change that lands in the APK: Kotlin under `core/`, `tunnels/` or `app/`, resources,
     manifests, `permissions.allow`, Gradle files, the version catalog.
   - Docs-only, CI-only, test-only and skill-only PRs do not bump.
   - Bump the patch by one from the base branch's `VERSION`: 0.1.6 becomes 0.1.7. RJ decides when a 0.2 or a 1.0 comes;
     never bump the minor or major on your own.
   - Put the bump in its own last commit, `Release X.Y.Z: <what the release ships, in a few words>`, after the feature
     commits (it carries `[emulator]`, see below). The PR title says `(release X.Y.Z)` at its end.
2. **The PR body gets a `## Release` section:**
   - one line: "Merging this starts release X.Y.Z; it is signed only after RJ approves `signing` and published only
     after RJ approves `publish`, both in the GitHub app."
   - what the release ships, in RJ's words where possible.
   - a dry-run note when the build changed (a new module, Gradle or workflow change): `docs/RELEASING.md` step 2 asks
     for a Release dry run on the branch first. RJ or the overseer starts it; agents never do.
3. **Two PRs open at once both bump.** The first to merge takes the number. The second then has a conflict on `VERSION`:
   merge the base branch into it (never rebase someone else's branch) and set `VERSION` to the new base plus one patch.
   The Release run refuses a version lower than or equal to one already released, so a stale bump fails safely.
4. **After the merge,** the Release run starts by itself: plan, build, then the `sign` job waits for RJ's first tap
   (`signing`); once approved it signs, the release check runs, and `publish` waits for the second tap.
   - Watch it.
   - When it reaches `sign`, tell RJ in one line: "vX.Y.Z is ready to sign: Actions → the Release run → Review
     deployments → signing → Approve."
   - When it reaches `publish`, tell RJ in one line: "vX.Y.Z is signed and ready: Actions → the Release run → Review
     deployments → publish → Approve, within three days (the signed APK expires after three days)."
   - If a job fails, read its log, say what broke, and propose the fix as a new PR (which bumps again).
   - Once published, give RJ the release link.
5. **Never, under this habit or any other:**
   - push a tag;
   - start `release.yml` with `publish` ticked;
   - approve or reject a deployment;
   - push to the integration branch or `main`.

   Both taps are RJ's: `signing` guards the key and `publish` is the control on publishing (CLAUDE.md).

## Driving the PR to green

- Before pushing, run the plain-Kotlin tests of every `core:` module you touched
  (`scripts/jvm-check/run.sh :core:<name>:test`). Android modules compile only in CI.
- A gate failure in the `build` job names exact `hosts`, components or permissions.
  - When the change is intended, add exactly those entries, with notes, to both `gate/baseline.json` and
    `gate/baseline.release.json`, and list them under `## GATE CHANGES`. RJ merges a gate change.
  - When it is not intended, remove the cause.
- CLAUDE.md asks for `[emulator]` in the final commit's message. With the bump last, that is the bump commit:
  `Release X.Y.Z: <summary> [emulator]`. Without it a pull request's emulator jobs (`ci.yml`'s smoke test and the
  Phase workflows') are skipped, and the pull request is green without having run an emulator.

## RJ's working preferences

- Ask with question cards before building anything new, and recommend one option.
- One feature per PR, from its own branch, opened against the integration branch.
- Plain words in findings, the screen and replies: say what something is and what to do, never claim proof that is
  not there.
- Changes to the hard rules in CLAUDE.md need RJ's explicit choice, recorded with the date in the rule itself.
