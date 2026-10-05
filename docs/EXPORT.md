# Export and import (bundle schema 1)

Tunnels exports to one passphrase-encrypted file in the program's **FWX v1** container (`.fwx`), through the system
file picker: no permission, no network, nothing leaves the phone unless you move the file yourself. The container is
specified in the program repository (`specs/export-container.md`); this page says what Tunnels puts in it.

Registry: app ID `tunnels` (debug and release builds share it, so an export from a debug install imports into a
release install). Bundle schema **1**; the importer accepts schema 1 only.

## What the bundle holds

Entries, written in this order (`fwx.py --list` shows exactly these five names and an `OK 5 entries` line):

| Entry | Holds |
|---|---|
| `manifest.json` | `{"app":"tunnels","schema":1,"app_version":"…","snapshots":N,"observations":N,"settings":N,"pairing_pins":N,"networks":N}`. Counts only; the importer checks them against what it parsed. |
| `snapshots.tsnap1` | Every snapshot (when it was taken, whether it is pinned, which tunnels it covers) and every observation, as `TSNAP1` text (`core/export` `BundleFormat`, unchanged since the old format). Findings are not carried: they are derived on the phone by the next scan. Home network's device list travels in the pinned Home network snapshot (`census:known`, tokens only); a device you tapped Mine on but no scan has folded in yet is an event and is not carried, so scan once after tapping Mine, before exporting. |
| `settings.tsv` | The settings you chose, as `key<TAB>value` rows after a `TSET1` line: `traffic.block` (Traffic blocking), `traffic.upstream` (Traffic's resolver) and `watch.settings` (background checks). A key this phone never set is absent. |
| `pairing-pins.txt` | The second phones this one paired with (pinned identity keys, names, boot key, patch level, last audit), one per line, as the Second phone tunnel stores them. |
| `networks.txt` | SHA-256 of each Wi-Fi network you confirmed as your own for Home network (the gate's hashes, never an SSID or an address), one per line. Read from the settings table's `homenet.confirmed_networks` row. |

Not carried, on purpose: findings and their dismissals, events, the inbox's last visit, the last background-check
status (this phone's history, not choices), the Second phone verifier ID (this phone's identity: a restore must not
give two phones the same one), the app lock switch (it depends on the screen lock of the phone it is restored on),
the Surroundings cell logbook (`surroundings.cell_logbook`: its hashes are keyed by this phone's keystore and mean
nothing on another phone or after a reinstall, so a restored phone starts the logbook from zero), and the update token. Every value is read back through the code that owns it and written in that code's own
canonical form, so nothing from the file is stored as it came.

Visible without the passphrase (container spec 1.1): that it is a Tunnels export, the schema, the time it was made,
and the file size (so roughly how much history it holds). Entry names, counts and contents are encrypted.

## Export

Snapshots → Export and import → Export. A passphrase of at least 12 characters, typed twice (five or more random
words is a good choice; it is checked with the codec's own rule, counting characters after Unicode normalisation),
then the destination (suggested name `tunnels-yyyyMMdd-HHmmssZ.fwx`, UTC). Then, in this order:

1. everything is read from the encrypted store **in memory** (the confirmed networks are a row of its settings table, `homenet.confirmed_networks`; see "Where the confirmed networks live" below);
2. the bundle's entries are built and parsed back with the same function the import uses, and must equal what is
   about to be written: anything an import would refuse (a value over the line cap, more snapshots than the import
   takes, text that does not survive UTF-8) stops the export **before the picked file is opened**;
3. the file is opened for writing and the bundle streamed into it (plaintext is held in memory only; the buffers the export owns are zeroed afterwards, best effort: Strings and grown buffers cannot be);
4. the finished file is read back in full through the import's reader with the same passphrase and compared with
   what was exported.

If any step fails, a file the export opened is deleted (an empty file the picker just made is deleted too; an
existing file that was never opened for writing is left alone) and the message says which. The passphrase is never
stored, logged or kept in saved state, and is zeroed after use.

## Import

Snapshots → Export and import → Import. The order is fixed (container spec 5.1, 5.2):

1. **Header first, no passphrase.** The picked file's first bytes decide: not an export, another app's export (named:
   "This is a Prikey export, not a Tunnels export"), a schema this build cannot read, or a damaged header are refused
   here, before any passphrase prompt. A good file shows when it says it was made (local time and UTC), marked *not
   yet verified*, and what the import will do.
2. **Passphrase, full verification, staging in memory.** The whole file is decrypted chunk by chunk and verified to
   its last byte; each entry is parsed with bounds (see below) into memory. Nothing is written yet. A wrong
   passphrase, a damaged or truncated file, trailing bytes, an unknown or duplicate entry, a manifest that disagrees
   with the entries: nothing is changed, and the message says so. A wrong passphrase leaves the file selected for a
   retry.
3. **One swap.** One database transaction adds the snapshots and writes the settings, the pairing pins and the
   confirmed networks (all rows of the same encrypted store), so a failure keeps none of them. Just before it, an older
   build's plaintext networks file, if one is still there, is moved into the store (see below).

Import **adds to** the phone and removes nothing:

| Data | What import does |
|---|---|
| Snapshots | Added unless the phone already has one taken at the same moment (so importing a file twice does not double the history). A snapshot keeps the pin it had in the file; one already on the phone keeps its own. An unpinned snapshot is subject to retention like any other (the newest 12 unpinned are kept), so older unpinned ones brought into a phone that already has newer ones can be removed by the next retention pass. |
| Settings | Each setting in the file replaces this phone's value; settings the file does not have are left alone. The done message names Traffic's resolver when the file set one (a bundle can carry a custom DNS-over-HTTPS address). If the file has the background-check setting, the scheduled job is armed or cancelled to match at once. |
| Paired phones | Merged by phone: new ones are added; for a phone both have, the more recently audited record stays. |
| Confirmed networks | Merged: the union. |

### Where the confirmed networks live

The hashes Home network's gate trusts are one row of the encrypted settings table, `homenet.confirmed_networks`: one
lowercase SHA-256 per line, sorted. The row is not in `settings.tsv` (it has its own entry, `networks.txt`, and an
import merges it rather than replacing it). Builds before this change kept them in a plaintext preferences file,
`homenet_gate` (key `confirmed_network_hashes`), which broke the encrypted-at-rest rule. On the first start after the update
(and, if that did not run, the first time the gate, an export or an import needs the list) they are copied into the
row, the row is read back, and only then the plaintext file is emptied and deleted: never both, and a copy that fails
leaves the file untouched for the next try. The file is never read for a decision, and once the row exists it is not read even to merge (so a
network forgotten later does not return from a leftover file). If the store cannot be read, no network counts as
confirmed and Home network refuses to scan ("Tunnels' encrypted store could not be read"); an export stops rather than
leaving networks out.

### Old exports (`TSNAPE1`)

An export made by an earlier Tunnels (the `.tsnap` file, `TSNAPE1` magic) still imports: the first bytes route it to
the old reader (unchanged: 310 000 PBKDF2 iterations, the whole file in memory, at most 32 MiB). It carries
snapshots only, so an import of it touches nothing else, and goes through the same transaction as a new one.
Tunnels no longer writes that format.

### Bounds

| Limit | Value |
|---|---|
| Plaintext of the whole bundle | 64 MiB |
| Snapshots / observations | 5 000 / 500 000 |
| One line of `snapshots.tsnap1` | 1 Mi characters |
| Each of the other entries | 1 MiB |
| Paired phones / networks | 1 000 / 10 000 |
| Snapshot dates | not after now + 1 day, not before 2020-01-01 UTC (otherwise the file is refused as invalid) |
| Entries | exactly the five above, `manifest.json` first, each once |

Counts are checked while parsing, so a hostile or damaged file stops at the first record over a cap instead of
after it has filled memory.

## Which snapshot is "latest"

A tunnel's current reading is its newest snapshot by the time it was taken, then by id (`TunnelsDao.latestSnapshotIdFor`), not by id alone: an import adds older snapshots with higher ids and must not make them the current reading.

## Schema history

| Schema | Entries |
|---|---|
| 1 | the five above (B05a, 2026-10) |

A change to the entry set or to any entry's format bumps the schema; the importer's range is `1..1` until a tested
migration for an older schema exists.

## The codec

`core/export/src/main/kotlin/…/export/fwx/` is the program's FWX codec 1.2.0 copied unchanged except for its package
line and one provenance line per file (the canonical SHA-256 and the program-repository commit it came from).
`CodecCopyTest` recomputes each file's hash, so an edit made here fails CI; it also scans the repository for the test
hooks the codec README lists (none may exist outside `src/test`). The program's test vectors (`src/test/resources/fwx-v1/`)
and tests run in `:core:export:test`.

## Checking an export on the phone

1. Snapshots → Export; save `tunnels-<date>Z.fwx`; the done message shows the counts and the file size.
2. In Termux (`pkg install python python-cryptography`), with `fwx.py` from the program repository's `tools/`:
   `python3 fwx.py --list <file>`, then the passphrase. It lists `manifest.json`, `snapshots.tsnap1`, `settings.tsv`,
   `pairing-pins.txt` and `networks.txt` with sizes and ends with `OK 5 entries`. (`--list` shows names and sizes
   only; never use `--extract` on a real export, it writes the decrypted contents to storage.)
3. Copy the file off the phone. Restore drill: in a **spare user profile**, install Tunnels, Import, pick the file:
   the header shows first (unverified), then the passphrase; the counts in the done message match the export's.
4. Another app's `.fwx` is refused by name before any passphrase prompt; a wrong passphrase says so and changes
   nothing; an old `.tsnap` export still imports.
