package io.github.stronghorse44.tunnels.install

/** Mirrors android.content.pm.PackageInstaller status codes so this stays plain Kotlin. */
object InstallStatus {
    const val PENDING_USER_ACTION = -1
    const val SUCCESS = 0
    const val FAILURE = 1
    const val FAILURE_BLOCKED = 2
    const val FAILURE_ABORTED = 3
    const val FAILURE_INVALID = 4
    const val FAILURE_CONFLICT = 5
    const val FAILURE_STORAGE = 6
    const val FAILURE_INCOMPATIBLE = 7
    const val FAILURE_TIMEOUT = 8
}

data class FailureExplanation(val title: String, val detail: String, val code: String?)

object InstallFailure {
    private val codePattern = Regex("""INSTALL_(?:FAILED|PARSE_FAILED)_[A-Z_]+""")

    private val byCode = mapOf(
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to ("Signing key doesn't match" to "The installed copy was signed with a different key (often a debug vs release build). Uninstall it first; that erases its data."),
        "INSTALL_FAILED_VERSION_DOWNGRADE" to ("Older than the installed version" to "Android blocks downgrades. Uninstall the installed copy first."),
        "INSTALL_FAILED_NO_MATCHING_ABIS" to ("Wrong processor type" to "The app has no native code for this phone's 64-bit ARM processor."),
        "INSTALL_FAILED_TEST_ONLY" to ("Test-only build" to "This APK is marked testOnly. Build a normal debug or release APK instead."),
        "INSTALL_FAILED_OLDER_SDK" to ("Needs a newer Android" to "The app's minSdk is higher than this phone's Android version."),
        "INSTALL_FAILED_DEPRECATED_SDK_VERSION" to ("Built for very old Android" to "The app targets an Android version too old for this phone to install."),
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to ("Not enough storage" to "Free up space and try again."),
        "INSTALL_FAILED_DUPLICATE_PERMISSION" to ("Permission clash" to "Another installed app already defines a permission this app declares."),
        "INSTALL_FAILED_CONFLICTING_PROVIDER" to ("Provider clash" to "Another installed app already uses the same content provider authority."),
        "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE" to ("Shared user mismatch" to "The app shares a user ID with an app signed by a different key."),
        "INSTALL_FAILED_MISSING_SPLIT" to ("Missing split APK" to "This app needs more parts than were provided. Install the full bundle."),
        "INSTALL_FAILED_INVALID_APK" to ("Invalid APK" to "The file is damaged or not a complete APK."),
        "INSTALL_PARSE_FAILED_NO_CERTIFICATES" to ("Not signed" to "The APK has no valid signature."),
        "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" to ("Inconsistent signatures" to "Parts of this APK or bundle are signed with different keys."),
        "INSTALL_PARSE_FAILED_NOT_APK" to ("Not an APK" to "The file is not an Android package."),
        "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED" to ("Broken manifest" to "The app's manifest is malformed."),
        "INSTALL_FAILED_USER_RESTRICTED" to ("Installs restricted" to "This user or profile is not allowed to install apps."),
        "INSTALL_FAILED_ABORTED" to ("Cancelled" to "The install was cancelled."),
    )

    fun explain(status: Int, message: String?): FailureExplanation {
        val code = message?.let { codePattern.find(it)?.value }
        byCode[code]?.let { (title, detail) -> return FailureExplanation(title, detail, code) }
        val (title, detail) = when (status) {
            InstallStatus.FAILURE_ABORTED -> "Cancelled" to "The install was cancelled or the confirmation was dismissed."
            InstallStatus.FAILURE_BLOCKED -> "Blocked" to "The system or a device policy blocked this install."
            InstallStatus.FAILURE_CONFLICT -> "Conflicts with an installed app" to "Usually a signing key mismatch with the installed copy, or another app holding the same permission or provider."
            InstallStatus.FAILURE_INCOMPATIBLE -> "Incompatible with this phone" to "Wrong processor type or Android version."
            InstallStatus.FAILURE_INVALID -> "Invalid package" to "The APK is damaged, unsigned, or the bundle's parts don't belong together."
            InstallStatus.FAILURE_STORAGE -> "Not enough storage" to "Free up space and try again."
            InstallStatus.FAILURE_TIMEOUT -> "Timed out" to "The install took too long. Try again."
            else -> "Install failed" to "Android didn't give a specific reason."
        }
        return FailureExplanation(title, detail, code)
    }
}
