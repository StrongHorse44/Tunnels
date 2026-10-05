# Backups tunnel (B06)

Manual exports get forgotten, so this tunnel watches them. It needs **no permission and no network**: you pick the folder
that holds your exports once, in Android's folder picker, and Tunnels keeps a read-only grant to that one folder.

## What it reads

For each `.fwx` (FWX v1) or `.tsnap` (old Tunnels format) file in the folder, and in folders up to two levels below it, it reads the
**plaintext header** with the codec's passphrase-free reader (`core/export`, the B05a copy; export container spec section 6): at
most 202 bytes, so never a byte of the encrypted part and never a passphrase. From the header it takes the app ID, the
schema and the time the export was made. Other files (photos, notes) are counted and never opened. At most 500 headers and
20,000 listing entries are read per scan. Files are grouped by folder and app name (the registry app ID the name starts with, else
its leading letters), **newest names first** in each group, and **at most 64 per group**; the groups are read a rank at a time,
registry apps first, so one app's pile of exports cannot starve another app that sorts after it. What an unread file means is in
"Which apps can be stale or missing" below.

**Lumen** writes one bundle per vault item (`lumen-<yyyyMMdd>-<HHmmss>Z-<n>.fwx`, often hundreds) next to its manifest bundle
(`lumen-<yyyyMMdd>-<HHmmss>Z.fwx`, written last). The per-item files are counted by the spec's exact name form and **never opened**;
only the manifest bundle's header says when a Lumen export was made. Lumen's own rule is that an export is complete only once its
manifest exists (its import refuses a folder with none), so item files alone are not a backup you can restore: that is a warning.

**A folder on the phone's own storage, please.** A storage provider backed by a cloud service may download a whole file just to
let the header be read, and that would happen in the background check too. Pick a folder on the phone itself.

| Found | Shown as |
|---|---|
| `app_id` in the registry (`tunnels`, `lumen`, `southbound`, `mardigras`, `pusher`, `pusher-server`, `prikey`) | that app, newest header time and file count |
| any other app ID | **other**: a count and the newest time, never the ID or the name |
| old `TSNAPE1` Tunnels export | Tunnels, "old format", with the file's own last-modified date (it has no header date) |
| a header dated more than a day ahead of the clock | **suspicious**, never counted as fresh (a planted file cannot hide a stale backup) |
| anything that is not a readable FWX v1 header, or a file the storage provider fails on (any error, not only I/O) | counted as "not readable as a bundle"; the scan goes on |
| an old Tunnels file with no last-modified time | **present, date unknown**: counts as a file and is not missing. It also keeps a *dated old-format file* from being called stale (it may be newer), but only while the app has **no FWX header date at all**: the old format is no longer written, so an FWX header is always the later export. With one, the newest dated file is judged as it is: an undated `.tsnap`, a `.tsnap` dated 100 days ago and a 400-day-old FWX bundle are **stale** (100 days), not "date unknown" |
| Lumen per-item files and no manifest bundle | **incomplete export**: a warning (`BACKUP_MISSING`, "item files but no manifest: the export is incomplete and can't be imported") |
| a file or folder the storage provider fails on (cannot be opened or listed) | counted, and the app it may belong to is **not judged**, never called missing. When the failed file's own name says it is the app's, the text is "A Prikey file could not be read"; when the file has no app name or a folder would not list, it only *may* be: "A file that may be Prikey's could not be read" |

A header's MAC needs the passphrase, so nothing here is verified. The wording says "the newest bundle header says <date>", never
"backup verified": only a restore drill proves a backup.

## Which apps can be stale or missing

Only **watched** apps. An app is watched once a bundle of it has been found in the folder, or when you switch it on in the
screen; you can switch any app off (or tap "Stop watching" on its finding). Apps you never exported are not reported missing.
The choices (`seen` and your switches) belong to the app, not to the folder: they **survive a change of folder**, so after you point the
tunnel at a new folder every app you watched is judged there.

