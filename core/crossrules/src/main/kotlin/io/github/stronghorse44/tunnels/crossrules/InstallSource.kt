package io.github.stronghorse44.tunnels.crossrules

/** Where an app came from, judged from its installing package. */
enum class InstallSource(val value: String) {
    /** A store that reviews or signs what it ships (Play, Accrescent, F-Droid and its clients, GrapheneOS's own). */
    STORE("store"),
    /** Android's package installer or Tunnels' Installer: someone opened an APK file. */
    FILE("file"),
    /** No installer on record: adb, or a store that has since been uninstalled. */
    UNKNOWN("unknown"),
    /** Another app installed it (an updater such as Obtainium, a browser, a file manager with install rights). */
    OTHER("other"),
    /** Preinstalled with the OS. */
    SYSTEM("system"),
    ;

    /** True for every source that skipped a store's review. */
    val outsideStore: Boolean get() = this == FILE || this == UNKNOWN || this == OTHER

    companion object {
        fun of(value: String?): InstallSource? = entries.firstOrNull { it.value == value }
    }
}

object InstallSources {
    /** Installing packages that are stores, with a readable name. */
    val STORES: Map<String, String> = mapOf(
        "com.android.vending" to "Google Play",
        "app.accrescent.client" to "Accrescent",
        "app.grapheneos.apps" to "the GrapheneOS App Store",
        "org.fdroid.fdroid" to "F-Droid",
        "org.fdroid.basic" to "F-Droid Basic",
        "org.fdroid.fdroid.privileged" to "F-Droid (privileged extension)",
        "com.looker.droidify" to "Droid-ify",
        "com.machiav3lli.fdroid" to "Neo Store",
        "com.aurora.store" to "Aurora Store",
    )

    /** Installing packages that mean "installed from an APK file". */
    val FILE_INSTALLERS: Map<String, String> = mapOf(
        "com.android.packageinstaller" to "Android's package installer",
        "com.google.android.packageinstaller" to "Android's package installer",
        "io.github.stronghorse44.tunnels" to "Tunnels' Installer",
        "io.github.stronghorse44.tunnels.debug" to "Tunnels' Installer",
    )

    /** Other installers worth naming. */
    val KNOWN_OTHERS: Map<String, String> = mapOf(
        "dev.imranr.obtainium" to "Obtainium",
        "dev.imranr.obtainium.fdroid" to "Obtainium",
    )

    /** APK excavation's value when Android names no installer. */
    const val UNKNOWN_INSTALLER = "unknown"

    fun classify(installer: String?, isSystem: Boolean): InstallSource = when {
        isSystem -> InstallSource.SYSTEM
        installer == null || installer == UNKNOWN_INSTALLER || installer.isBlank() -> InstallSource.UNKNOWN
        installer in STORES -> InstallSource.STORE
        installer in FILE_INSTALLERS -> InstallSource.FILE
        else -> InstallSource.OTHER
    }

    /** "Obtainium", "Android's package installer", or the package name itself. */
    fun nameOf(installer: String): String = STORES[installer] ?: FILE_INSTALLERS[installer] ?: KNOWN_OTHERS[installer] ?: installer

    /** Half a sentence for evidence: "was installed from a file (Android's package installer), not from a store". */
    fun describe(source: InstallSource, installer: String?): String = when (source) {
        InstallSource.FILE -> "was installed from an APK file (${installer?.let(::nameOf) ?: "package installer"}), not from a store"
        InstallSource.UNKNOWN -> "has no installer on record (adb, or a store that has since been removed), so no store vouches for it"
        InstallSource.OTHER -> "was installed by ${installer?.let(::nameOf) ?: "another app"}, not by a store"
        InstallSource.STORE -> "came from ${installer?.let(::nameOf) ?: "a store"}"
        InstallSource.SYSTEM -> "came with the system"
    }
}
