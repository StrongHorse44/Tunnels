package io.github.stronghorse44.tunnels.syspkg

/** What part of the system a package belongs to. */
enum class PackageCategory(val label: String) {
    UI("ui"),
    TELEPHONY("telephony"),
    CONNECTIVITY("connectivity"),
    MEDIA("media"),
    PROVIDER("provider"),
    SECURITY("security"),
    INPUT("input"),
    UPDATE("update"),
    STORE("store"),
    GRAPHENEOS("grapheneos"),
    GOOGLE("google"),
    OTHER("other"),
    ;

    companion object {
        fun byLabel(label: String): PackageCategory? = entries.firstOrNull { it.label == label }
    }
}

/** What happens if the user disables the package from its app details screen. */
enum class DisableRisk(val label: String, val explanation: String) {
    SAFE("safe", "Safe to disable; only this feature stops working."),
    CAUTION("caution", "Disabling breaks features other apps or the system rely on."),
    NEVER("never", "Never disable: the phone may stop booting or lose calls, security or settings."),
    ;

    companion object {
        fun byLabel(label: String): DisableRisk? = entries.firstOrNull { it.label == label }
    }
}

/** Who ships a package, judged from its name alone. */
enum class Namespace(val label: String) {
    AOSP("aosp"),
    GRAPHENEOS("grapheneos"),
    GOOGLE("google"),
    OTHER("other"),
    ;

    companion object {
        fun byLabel(label: String): Namespace? = entries.firstOrNull { it.label == label }
    }
}

data class KnownPackage(
    val packageName: String,
    /** One line a non-expert can read. */
    val purpose: String,
    val category: PackageCategory,
    val disableRisk: DisableRisk,
)

/**
 * Knowledge base of common AOSP, GrapheneOS and Google system packages. Only packages whose
 * identity is certain are listed; everything else is reported as unknown, which is fine.
 */
object SystemPackageKb {
    private fun p(name: String, purpose: String, category: PackageCategory, risk: DisableRisk) = KnownPackage(name, purpose, category, risk)

