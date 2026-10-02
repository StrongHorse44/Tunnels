package io.github.stronghorse44.tunnels.devicecheck

import io.github.stronghorse44.tunnels.attestation.SiliconKeys
import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import io.github.stronghorse44.tunnels.watchrules.WatchStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A per-app permission toggle as seen through PackageManager: Tunnels' own reading and every app's. */
data class ToggleProbe(
    /** Tunnels' own requested-permission flag (granted bit), or null when Tunnels does not request it. */
    val selfFlag: Boolean?,
    /** What Android answers when Tunnels checks the permission for itself. */
    val selfGranted: Boolean,
    /** Other apps requesting the permission. */
    val appsRequesting: Int,
    /** Of those, apps whose flag reads not granted: the toggle is off for them. */
    val appsOff: Int,
)

/** The Private DNS state of the network Traffic forwards to. */
sealed interface PrivateDns {
    data object Off : PrivateDns
    data object Automatic : PrivateDns
    data class Strict(val host: String) : PrivateDns
    data object Unknown : PrivateDns
}

/**
 * Turns raw readings into verdicts with a plain next step. Pure: the Android screen gathers the readings.
 * Every check names what Tunnels relies on the reading for.
 */
object DeviceChecks {
    const val ACTION_INBOX = "io.github.stronghorse44.tunnels.action.INBOX"
    const val ACTION_SNAPSHOTS = "io.github.stronghorse44.tunnels.action.SNAPSHOTS"
    const val ACTION_PAIRING = "io.github.stronghorse44.tunnels.action.PAIRING"

    // android.provider.Settings actions, spelled out so this module stays plain Kotlin.
    const val SETTINGS_ALL_APPS = "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS"
    const val SETTINGS_APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val SETTINGS_VPN = "android.settings.VPN_SETTINGS"
    const val SETTINGS_NETWORK = "android.settings.WIRELESS_SETTINGS"
    const val SETTINGS_SECURITY = "android.settings.SECURITY_SETTINGS"

    /** Stale attestation readings: Silicon should be rescanned after an OS update. */
    val SILICON_FRESH: Duration = Duration.ofDays(31)

    fun networkToggle(p: ToggleProbe, grapheneOs: Boolean): CheckResult {
        val id = "toggle_network"
        val title = "Network toggle is readable"
        if (p.selfFlag != null && p.selfFlag != p.selfGranted) {
            return CheckResult(
                id, title, CheckStatus.FAIL,
                "Tunnels' own Network flag reads ${onOff(p.selfFlag)} but Android says ${onOff(p.selfGranted)}. The Permissions tunnel's " +
                    "Network column cannot be trusted on this build.",
            )
        }
        if (!grapheneOs) {
            return CheckResult(id, title, CheckStatus.NOTE, "This does not look like GrapheneOS: there is no per-app Network toggle, so every app reads on.")
        }
        return when {
            p.appsOff > 0 -> CheckResult(
                id, title, CheckStatus.PASS,
                "${p.appsOff} of ${p.appsRequesting} apps read as Network off, and Tunnels' own toggle reads ${onOff(p.selfGranted)}. " +
                    "Permissions, Crossroads and the Network-on findings rely on this reading.",
            )
            else -> CheckResult(
                id, title, CheckStatus.TODO,
                "Every app reads Network on, so this cannot be confirmed yet. Turn Network off for one app (App info, Permissions, " +
                    "Network), then run the checks again: it should read off.",
                CheckAction.OpenSettings(SETTINGS_ALL_APPS, "All apps"),
            )
        }
    }

    fun sensorsToggle(p: ToggleProbe, permissionDefined: Boolean): CheckResult {
        val id = "toggle_sensors"
        val title = "Sensors toggle is readable"
        if (!permissionDefined) {
            return CheckResult(id, title, CheckStatus.NOTE, "This OS has no Sensors permission (it is GrapheneOS's), so the Sensors column reads unknown.")
        }
        return when {
            p.appsOff > 0 -> CheckResult(id, title, CheckStatus.PASS, "${p.appsOff} of ${p.appsRequesting} apps read as Sensors off.")
            p.appsRequesting == 0 -> CheckResult(id, title, CheckStatus.WARN, "The Sensors permission exists, but no app lists it, so Tunnels cannot read the toggle per app.")
            else -> CheckResult(
                id, title, CheckStatus.TODO,
                "Every app reads Sensors on. Turn Sensors off for one app, then run the checks again: it should read off.",
                CheckAction.OpenSettings(SETTINGS_ALL_APPS, "All apps"),
            )
        }
    }

