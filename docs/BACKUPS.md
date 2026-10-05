# Backups tunnel (B06)

Manual exports get forgotten, so this tunnel watches them. It needs **no permission and no network**: you pick the folder
that holds your exports once, in Android's folder picker, and Tunnels keeps a read-only grant to that one folder.

## What it reads

For each `.fwx` (FWX v1) or `.tsnap` (old Tunnels format) file in the folder, and in folders up to two levels below it, it reads the
**plaintext header** with the codec's passphrase-free reader (`core/export`, the B05a copy; export container spec section 6): at
most 202 bytes, so never a byte of the encrypted part and never a passphrase. From the header it takes the app ID, the
schema and the time the export was made. Other files (photos, notes) are counted and never opened. At most 500 headers and
20,000 listing entries are read per scan, **newest names first** (name descending, then last-modified descending).

**Lumen** writes one bundle per vault item (`lumen-<stamp>Z-<n>.fwx`, often hundreds) next to its manifest bundle
(`lumen-<stamp>Z.fwx`). The per-item files are counted by name and **never opened**; only the manifest bundle's header says when a
Lumen export was made.

**A folder on the phone's own storage, please.** A storage provider backed by a cloud service may download a whole file just to
let the header be read, and that would happen in the background check too. Pick a folder on the phone itself.

| Found | Shown as |
|---|---|
| `app_id` in the registry (`tunnels`, `lumen`, `southbound`, `mardigras`, `pusher`, `pusher-server`, `prikey`) | that app, newest header time and file count |
| any other app ID | **other**: a count and the newest time, never the ID or the name |
| old `TSNAPE1` Tunnels export | Tunnels, "old format", with the file's own last-modified date (it has no header date) |
| a header dated more than a day ahead of the clock | **suspicious**, never counted as fresh (a planted file cannot hide a stale backup) |
| anything that is not a readable FWX v1 header, or a file the storage provider fails on (any error, not only I/O) | counted as "not readable as a bundle"; the scan goes on |
| an old Tunnels file with no last-modified time | **present, date unknown**: counts as a file, is not missing, and keeps an older dated bundle from being called stale |
| Lumen per-item files and no manifest bundle | present, date unknown |

A header's MAC needs the passphrase, so nothing here is verified. The wording says "the newest bundle header says <date>", never
"backup verified": only a restore drill proves a backup.

## Which apps can be stale or missing

Only **watched** apps. An app is watched once a bundle of it has been found in the folder, or when you switch it on in the
screen; you can switch any app off (or tap "Stop watching" on its finding). Apps you never exported are not reported missing.
The choices (`seen` and your switches) belong to the app, not to the folder: they **survive a change of folder**, so after you point the
tunnel at a new folder every app you watched is judged there.

When the folder cannot be read (moved, deleted, access removed) no app is judged at all: an unreadable folder is not a missing
backup. A `BACKUP_FOLDER_LOST` warning says so instead of the stale findings vanishing unseen (also in the background check).
When the folder holds more files than one scan reads, the files it did not read could be the fresh ones, so nothing is judged stale or
missing and one `BACKUP_SCAN_INCOMPLETE` notice says so.

## Findings

All are state findings: they clear on the next scan once the state is fixed.

| Kind | When | Severity | Actions |
|---|---|---|---|
| `BACKUP_STALE` | a watched app's newest header is older than your limit (1, 7, 14, 30, 60, 90 or 180 days; default 30) | warning | Open <app> / Stop watching <app> |
| `BACKUP_MISSING` | a watched app has no bundle in the folder | warning | the same |
| `BACKUP_DATE_SUSPICIOUS` | every dated bundle of a watched app is dated in the future | notice | the same |
| `BACKUP_FOLDER_LOST` | a folder was chosen and cannot be read now (deleted, moved, access removed) | warning | Open Backups to choose the folder / Forget the folder |
| `BACKUP_SCAN_INCOMPLETE` | the folder has more files than one scan reads | notice | Open Backups |
| `RESTORE_DRILL_DUE` | you set a "last restore drill" date and it is 90 or more days ago | notice | Open Snapshots to try an import / I did a drill today |

"Open <app>" starts the app's launcher screen (`getLaunchIntentForPackage`, which relies on the `QUERY_ALL_PACKAGES`
permission the app already declares; the Backups module adds no permission and no `<queries>`), or says the app is not
installed. Tunnels itself gets "Open Snapshots to export"; the Pusher server (a script, not a phone app) gets "How to
refresh". If you never set a drill date there is no reminder.

## Stored

Observations (30-day retention and the 12-snapshot rule, as for every tunnel): per app `status`, `newest_ms`, `age_days`,
`files`, `schema`, `tracked`; plus `folder` (`state`, counts), `other` and `drill`. `age_days` moves with the clock alone, so it
is a volatile key (a background check stores no snapshot for it). The folder's tree URI, the limit, your watch choices and the
drill date are encrypted settings (`backups.folder`, `backups.config`); they are not part of an export bundle.

The tunnel runs in the opt-in background check with the offline tunnels. A new warning raises the check's notification, which
names the tunnel and kind ("Backups: Backup stale"), never the app.

## Check on the phone

1. Open Backups, tap Choose folder and pick the folder that holds your Tunnels, Prikey and Mardi Gras exports. Scan: each app shows
   its newest export date. Nothing asks for a passphrase.
2. Move one app's export out of the folder (or set the limit to 1 day for an app whose newest export is older) and scan: a
   finding appears with "Open <app>". Put the file back (or reset the limit) and scan: the finding is gone.
3. Lumen: pick a folder that holds a Lumen export with its many per-item files. Lumen shows its manifest date and "N item bundles",
   and no finding appears (the scan opens only the manifest).
4. Delete the export folder (or remove its access in Android's settings) and scan: a "Backup folder lost" warning appears, with
   "Open Backups to choose the folder". Choose the folder again: it clears. With background checks on, the notification reads
   "Backups: Backup folder lost" (tunnel and kind only, never an app).
5. Set the drill date to a day more than 90 days ago: a reminder appears. Tap "I did a drill today": it clears.
