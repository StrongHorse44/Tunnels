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

/** What a check offers the user to do about it (rule 7): real actions on the install screen. */
enum class CheckAction {
    /** Leave without installing (the screen's Cancel). */
    DONT_INSTALL,

    /** Uninstall the copy already on the phone (the system's uninstall confirmation). */
    UNINSTALL_INSTALLED,
}

/** [actions] are shown with the check; [why] is a longer explanation behind "Why this matters". */
data class Check(
    val level: CheckLevel,
    val title: String,
    val detail: String,
    val actions: List<CheckAction> = emptyList(),
    val why: String? = null,
)

enum class InstallKind { NEW, UPDATE, REINSTALL, DOWNGRADE }

data class PreInstallReport(val kind: InstallKind, val checks: List<Check>) {
    val blocked: Boolean get() = checks.any { it.level == CheckLevel.BLOCKER }
}

object PreInstallChecks {
    /** Android 14 refuses apps targeting below this. */
    const val MIN_TARGET_SDK = 23

    const val PUBLIC_KEY_TITLE = "Signed with a publicly known key"
    const val PUBLIC_LINEAGE_TITLE = "Signing history includes a publicly known key"
    const val PUBLIC_KEY_WHY = "Android trusts an update when it carries the same signature as the installed app. A key that is " +
        "published (Android's test keys, or a key a project committed to a public repository, as Tunnels once did for its debug " +
        "build) proves nothing about who made the file. Install it only if you built it yourself from source you trust."

    /**
     * The H1 check (placed first): an APK signed with a key anyone can download ([PublicSigningKeys]) could have been
     * built by anyone. A warning, not a blocker (I-Q1): test-key builds are legitimate for developers and custom-ROM
     * users, and the system installer would accept the file anyway.
     */
    fun publicKeyCheck(apk: ApkFacts, installed: InstalledFacts?): Check? {
        val signer = apk.signerSha256.sorted().firstNotNullOfOrNull(PublicSigningKeys::match)
        if (signer != null) {
            // The H1 case: the installed copy holds the same public key, so anyone can make an update Android accepts for it.
            val installedSame = installed?.signerSha256.orEmpty().mapNotNull(PublicSigningKeys::match).any { known ->
                apk.lineageSha256.any { PublicSigningKeys.match(it) == known }
            }
            val detail = "This APK is signed with ${signer.name}, a key anyone can download. Anyone could have built this file, " +
                "including a changed copy of a real app, and Android can't tell." +
                if (installedSame) {
                    " The copy already on this phone is signed with the same public key, so anyone can make an update Android " +
                        "accepts for it. Uninstall it unless you build it yourself."
                } else {
                    ""
                }
            val actions = if (installedSame) listOf(CheckAction.DONT_INSTALL, CheckAction.UNINSTALL_INSTALLED) else listOf(CheckAction.DONT_INSTALL)
            return Check(CheckLevel.WARN, PUBLIC_KEY_TITLE, detail, actions, PUBLIC_KEY_WHY)
        }
        val old = (apk.lineageSha256 - apk.signerSha256).sorted().firstNotNullOfOrNull(PublicSigningKeys::match) ?: return null
        return Check(
            CheckLevel.WARN,
            PUBLIC_LINEAGE_TITLE,
            "This app once used ${old.name}, a key anyone can download. An APK signed with that old key may still be accepted as " +
                "this app's update.",
            listOf(CheckAction.DONT_INSTALL),
            PUBLIC_KEY_WHY,
        )
    }

    fun evaluate(apk: ApkFacts, installed: InstalledFacts?, device: DeviceFacts): PreInstallReport {
        val checks = mutableListOf<Check>()
        publicKeyCheck(apk, installed)?.let { checks += it }

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