    /** From Silicon's latest observations (key -> value), or null when Silicon was never scanned. */
    fun verifiedBoot(silicon: Map<String, String>?, takenAt: Instant?, now: Instant): CheckResult {
        val id = "verified_boot"
        val title = "Verified boot and attestation"
        val open = CheckAction.OpenTunnel(SiliconKeys.TUNNEL_ID, "Open Silicon")
        if (silicon == null || takenAt == null) {
            return CheckResult(id, title, CheckStatus.TODO, "Silicon has not been scanned. Scan it to read the hardware's own report on how the phone booted.", open)
        }
        val day = takenAt.atOffset(ZoneOffset.UTC).toLocalDate()
        val stale = takenAt.isBefore(now.minus(SILICON_FRESH))
        val asOf = if (stale) " (reading from $day: scan Silicon again after an update)" else ""
        silicon[SiliconKeys.ATTESTATION_ERROR]?.let {
            return CheckResult(id, title, CheckStatus.WARN, "The hardware attestation could not be read: $it.$asOf", open)
        }
        val state = silicon[SiliconKeys.BOOT_STATE]
        val locked = silicon[SiliconKeys.BOOT_LOCKED] == "true"
        val name = silicon[SiliconKeys.BOOT_KEY_NAME].orEmpty()
        val chain = silicon[SiliconKeys.CHAIN_VERIFIED]
        if (state != "Verified" || !locked) {
            val why = buildList {
                if (state != "Verified") add("the boot state is ${state ?: "unknown"}")
                if (!locked) add("the bootloader is unlocked")
            }.joinToString(" and ")
            return CheckResult(id, title, CheckStatus.FAIL, "The hardware reports that $why. Anyone with the phone can change what it runs.$asOf", open)
        }
        val chainText = if (chain == "true") ", chain verified to ${silicon[SiliconKeys.CHAIN_ROOT] ?: "a Google root"}" else ", but the certificate chain did not verify ($chain)"
        return when {
            name.startsWith("GrapheneOS") -> CheckResult(
                id, title, if (chain == "true" && !stale) CheckStatus.PASS else CheckStatus.WARN,
                "Booted $name with a locked bootloader$chainText.$asOf",
                open,
            )
            name.isBlank() || name == "unknown" -> CheckResult(
                id, title, CheckStatus.WARN,
                "Verified boot with a key Tunnels does not know. Compare it with the key GrapheneOS publishes for this model, " +
                    "or with the Auditor app, before trusting it.$asOf",
                open,
            )
            else -> CheckResult(id, title, CheckStatus.NOTE, "Booted $name with a locked bootloader$chainText.$asOf", open)
        }
    }

    /** [level] is "StrongBox", "TEE", "software" or "unknown". */
    fun storeKey(level: String): CheckResult {
        val id = "store_key"
        val title = "Encrypted store key"
        return when (level) {
            "StrongBox" -> CheckResult(id, title, CheckStatus.PASS, "The key that unlocks Tunnels' store lives in the StrongBox security chip.")
            "TEE" -> CheckResult(id, title, CheckStatus.PASS, "The store key lives in the processor's trusted environment (StrongBox was not available when it was made).")
            "software" -> CheckResult(id, title, CheckStatus.WARN, "The store key is software-backed on this phone: the store is encrypted, but the key has no hardware protection.")
            else -> CheckResult(id, title, CheckStatus.NOTE, "The store key's protection could not be read.")
        }
    }

    fun packageVisibility(visible: Int): CheckResult {
        val id = "package_visibility"
        val title = "Every app is visible"
        return if (visible >= MIN_VISIBLE) CheckResult(id, title, CheckStatus.PASS, "Tunnels sees $visible installed packages, system ones included.")
        else CheckResult(id, title, CheckStatus.FAIL, "Tunnels sees only $visible packages, so the app tunnels would miss apps. Package visibility is limited on this build.")
    }

    private const val MIN_VISIBLE = 40