    val all: List<KnownPackage> = listOf(
        // Framework and core UI
        p("android", "The Android framework itself: system server, core resources and permissions.", PackageCategory.OTHER, DisableRisk.NEVER),
        p("com.android.systemui", "Status bar, lock screen, notification shade, quick settings and recents.", PackageCategory.UI, DisableRisk.NEVER),
        p("com.android.settings", "The Settings app.", PackageCategory.UI, DisableRisk.NEVER),
        p("com.android.settings.intelligence", "Search and suggestions inside Settings.", PackageCategory.UI, DisableRisk.CAUTION),
        p("com.android.intentresolver", "The share sheet and the 'open with' chooser.", PackageCategory.UI, DisableRisk.NEVER),
        p("com.android.launcher3", "The AOSP home screen and app drawer.", PackageCategory.UI, DisableRisk.CAUTION),
        p("com.android.documentsui", "The Files app and the system file picker.", PackageCategory.UI, DisableRisk.NEVER),
        p("com.android.soundpicker", "Picker for ringtones, notification and alarm sounds.", PackageCategory.UI, DisableRisk.SAFE),
        p("com.android.wallpaper.livepicker", "Chooser for live wallpapers.", PackageCategory.UI, DisableRisk.SAFE),
        p("com.android.wallpaperbackup", "Backs up and restores the wallpaper.", PackageCategory.OTHER, DisableRisk.SAFE),
        p("com.android.dreams.basic", "Basic screen saver (colors).", PackageCategory.UI, DisableRisk.SAFE),
        p("com.android.dreams.phototable", "Photo table screen saver.", PackageCategory.UI, DisableRisk.SAFE),
        p("com.android.egg", "The Android version Easter egg.", PackageCategory.OTHER, DisableRisk.SAFE),
        p("com.android.htmlviewer", "Minimal viewer for local HTML files.", PackageCategory.OTHER, DisableRisk.SAFE),
        p("com.android.traceur", "System Tracing developer tool.", PackageCategory.OTHER, DisableRisk.SAFE),
        p("com.android.shell", "The adb shell, bug reports and developer commands.", PackageCategory.SECURITY, DisableRisk.NEVER),
        p("com.android.ext.services", "Notification ranking, autofill and text classification helpers.", PackageCategory.OTHER, DisableRisk.NEVER),
        p("com.android.ext.shared", "Shared support library for other system apps.", PackageCategory.OTHER, DisableRisk.NEVER),
        p("com.android.server.deviceconfig", "Stores feature flags for system components.", PackageCategory.OTHER, DisableRisk.NEVER),
        p("com.android.sdksandbox", "Isolated runtime for advertising SDKs (Privacy Sandbox).", PackageCategory.OTHER, DisableRisk.NEVER),
        p("com.android.adservices.api", "Privacy Sandbox advertising APIs (topics, attribution).", PackageCategory.OTHER, DisableRisk.CAUTION),
        p("com.android.ondevicepersonalization.services", "On-device personalization service of the Privacy Sandbox.", PackageCategory.OTHER, DisableRisk.CAUTION),
        p("com.android.federatedcompute", "Federated computation service used by on-device personalization.", PackageCategory.OTHER, DisableRisk.CAUTION),
        p("com.android.virtualmachine.res", "Resources for protected virtual machines (pKVM).", PackageCategory.OTHER, DisableRisk.NEVER),
        p("com.android.cts.ctsshim", "Empty placeholder used by compatibility tests.", PackageCategory.OTHER, DisableRisk.SAFE),
        p("com.android.cts.priv.ctsshim", "Empty privileged placeholder used by compatibility tests.", PackageCategory.OTHER, DisableRisk.SAFE),

        // Telephony
        p("com.android.phone", "Telephony service: calls, SIM, signal and mobile data settings.", PackageCategory.TELEPHONY, DisableRisk.NEVER),
        p("com.android.server.telecom", "Routes calls between the dialer, the carrier and Bluetooth.", PackageCategory.TELEPHONY, DisableRisk.NEVER),
        p("com.android.dialer", "The AOSP Phone app.", PackageCategory.TELEPHONY, DisableRisk.CAUTION),
        p("com.android.messaging", "The AOSP SMS and MMS app.", PackageCategory.TELEPHONY, DisableRisk.CAUTION),
        p("com.android.contacts", "The AOSP Contacts app.", PackageCategory.OTHER, DisableRisk.CAUTION),
        p("com.android.carrierconfig", "Per-carrier settings loaded from the SIM.", PackageCategory.TELEPHONY, DisableRisk.NEVER),
        p("com.android.carrierdefaultapp", "Handles carrier captive portals and data-plan notices.", PackageCategory.TELEPHONY, DisableRisk.CAUTION),
        p("com.android.cellbroadcastreceiver", "Emergency and public safety alerts (cell broadcast).", PackageCategory.TELEPHONY, DisableRisk.CAUTION),
        p("com.android.cellbroadcastservice", "Receives cell broadcast messages from the modem.", PackageCategory.TELEPHONY, DisableRisk.CAUTION),
        p("com.android.stk", "SIM Toolkit: menus the SIM card provides.", PackageCategory.TELEPHONY, DisableRisk.SAFE),
        p("com.android.ons", "Opportunistic network service for carrier eSIM profiles.", PackageCategory.TELEPHONY, DisableRisk.CAUTION),
        p("com.android.emergency", "Emergency information and emergency call screen.", PackageCategory.TELEPHONY, DisableRisk.CAUTION),
        p("com.android.simappdialog", "Prompts to install the carrier's app after a SIM is inserted.", PackageCategory.TELEPHONY, DisableRisk.SAFE),
        p("com.android.imsserviceentitlement", "Checks carrier entitlement for Wi-Fi calling and VoLTE.", PackageCategory.TELEPHONY, DisableRisk.CAUTION),

        // Connectivity
        p("com.android.bluetooth", "The Bluetooth stack.", PackageCategory.CONNECTIVITY, DisableRisk.NEVER),
        p("com.android.bluetoothmidiservice", "Bluetooth MIDI instrument support.", PackageCategory.CONNECTIVITY, DisableRisk.SAFE),
        p("com.android.nfc", "The NFC service: tags, payments and Android Beam-style sharing.", PackageCategory.CONNECTIVITY, DisableRisk.CAUTION),
        p("com.android.apps.tag", "Viewer for scanned NFC tags.", PackageCategory.CONNECTIVITY, DisableRisk.SAFE),
        p("com.android.se", "Secure element service for payment and SIM applets.", PackageCategory.SECURITY, DisableRisk.CAUTION),
        p("com.android.networkstack", "Network stack: DHCP, IP configuration and connectivity checks.", PackageCategory.CONNECTIVITY, DisableRisk.NEVER),
        p("com.android.networkstack.inprocess", "In-process variant of the network stack.", PackageCategory.CONNECTIVITY, DisableRisk.NEVER),
        p("com.android.networkstack.tethering", "Tethering and hotspot service.", PackageCategory.CONNECTIVITY, DisableRisk.NEVER),
        p("com.android.networkstack.permissionconfig", "Grants the network stack its permissions.", PackageCategory.CONNECTIVITY, DisableRisk.NEVER),
        p("com.android.captiveportallogin", "Sign-in page for Wi-Fi captive portals.", PackageCategory.CONNECTIVITY, DisableRisk.CAUTION),
        p("com.android.hotspot2.osulogin", "Passpoint (Hotspot 2.0) online sign-up.", PackageCategory.CONNECTIVITY, DisableRisk.SAFE),
        p("com.android.wifi.resources", "Resources and overlays for the Wi-Fi service.", PackageCategory.CONNECTIVITY, DisableRisk.NEVER),
        p("com.android.wifi.dialog", "Wi-Fi dialogs such as WPS and P2P prompts.", PackageCategory.CONNECTIVITY, DisableRisk.NEVER),
        p("com.android.uwb.resources", "Resources for the ultra-wideband service.", PackageCategory.CONNECTIVITY, DisableRisk.CAUTION),
        p("com.android.vpndialogs", "The dialogs that ask you to allow a VPN app.", PackageCategory.CONNECTIVITY, DisableRisk.NEVER),
        p("com.android.proxyhandler", "Handles HTTP proxy settings for apps.", PackageCategory.CONNECTIVITY, DisableRisk.CAUTION),
        p("com.android.pacprocessor", "Runs proxy auto-config (PAC) scripts.", PackageCategory.CONNECTIVITY, DisableRisk.CAUTION),
        p("com.android.companiondevicemanager", "Pairs companion devices such as watches with apps.", PackageCategory.CONNECTIVITY, DisableRisk.CAUTION),
        p("com.android.location.fused", "The fused location provider.", PackageCategory.CONNECTIVITY, DisableRisk.NEVER),
        p("com.android.mtp", "Media transfer (MTP) access to connected devices.", PackageCategory.CONNECTIVITY, DisableRisk.CAUTION),

        // Media
        p("com.android.camera2", "The AOSP Camera app.", PackageCategory.MEDIA, DisableRisk.CAUTION),
        p("com.android.gallery3d", "The AOSP Gallery app.", PackageCategory.MEDIA, DisableRisk.CAUTION),
        p("com.android.musicfx", "Audio effects panel (equalizer).", PackageCategory.MEDIA, DisableRisk.SAFE),
        p("com.android.providers.media.module", "The media store: indexes photos, videos and audio for every app.", PackageCategory.PROVIDER, DisableRisk.NEVER),

        // Content providers
        p("com.android.providers.settings", "Database behind every system setting.", PackageCategory.PROVIDER, DisableRisk.NEVER),
        p("com.android.providers.contacts", "Database of contacts and call log.", PackageCategory.PROVIDER, DisableRisk.NEVER),
        p("com.android.providers.telephony", "Database of SMS, MMS and APNs.", PackageCategory.PROVIDER, DisableRisk.NEVER),
        p("com.android.providers.calendar", "Database of calendars and events.", PackageCategory.PROVIDER, DisableRisk.CAUTION),
        p("com.android.providers.downloads", "The download manager that apps use for background downloads.", PackageCategory.PROVIDER, DisableRisk.CAUTION),
        p("com.android.providers.blockednumber", "Database of blocked phone numbers.", PackageCategory.PROVIDER, DisableRisk.NEVER),
        p("com.android.providers.userdictionary", "Personal dictionary for keyboards.", PackageCategory.PROVIDER, DisableRisk.CAUTION),
        p("com.android.externalstorage", "Exposes internal and removable storage to the file picker.", PackageCategory.PROVIDER, DisableRisk.NEVER),

        // Security and policy
        p("com.android.permissioncontroller", "Permission prompts, auto-reset and the privacy dashboard.", PackageCategory.SECURITY, DisableRisk.NEVER),
        p("com.android.packageinstaller", "Installs and uninstalls apps, with the confirmation screens.", PackageCategory.SECURITY, DisableRisk.NEVER),
        p("com.android.certinstaller", "Installs certificates from files.", PackageCategory.SECURITY, DisableRisk.NEVER),
        p("com.android.keychain", "Stores credentials and user-installed certificates.", PackageCategory.SECURITY, DisableRisk.NEVER),
        p("com.android.credentialmanager", "Credential Manager: passkeys and password autofill UI.", PackageCategory.SECURITY, DisableRisk.NEVER),
        p("com.android.statementservice", "Verifies app links against the websites they claim.", PackageCategory.SECURITY, DisableRisk.CAUTION),
        p("com.android.managedprovisioning", "Sets up work profiles and managed devices.", PackageCategory.SECURITY, DisableRisk.CAUTION),
        p("com.android.rkpdapp", "Remote key provisioning for hardware attestation keys.", PackageCategory.SECURITY, DisableRisk.NEVER),
        p("com.android.safetycenter.resources", "Resources for Safety Center in Settings.", PackageCategory.SECURITY, DisableRisk.NEVER),
        p("com.android.backupconfirm", "Confirmation screens for full backups and restores over adb.", PackageCategory.OTHER, DisableRisk.CAUTION),
        p("com.android.sharedstoragebackup", "Backup agent for shared storage.", PackageCategory.OTHER, DisableRisk.SAFE),
        p("com.android.localtransport", "Local (on-device) backup transport used for testing.", PackageCategory.OTHER, DisableRisk.SAFE),

        // Input
        p("com.android.inputdevices", "Keyboard layouts for physical keyboards.", PackageCategory.INPUT, DisableRisk.NEVER),
        p("com.android.inputmethod.latin", "The AOSP keyboard.", PackageCategory.INPUT, DisableRisk.CAUTION),

        // Updates and system maintenance
        p("com.android.dynsystem", "Dynamic System Updates: boots a system image without flashing.", PackageCategory.UPDATE, DisableRisk.CAUTION),
        p("com.android.storagemanager", "Frees up storage automatically.", PackageCategory.OTHER, DisableRisk.SAFE),
        p("com.android.printspooler", "Print queue and print dialogs.", PackageCategory.OTHER, DisableRisk.SAFE),
        p("com.android.bips", "Default print service for IPP printers.", PackageCategory.OTHER, DisableRisk.SAFE),
        p("com.android.healthconnect.controller", "Health Connect: shares health data between apps.", PackageCategory.OTHER, DisableRisk.CAUTION),
        p("com.android.deskclock", "The AOSP Clock: alarms, timers and stopwatch.", PackageCategory.OTHER, DisableRisk.CAUTION),
        p("com.android.calculator2", "The AOSP Calculator.", PackageCategory.OTHER, DisableRisk.SAFE),
        p("com.android.calendar", "The AOSP Calendar app.", PackageCategory.OTHER, DisableRisk.CAUTION),

        // GrapheneOS
        p("app.grapheneos.setupwizard", "GrapheneOS first-boot setup wizard.", PackageCategory.GRAPHENEOS, DisableRisk.NEVER),
        p("app.grapheneos.apps", "The GrapheneOS app repository client (App Store), also used to install sandboxed Google Play.", PackageCategory.GRAPHENEOS, DisableRisk.CAUTION),
        p("app.grapheneos.camera", "The GrapheneOS Camera app.", PackageCategory.GRAPHENEOS, DisableRisk.CAUTION),
        p("app.grapheneos.pdfviewer", "The GrapheneOS PDF Viewer, sandboxed with no network access.", PackageCategory.GRAPHENEOS, DisableRisk.SAFE),
        p("app.grapheneos.gmscompat", "Sandboxed Google Play compatibility layer and its settings.", PackageCategory.GRAPHENEOS, DisableRisk.CAUTION),
        p("app.grapheneos.networklocation", "GrapheneOS network location: estimates position from nearby Wi-Fi.", PackageCategory.GRAPHENEOS, DisableRisk.CAUTION),
        p("app.grapheneos.carrierconfig2", "GrapheneOS carrier configuration overrides.", PackageCategory.GRAPHENEOS, DisableRisk.NEVER),
        p("app.grapheneos.logviewer", "GrapheneOS Log Viewer for crash and system logs.", PackageCategory.GRAPHENEOS, DisableRisk.SAFE),
        p("app.attestation.auditor", "Auditor: verifies this device's hardware attestation and boot state.", PackageCategory.GRAPHENEOS, DisableRisk.SAFE),
        p("app.vanadium.browser", "Vanadium, the GrapheneOS hardened browser.", PackageCategory.GRAPHENEOS, DisableRisk.CAUTION),
        p("app.vanadium.webview", "Vanadium WebView: renders web content inside other apps.", PackageCategory.GRAPHENEOS, DisableRisk.NEVER),
        p("app.vanadium.trichromelibrary", "Shared browser engine used by Vanadium and its WebView.", PackageCategory.GRAPHENEOS, DisableRisk.NEVER),

        // Google (preinstalled on stock Pixel; on GrapheneOS these are user apps or absent)
        p("com.android.vending", "Google Play Store. A system app on stock Android; a regular user app on GrapheneOS.", PackageCategory.STORE, DisableRisk.CAUTION),
        p("com.google.android.gms", "Google Play services. A system app on stock Android; sandboxed as a user app on GrapheneOS.", PackageCategory.GOOGLE, DisableRisk.CAUTION),
        p("com.google.android.gsf", "Google Services Framework: account and push registration for Play services.", PackageCategory.GOOGLE, DisableRisk.CAUTION),
        p("com.google.android.webview", "Google's Chromium WebView.", PackageCategory.GOOGLE, DisableRisk.NEVER),
        p("com.google.android.permissioncontroller", "Google's build of the permission controller.", PackageCategory.SECURITY, DisableRisk.NEVER),
        p("com.google.android.packageinstaller", "Google's build of the package installer.", PackageCategory.SECURITY, DisableRisk.NEVER),
        p("com.google.android.ext.services", "Google's build of the ExtServices helpers.", PackageCategory.OTHER, DisableRisk.NEVER),
        p("com.google.android.networkstack", "Google's build of the network stack.", PackageCategory.CONNECTIVITY, DisableRisk.NEVER),
        p("com.google.android.networkstack.tethering", "Google's build of the tethering service.", PackageCategory.CONNECTIVITY, DisableRisk.NEVER),
        p("com.google.android.captiveportallogin", "Google's build of the captive portal sign-in page.", PackageCategory.CONNECTIVITY, DisableRisk.CAUTION),
        p("com.google.android.cellbroadcastreceiver", "Google's build of emergency alerts.", PackageCategory.TELEPHONY, DisableRisk.CAUTION),
        p("com.google.android.cellbroadcastservice", "Google's build of the cell broadcast service.", PackageCategory.TELEPHONY, DisableRisk.CAUTION),
        p("com.google.android.documentsui", "Google's build of the Files app and file picker.", PackageCategory.UI, DisableRisk.NEVER),
        p("com.google.android.modulemetadata", "Describes the installed Google system modules (Mainline).", PackageCategory.UPDATE, DisableRisk.NEVER),
        p("com.google.android.dialer", "Google Phone app.", PackageCategory.TELEPHONY, DisableRisk.CAUTION),
        p("com.google.android.apps.messaging", "Google Messages.", PackageCategory.TELEPHONY, DisableRisk.CAUTION),
        p("com.google.android.contacts", "Google Contacts.", PackageCategory.OTHER, DisableRisk.CAUTION),
        p("com.google.android.inputmethod.latin", "Gboard, Google's keyboard.", PackageCategory.INPUT, DisableRisk.CAUTION),
        p("com.google.android.apps.nexuslauncher", "Pixel Launcher.", PackageCategory.UI, DisableRisk.CAUTION),
        p("com.google.android.googlequicksearchbox", "Google app: search, Assistant and feed.", PackageCategory.GOOGLE, DisableRisk.CAUTION),
        p("com.google.android.apps.wellbeing", "Digital Wellbeing.", PackageCategory.GOOGLE, DisableRisk.SAFE),
        p("com.google.android.apps.photos", "Google Photos.", PackageCategory.MEDIA, DisableRisk.SAFE),
        p("com.google.android.GoogleCamera", "Pixel Camera.", PackageCategory.MEDIA, DisableRisk.CAUTION),
        p("com.google.android.setupwizard", "Google's first-boot setup wizard.", PackageCategory.GOOGLE, DisableRisk.NEVER),
        p("com.google.android.partnersetup", "Carrier and OEM partner setup for Google apps.", PackageCategory.GOOGLE, DisableRisk.CAUTION),
        p("com.google.android.configupdater", "Delivers certificate pins, time zone and emergency number updates.", PackageCategory.UPDATE, DisableRisk.CAUTION),
        p("com.google.android.tts", "Google Speech Services (text to speech).", PackageCategory.MEDIA, DisableRisk.SAFE),
        p("com.google.android.marvin.talkback", "TalkBack screen reader and other accessibility services.", PackageCategory.UI, DisableRisk.CAUTION),
        p("com.google.android.feedback", "Sends crash and feedback reports to Google.", PackageCategory.GOOGLE, DisableRisk.SAFE),
        p("com.google.android.printservice.recommendation", "Recommends print services for discovered printers.", PackageCategory.OTHER, DisableRisk.SAFE),
    )