When the folder cannot be read (moved, deleted, access removed) no app is judged at all: an unreadable folder is not a missing
backup. A `BACKUP_FOLDER_LOST` warning says so instead of the stale findings vanishing unseen (also in the background check).
**Files the scan did not read, or could not read.** A fresh header that was read is fresh, whatever else is unread. For stale and
missing the question is whether an unread file could belong to the app. If a group's newest-named file was read and every header
read in it names the same app (the group's own app, when the name says one), the unread files are older exports of that app, and it
is still judged from its newest: 510 Tunnels exports do not hide Prikey, and 510 Mardi Gras exports whose newest read one is 40 days
old are stale. "Read" has to date something: when no read in a group gave a date (every header is in the future, or the files are old-format
ones with no modified time), nothing there says how old the app's exports are, so the scan keeps reading that group's older names (past the 64 per group, up to the 500-header bound) until one gives a
date. If names are still unread when the bound is reached, the app is **not judged** (never "suspicious" and never stale), and a watched one
raises the warning: seventy future-dated Prikey files with a 45-day-old one beneath them are stale, not suspicious. When such a
pile has no app name (`backup-…`) and every header read in it names one app, only that app is held, not every app. Otherwise the app is **not judged** (status "not judged", never stale, missing or suspicious): the app the group's
name points to, or every app when the name points to none (a mixed pile named `backup-…`, a folder that would not list, a folder
with more than 20,000 entries, a file that fails to open and is the newest of its group). One `BACKUP_SCAN_INCOMPLETE` finding says
so: a **warning** when it keeps a watched app from being judged, a **notice** (it never raises the background notification) when
no watched app was held back. A notice says "Every app you watch was still judged from the newest file that was read" only when that is
so for each of them; otherwise it says what is different, per case: an app whose files are all undated ("Tunnels has files but none says when
it was made, so its age is not judged") or an app with an undated old-format file beside dated ones ("Tunnels has an old-format
file with no date that may be newer than its dated ones, so its age is not judged"), or an app whose newest file is named for another app ("Tunnels was judged from a file whose
name says it is another app's; check that file"), and "Every other app" for the rest. With no watched app the sentence is left out. A failed file's wording is in the table above; it
is not the same as "No Prikey bundle in the export folder".

## Findings

All are state findings: they clear on the next scan once the state is fixed.

| Kind | When | Severity | Actions |
|---|---|---|---|
| `BACKUP_STALE` | a watched app's newest header is older than your limit (1, 7, 14, 30, 60, 90 or 180 days; default 30) | warning | Open <app> / Stop watching <app> |
| `BACKUP_MISSING` | a watched app has no bundle in the folder, or (Lumen) has item files but no manifest bundle | warning | the same |
| `BACKUP_DATE_SUSPICIOUS` | every dated bundle of a watched app is dated in the future, and every file of the app was read | notice | the same |
| `BACKUP_FOLDER_LOST` | a folder was chosen and cannot be read now (deleted, moved, access removed) | warning | Open Backups to choose the folder / Forget the folder |
| `BACKUP_SCAN_INCOMPLETE` | files were left unread or a file or folder could not be opened | warning when a watched app is not judged because of it (including a pile of future-dated files that never gave a date), else notice | Open Backups |
| `RESTORE_DRILL_DUE` | you set a "last restore drill" date and it is 90 or more days ago | notice | Open Snapshots to try an import / I did a drill today |

"Open <app>" starts the app's launcher screen (`getLaunchIntentForPackage`, which relies on the `QUERY_ALL_PACKAGES`
permission the app already declares; the Backups module adds no permission and no `<queries>`), or says the app is not
installed. Tunnels itself gets "Open Snapshots to export"; the Pusher server (a script, not a phone app) gets "How to
refresh". If you never set a drill date there is no reminder.

## Stored

Observations (30-day retention and the 12-snapshot rule, as for every tunnel): per app `status`, `newest_ms`, `age_days`,
`files`, `schema`, `tracked` (and, only when true, `misnamed` and `failed_named`); plus `folder` (`state`, counts), `other` and `drill`. `age_days` moves with the clock alone, so it
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
   and no finding appears (the scan opens only the manifest). Move the manifest file (`lumen-…Z.fwx`, the one without `-<n>`) out and
   scan: Lumen shows "item bundles but no manifest" and a warning, "Open Lumen". Put it back and scan: the warning is gone.
4. Delete the export folder (or remove its access in Android's settings) and scan: a "Backup folder lost" warning appears, with
   "Open Backups to choose the folder". Choose the folder again: it clears. With background checks on, the notification reads
   "Backups: Backup folder lost" (tunnel and kind only, never an app). "Forget the folder" ends with a line saying backup monitoring
   is now off until you choose a folder again (with no folder there is no finding at all, so it says so).
5. Set the drill date to a day more than 90 days ago: a reminder appears. Tap "I did a drill today": it clears.
6. Old-format export beside an older FWX one: put a Tunnels `.tsnap` file (its last-modified date more than your limit ago) and a Tunnels
   `.fwx` export older still in the folder. Tunnels shows **stale** with the `.tsnap` file's date and a warning, not "date unknown".