    fun vpn(connected: Boolean, ours: Boolean): CheckResult {
        val id = "vpn"
        val title = "Room for a Traffic session"
        return when {
            !connected -> CheckResult(id, title, CheckStatus.PASS, "No VPN is connected, so a Traffic session can start.")
            ours -> CheckResult(id, title, CheckStatus.NOTE, "Tunnels' own Traffic session is running.")
            else -> CheckResult(
                id, title, CheckStatus.WARN,
                "Another VPN is connected. Traffic sessions refuse to start beside it and never disconnect it for you: turn its " +
                    "Always-on off, disconnect it, run the session, then reconnect.",
                CheckAction.OpenSettings(SETTINGS_VPN, "VPN settings"),
            )
        }
    }

    fun privateDns(mode: PrivateDns): CheckResult {
        val id = "private_dns"
        val title = "Traffic sees every lookup"
        return when (mode) {
            PrivateDns.Off -> CheckResult(id, title, CheckStatus.PASS, "Private DNS is off or not encrypting, so lookups reach a Traffic session in the clear.")
            PrivateDns.Automatic -> CheckResult(
                id, title, CheckStatus.PASS,
                "Private DNS is automatic: inside a session Android falls back to plain lookups, which Traffic sees, counts and can block.",
            )
            is PrivateDns.Strict -> CheckResult(
                id, title, CheckStatus.WARN,
                "Private DNS is set to ${mode.host}. Lookups go there encrypted, around a Traffic session, so Traffic sees and blocks " +
                    "nothing. Switch Private DNS to Automatic before a session if you want it to count and block.",
                CheckAction.OpenSettings(SETTINGS_NETWORK, "Network settings"),
            )
            PrivateDns.Unknown -> CheckResult(id, title, CheckStatus.NOTE, "No network is connected, so Private DNS could not be read.")
        }
    }

    /** One row per special access a tunnel uses. */
    fun specialAccess(tunnelId: String, tunnelTitle: String, accessId: String, label: String, granted: Boolean, restricted: Boolean, installedFromFile: Boolean): CheckResult {
        val id = "access_${tunnelId}_$accessId"
        val title = "$label ($tunnelTitle)"
        if (granted) return CheckResult(id, title, CheckStatus.PASS, "Granted.")
        val hint = if (restricted && installedFromFile) {
            " Tunnels was installed from a file, so Android may answer \"App was denied access\": then open App info, tap the menu, " +
                "choose Allow restricted settings and try again."
        } else {
            ""
        }
        return CheckResult(
            id, title, CheckStatus.TODO,
            "Not granted: only $tunnelTitle needs it, and only when you open it.$hint",
            CheckAction.GrantAccess(tunnelId, accessId, "Open setting"),
        )
    }

    fun backgroundChecks(
        settings: WatchSettings,
        status: WatchStatus,
        scheduled: Boolean,
        notificationsAllowed: Boolean,
        batteryUnrestricted: Boolean,
        now: Instant,
    ): CheckResult {
        val id = "background_checks"
        val title = "Background checks"
        val inbox = CheckAction.OpenScreen(ACTION_INBOX, "Open Findings")
        if (!settings.enabled) {
            return CheckResult(id, title, CheckStatus.TODO, "Off. Switch them on in Findings to hear about a new CA, a permission granted or a changed signing key without opening Tunnels.", inbox)
        }
        if (!scheduled) return CheckResult(id, title, CheckStatus.WARN, "On, but Android has no check scheduled. Opening Findings re-arms it.", inbox)
        if (status.neverRan) return CheckResult(id, title, CheckStatus.NOTE, "Scheduled every ${settings.intervalHours} h; no check has run yet.", inbox)
        val ago = Duration.ofMillis(now.toEpochMilli() - status.lastRunAt)
        val late = ago > Duration.ofHours(settings.intervalHours * 2L + 6)
        val notes = buildList {
            if (!notificationsAllowed) add("notifications are off, so findings wait in the inbox")
            if (!batteryUnrestricted) add("battery use is optimized, so Android may hold checks back while the phone is idle")
        }
        val tail = if (notes.isEmpty()) "" else " Note: ${notes.joinToString("; ")}."
        return if (late) {
            CheckResult(
                id, title, CheckStatus.WARN,
                "The last check ran ${hours(ago)} ago, longer than the ${settings.intervalHours} h schedule allows: Android is holding " +
                    "it back. Setting Tunnels' battery use to Unrestricted (App info, Battery) lets checks run on time.$tail",
                CheckAction.OpenSettings(SETTINGS_APP_DETAILS, "App info", forThisApp = true),
            )
        } else {
            CheckResult(id, title, CheckStatus.PASS, "Every ${settings.intervalHours} h; the last one ran ${hours(ago)} ago.$tail", inbox)
        }
    }

