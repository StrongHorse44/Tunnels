# Emissions

A Network tunnel (the fourth bead on the Network ring) for what the phone itself transmits, how much of it is off,
and how to silence it. Built from RJ's answers of 2026-10-10: a tunnel with findings, Shizuku toggles with a Settings
fallback, all four extra readings (background scanning, hotspot and tethering, apps using radios, location) and all
four extras (Restore, a Quick Settings tile, a Faraday bag test, findings in the inbox).

Logic: `core/emissions` (plain Kotlin, unit-tested). Android adapter: `tunnels/emissions`.

## What it reads

| Transmitter | Without Shizuku | With Shizuku |
| --- | --- | --- |
| Cellular | airplane mode (`Settings.Global`), SIM state, mobile data | same |
| Wi-Fi | `WifiManager.isWifiEnabled` | same |
| Wi-Fi scanning | `WifiManager.isScanAlwaysAvailable` | same |
| Bluetooth | `BluetoothAdapter.isEnabled` | same |
| Bluetooth scanning | `ble_scan_always_enabled` when Android lets an app read it (usually not) | `settings get global ble_scan_always_enabled` |
| NFC | `NfcAdapter.isEnabled` | same |
| UWB | present or absent only | `cmd uwb status` (**unconfirmed parser**) |
| Hotspot and tethering | unknown | `dumpsys tethering`, "Tether state" section (**unconfirmed parser**) |
| Location (GNSS) | `LocationManager.isLocationEnabled`; receives only, never counted as an emission | same |
| Apps using radios | — | `dumpsys appops --op <OP>` for Bluetooth, Wi-Fi, location and UWB ops; used in the last 15 minutes |

A scan needs no runtime permission. The quiet level is: Silent (nothing on, nothing unknown), Quiet, not all read
(nothing on that Tunnels can read), Partly quiet, or Transmitting. Without Shizuku the hotspot is unknown, so the phone is
never called Silent.

## What it stores (rule 3)

States (`on`, `off`, `absent`, `unknown`), where each was read, airplane mode, SIM present or not, mobile data, the
location switch, the hotspot kinds (`wifi`, `usb`, `bluetooth`), counts per state, the quiet level, whether Shizuku was
ready, and how many apps used each kind of radio. Never an app name, address, SSID, cell or time: the apps list is held
in memory for the screen. The Restore memory is one settings row, `emissions.restore` (transmitter ids), in the encrypted
store and not in exports. The bag test stores nothing.

## Silence and Restore

With Shizuku ready, Silence runs these through Deep mode's shell (`ShizukuShell`, uid 2000), first command first; a
command whose output reads as an error (`ShellOutput`) falls through to the next. Every change is checked by reading the
states again, for up to about four seconds.

| Transmitter | Off | On (Restore) |
| --- | --- | --- |
| Hotspot | `cmd wifi stop-softap` (Wi-Fi hotspot only) | not restored: it needs its name and password |
| Cellular | `cmd connectivity airplane-mode enable` | `... disable` |
| Wi-Fi | `cmd wifi set-wifi-enabled disabled`, then `svc wifi disable` | `... enabled` / `svc wifi enable` |
| Wi-Fi scanning | `cmd wifi set-scan-always-available disabled`, then `settings put global wifi_scan_always_enabled 0` | `... enabled` / `... 1` |
| Bluetooth | `svc bluetooth disable`, then `cmd bluetooth_manager disable` | `... enable` |
| Bluetooth scanning | `settings put global ble_scan_always_enabled 0` | `... 1` |
| NFC | `svc nfc disable` | `svc nfc enable` |
| UWB | `cmd uwb disable-uwb` | `cmd uwb enable-uwb` |

Without Shizuku each row opens Android's own panel or Settings screen (`EmitterSettings`): the Wi-Fi and NFC panels,
Airplane mode, Bluetooth, Location for both kinds of scanning, Network & internet for the hotspot, and Settings for UWB
(no public action opens its page). "Remember what is on" keeps the list so the Restore card can walk back through it.

## Findings

State findings, cleared when the transmitter goes off; a radio in use is never one. A reading through an unconfirmed
parser raises none.

| Kind | Severity | When |
| --- | --- | --- |
| `EMISSION_AIRPLANE_LEAK` | NOTICE | airplane mode on, Wi-Fi or Bluetooth still on |
| `EMISSION_SCANNING_WHILE_OFF` | INFO | Wi-Fi (or Bluetooth) off, its scanning on |
| `EMISSION_NFC_ON` | INFO | NFC on |
| `EMISSION_UWB_ON` | INFO | UWB on (needs the UWB parser confirmed) |
| `EMISSION_HOTSPOT_ON` | INFO | a hotspot or tethering up (needs the tethering parser confirmed) |

Each has the Settings route as its action, and "Turn off" in front of it when Shizuku is ready (rule 7). Deep mode's
`SCAN_ALWAYS_ENABLED` still fires when scanning is on whatever the radios do; the two overlap when a radio is off.

## Quick Settings tile

"Silence", lit when nothing the app APIs can see is on. It never binds the shell to draw itself. With Shizuku a tap
silences, or restores what the last Silence turned off (after an unlock when the phone is locked); without it a tap
opens the tunnel. The work runs in a process-wide scope so closing the shade does not cut it short.

## Faraday bag test

Tests the bag, not the phone: radios stay on, Tunnels listens 10 s outside, gives 10 s to seal the phone in, listens
45 s inside (the first 15 s ignored while the modem notices), buzzes once when it starts listening inside and twice when
done, and compares. Cellular: the modem's service state and signal level (telephony callback, no permission). Wi-Fi: one
fresh scan per window (NEARBY_WIFI_DEVICES and the location switch) and whether Wi-Fi is the connected network.
Bluetooth: devices heard in an unfiltered scan (BLUETOOTH_SCAN; the screen stays on, or Android pauses the scan). The two
permissions are asked for when the test starts; without them those parts are "not tested". Nothing is stored.

## Device checks (on a test phone)

1. Silence with Shizuku: each row in the Off column turns its transmitter off, and the state read back agrees. Note which
   first command failed and fell through; flip `ShellStep.confirmed` for the ones that work.
2. Restore brings back exactly what Silence turned off; airplane mode first.
3. `cmd uwb status` (if the phone has UWB) and `dumpsys tethering` with a hotspot on: paste the output, fix the parser if
   needed, then set `ShellReads.UWB_CONFIRMED` / `TETHERING_CONFIRMED`.
4. Deep mode's "Turn scanning off" (`settings put global wifi_scan_always_enabled 0`): does Wi-Fi scanning really turn
   off? Emissions reads the state through `WifiManager`, so its row shows the truth. Fix Deep mode only if it does not.
5. Whether `ble_scan_always_enabled` is readable without Shizuku on GrapheneOS (expected: no).
6. Pixel powered-off Find Hub beacon: whether GrapheneOS leaves it unprovisioned (the full-silence guide says so, unverified).
7. The bag test with a known-good bag and with an ordinary pouch; a call to the phone in the bag agrees with the result.
8. The tile: silence from the shade, close the shade at once, and check the work still finished.
