package io.github.stronghorse44.tunnels.install

/** A signing certificate anyone can download: its SHA-256 (uppercase hex, no colons, as the Installer computes it) and its name. */
data class KnownKey(val sha256: String, val name: String)

/**
 * Signing certificates whose private keys are public (audit H1 follow-up). An APK signed with one of them proves nothing
 * about who built it, and an installed app signed with one accepts an "update" from anyone. Each value is the SHA-256 of
 * the certificate's DER form; `core/install/src/test/resources/public-keys/` holds the certificates and the test that
 * recomputes every value.
 *
 * - AOSP's test keys from `build/make/target/product/security/` (AOSP publishes no `releasekey`; vendors' release keys
 *   are private), copied from the aosp-mirror GitHub copy and cross-checked byte for byte with LineageOS `android_build`.
 * - Tunnels' own retired debug key: `app/debug.keystore` is committed to the public repository, and the old "Debug
 *   build #N" channel was signed with it (`gate/baseline.json`'s pin).
 */
object PublicSigningKeys {
    val entries: List<KnownKey> = listOf(
        KnownKey("A40DA80A59D170CAA950CF15C18C454D47A39B26989D8B640ECD745BA71BF5DC", "Android's public test key (testkey)"),
        KnownKey("C8A2E9BCCF597C2FB6DC66BEE293FC13F2FC47EC77BC6B2B0D52C11F51192AB8", "Android's public platform test key"),
        KnownKey("28BBFE4A7B97E74681DC55C2FBB6CCB8D6C74963733F6AF6AE74D8C3A6E879FD", "Android's public shared test key"),
        KnownKey("465983F7791F2ABEB43EA2CBDC7F21A8260B72BC08A55C839FC1A43BC741A81E", "Android's public media test key"),
        KnownKey("E1DBADCE60DC080D15B58A014B0DCF9400E24DE23FA00B287A5A982BFEBDA2EE", "Android's public network stack test key"),
        KnownKey("ABF21F9E2AF1D881CC673FDDCEFA6ED9C269A437BD64B279CF45844CFD589126", "Android's public SDK sandbox test key"),
        KnownKey("A6CCC500FF0E7421200EB66A7FE174EF1B00E52CA91727070CBEDF061FF76C35", "Android's public Bluetooth test key"),
        KnownKey("FAE9122A8721D6E2A196D2224DFFCF773C9127E2BB956CBDDB40B009192FFDFD", "Android's public NFC test key"),
        KnownKey("CE7B2B47AE2B7552C8F92CC29124279883041FB623A5F194A82C9BF15D492AA0", "Android's public CTS test key"),
        KnownKey("0F23F08BBE02F1D0FF9B4A721AD523C2C977A9178A2B842C38EDE005F3DE34C2", "Tunnels' retired debug key"),
    )

    private val bySha = entries.associateBy { it.sha256 }

    /** The known key [sha256] names, or null; case and colons do not matter. */
    fun match(sha256: String): KnownKey? = bySha[normalise(sha256)]

    private fun normalise(sha256: String): String = sha256.replace(":", "").trim().uppercase()
}