    const val AUDITOR = "app.attestation.auditor"

    /**
     * Whether a check from outside this phone is possible. Silicon reads attestation from the phone itself, which a
     * compromised OS could fake; a second phone (Tunnels' own pairing, or GrapheneOS's Auditor) verifies it from outside.
     */
    fun secondPhone(auditorInstalled: Boolean, pairedPhones: Int): CheckResult {
        val id = "second_phone"
        val title = "Verified from a second phone"
        val auditor = if (auditorInstalled) " Auditor is installed too and does the same between two Auditor apps." else
            " GrapheneOS's Auditor app does the same, if you prefer it."
        return if (pairedPhones > 0) {
            CheckResult(
                id, title, CheckStatus.NOTE,
                "This phone verifies ${pairedPhones} paired phone${if (pairedPhones == 1) "" else "s"}. To have this phone checked, let a " +
                    "phone you trust run Second phone on it now and then: nothing on a compromised phone can fake that check.$auditor",
                CheckAction.OpenScreen(ACTION_PAIRING, "Open Second phone"),
            )
        } else {
            CheckResult(
                id, title, CheckStatus.TODO,
                "Silicon reads this phone's attestation on this phone, and a compromised OS could fake what an app here sees. A second " +
                    "phone you trust can check it from outside with two QR codes and no network.$auditor",
                CheckAction.OpenScreen(ACTION_PAIRING, "Open Second phone"),
            )
        }
    }

    fun appLock(available: Boolean, enabled: Boolean): CheckResult {
        val id = "app_lock"
        val title = "App lock"
        return when {
            enabled -> CheckResult(id, title, CheckStatus.PASS, "On: Tunnels asks for your screen lock after 30 seconds in the background.")
            !available -> CheckResult(id, title, CheckStatus.NOTE, "Unavailable until the phone has a screen lock.", CheckAction.OpenSettings(SETTINGS_SECURITY, "Security settings"))
            else -> CheckResult(
                id, title, CheckStatus.NOTE,
                "Off: anyone holding the unlocked phone can read what Tunnels found. Turn it on in Snapshots.",
                CheckAction.OpenScreen(ACTION_SNAPSHOTS, "Open Snapshots"),
            )
        }
    }

    /** What only a person with the phone (and sometimes a tracker tag) can confirm. */
    val byHand: List<CheckResult> = listOf(
        CheckResult(
            "hand_tags", "A Bluetooth tag is recognised", CheckStatus.TODO,
            "With an AirTag, SmartTag, Tile or Find Hub tag nearby, scan Surroundings: it should be listed with its maker. In find-it " +
                "mode, Play sound should make it ring and the card should show its battery.",
            CheckAction.OpenTunnel("surroundings", "Open Surroundings"),
        ),
        CheckResult(
            "hand_following", "A tag that travels with you is flagged", CheckStatus.TODO,
            "Carry a tag between two places at least a few hundred metres apart and scan Surroundings at each: it should be marked as " +
                "travelling with you. Note whether a Tile or SmartTag keeps its identity for 30 minutes while away from its owner.",
            CheckAction.OpenTunnel("surroundings", "Open Surroundings"),
        ),
        CheckResult(
            "hand_fix", "Location fixes indoors", CheckStatus.TODO,
            "Scan Surroundings indoors and note how long the fix takes, or whether it says no fix: then movement is judged by time alone.",
            CheckAction.OpenTunnel("surroundings", "Open Surroundings"),
        ),
        CheckResult(
            "hand_session", "A Traffic session with blocking", CheckStatus.TODO,
            "With no other VPN connected, switch blocking on, start a session, open an app with ads for a minute and stop: Traffic " +
                "should list the app, its domains and blocked lookups, and the app should still work.",
            CheckAction.OpenTunnel("traffic", "Open Traffic"),
        ),
    )

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private fun hours(d: Duration): String {
        val h = d.toHours()
        return if (h < 1) "${d.toMinutes().coerceAtLeast(0)} min" else if (h < 48) "$h h" else "${h / 24} days"
    }
}
