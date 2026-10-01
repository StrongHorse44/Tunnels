package io.github.stronghorse44.tunnels.install

/** What we read from the APK before installing. */
data class ApkFacts(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    /** ABIs that have native code (lib/<abi>/), across base and splits. Empty = no native code. */
    val nativeAbis: Set<String>,
    val testOnly: Boolean,
    val debuggable: Boolean,
    /** SHA-256 of current signer certificates. */
    val signerSha256: Set<String>,
    /** SHA-256 of every certificate in the signing lineage (rotation history), including current. */
    val lineageSha256: Set<String>,
    val requestedPermissions: List<String>,
)

/** The copy already on the device, if any. */
data class InstalledFacts(
    val versionName: String?,
    val versionCode: Long,
    val signerSha256: Set<String>,
    val installerPackage: String?,
    val debuggable: Boolean,
)

data class DeviceFacts(
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val ownPackage: String,
)

enum class CheckLevel { OK, INFO, WARN, BLOCKER }

data class Check(val level: CheckLevel, val title: String, val detail: String)

enum class InstallKind { NEW, UPDATE, REINSTALL, DOWNGRADE }

data class PreInstallReport(val kind: InstallKind, val checks: List<Check>) {
    val blocked: Boolean get() = checks.any { it.level == CheckLevel.BLOCKER }
}

object PreInstallChecks {
    /** Android 14 refuses apps targeting below this. */
    const val MIN_TARGET_SDK = 23

    fun evaluate(apk: ApkFacts, installed: InstalledFacts?, device: DeviceFacts): PreInstallReport {
        val checks = mutableListOf<Check>()

        val kind = when {
            installed == null -> InstallKind.NEW
            apk.versionCode > installed.versionCode -> InstallKind.UPDATE
            apk.versionCode == installed.versionCode -> InstallKind.REINSTALL
            else -> InstallKind.DOWNGRADE
        }

        if (installed != null) {
            val sameSigner = installed.signerSha256.isNotEmpty() && installed.signerSha256.any { it in apk.lineageSha256 }
            if (sameSigner) {
                val rotated = installed.signerSha256 != apk.signerSha256
                checks += Check(
                    CheckLevel.OK,
                    if (rotated) "Signed by the same developer (key rotated)" else "Signed by the same key",
                    "Matches the installed copy.",
                )
            } else {
                checks += Check(
                    CheckLevel.BLOCKER,
                    "Different signing key",
                    "This APK is not signed by the key of the installed copy. Android will refuse the update. " +
                        "It may come from a different source, or be tampered with. Uninstalling first works but erases the app's data.",
                )
            }
            if (kind == InstallKind.DOWNGRADE && !(installed.debuggable && apk.debuggable)) {
                checks += Check(
                    CheckLevel.BLOCKER,
                    "Older version",
                    "Installed: ${installed.versionName ?: installed.versionCode}. Android does not allow downgrades without uninstalling first.",
                )
            }
            val owner = installed.installerPackage
            if (owner != null && owner != device.ownPackage) {
                checks += Check(CheckLevel.INFO, "Updated by another installer", "Currently updated by $owner. Android may ask you to confirm the change of installer.")
            }
        }

        if (apk.minSdk > device.sdkInt) {
            checks += Check(CheckLevel.BLOCKER, "Needs a newer Android", "Requires API ${apk.minSdk}; this phone runs API ${device.sdkInt}.")
        }
        if (apk.targetSdk in 1 until MIN_TARGET_SDK) {
            checks += Check(CheckLevel.BLOCKER, "Built for very old Android", "Targets API ${apk.targetSdk}. Android 14+ blocks apps targeting below API $MIN_TARGET_SDK.")
        }
        if (apk.nativeAbis.isNotEmpty()) {
            if (apk.nativeAbis.none { it in device.supportedAbis }) {
                checks += Check(
                    CheckLevel.BLOCKER,
                    "Wrong processor type",
                    "Native code only for ${apk.nativeAbis.sorted().joinToString()}. This phone supports ${device.supportedAbis.joinToString()}.",
                )
            } else {
                checks += Check(CheckLevel.OK, "Runs on this processor", apk.nativeAbis.filter { it in device.supportedAbis }.joinToString())
            }
        }
        if (apk.testOnly) {
            checks += Check(CheckLevel.BLOCKER, "Test-only build", "Marked testOnly (e.g. built with Android Studio's Run). Normal installs refuse it; build a proper debug or release APK.")
        }
        if (apk.debuggable) {
            checks += Check(CheckLevel.WARN, "Debuggable build", "Debug builds expose app data to a connected computer. Fine for your own apps.")
        }
        if (apk.signerSha256.isEmpty()) {
            checks += Check(CheckLevel.BLOCKER, "Not signed", "Android only installs signed APKs.")
        }
        return PreInstallReport(kind, checks)
    }
}