    val byPackage: Map<String, KnownPackage> = all.associateBy { it.packageName }

    fun lookup(packageName: String): KnownPackage? = byPackage[packageName]

    fun isKnown(packageName: String): Boolean = packageName in byPackage

    /** The Android Open Source Project namespace: the framework and `com.android.*`. */
    fun isAospNamespace(packageName: String): Boolean =
        packageName == "android" || packageName.startsWith("com.android.")

    /** GrapheneOS's own apps: `app.grapheneos.*`, Vanadium and Auditor. */
    fun isGrapheneOsNamespace(packageName: String): Boolean =
        packageName.startsWith("app.grapheneos.") || packageName.startsWith("app.vanadium.") || packageName == "app.attestation.auditor"

    /** Google's proprietary packages: `com.google.*` plus the Play Store, which keeps its historical name. */
    fun isGoogleNamespace(packageName: String): Boolean =
        packageName.startsWith("com.google.") || packageName == "com.android.vending"

    /** The Play Store is `com.android.vending`, so Google wins over the AOSP prefix. */
    fun namespaceOf(packageName: String): Namespace = when {
        isGoogleNamespace(packageName) -> Namespace.GOOGLE
        isGrapheneOsNamespace(packageName) -> Namespace.GRAPHENEOS
        isAospNamespace(packageName) -> Namespace.AOSP
        else -> Namespace.OTHER
    }
}
