package io.github.stronghorse44.tunnels.trackers

import io.github.stronghorse44.tunnels.model.Observation

/**
 * Google Play services, Google Services Framework and the Play Store, recognised by package name and Google's signing
 * certificate together. Their dex holds Google's own side of Firebase, AdMob, the advertising ID and the rest, so
 * tracker signatures match them by construction: they are the service those SDKs report to, not apps embedding one.
 * On GrapheneOS they are ordinary sandboxed apps.
 */
object GooglePlay {
    val PACKAGES: Map<String, String> = mapOf(
        "com.google.android.gms" to "Google Play services",
        "com.google.android.gsf" to "Google Services Framework",
        "com.android.vending" to "Google Play Store",
    )

    /**
     * SHA-256 of the certificate Google signs all three with (SHA-1 38:91:8A:45…). Not yet confirmed on a device: if it
     * is wrong nothing is recognised, and the apps keep being judged like any other, so a mistake here fails noisy.
     */
    const val CERT_SHA256 = "7CE83C1B71F3D572FED04C8D40C5CB10FF75E6D87D9DF6FBD53F0468C2905053"

    /** True only when [pkg] is one of [PACKAGES] and its first signer is Google's: a look-alike package is not exempt. */
    fun isGooglePlay(pkg: String, certSha256: String?): Boolean =
        pkg in PACKAGES && certSha256?.replace(":", "")?.trim()?.uppercase() == CERT_SHA256

    /** [isGooglePlay] from APK excavation's observations of [pkg]. */
    fun isGooglePlay(pkg: String, obs: List<Observation>): Boolean = isGooglePlay(pkg, ApkKeys.value(obs, ApkKeys.CERT_SHA256))
}
