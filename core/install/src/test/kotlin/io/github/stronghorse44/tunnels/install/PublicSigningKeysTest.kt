package io.github.stronghorse44.tunnels.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Spec section 6, tests 7 (the table against its committed certificates) and 8 (the Installer checks). */
class PublicSigningKeysTest {
    private val fixtures = mapOf(
        "testkey" to "Android's public test key (testkey)",
        "platform" to "Android's public platform test key",
        "shared" to "Android's public shared test key",
        "media" to "Android's public media test key",
        "networkstack" to "Android's public network stack test key",
        "sdk_sandbox" to "Android's public SDK sandbox test key",
        "bluetooth" to "Android's public Bluetooth test key",
        "nfc" to "Android's public NFC test key",
        "cts_uicc_2021" to "Android's public CTS test key",
        "tunnels_debug" to "Tunnels' retired debug key",
    )

    private fun sha256OfFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/public-keys/$name.x509.pem") ?: error("missing fixture $name")
        val cert = stream.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
    }

    // ---- 7 ---------------------------------------------------------------------------------------------

    @Test
    fun everyEntryIsTheSha256OfItsCommittedCertificate() {
        assertEquals(fixtures.size, PublicSigningKeys.entries.size)
        for ((file, name) in fixtures) {
            val sha = sha256OfFixture(file)
            assertEquals(file, KnownKey(sha, name), PublicSigningKeys.match(sha))
        }
    }

    @Test
    fun theTunnelsEntryIsTheDebugBaselinePin() {
        val pin = Regex("\"cert_sha256\"\\s*:\\s*\\[?\\s*\"([0-9a-f]{64})\"").find(baseline())?.groupValues?.get(1)
        assertNotNull(pin)
        assertEquals("Tunnels' retired debug key", PublicSigningKeys.match(pin!!)?.name)
    }

    /** gate/baseline.json, found from the module directory whichever build runs the test. */
    private fun baseline(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "gate/baseline.json")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        error("gate/baseline.json not found")
    }

    @Test
    fun entriesAreSixtyFourUppercaseHexAndUnique() {
        val hex = Regex("[0-9A-F]{64}")
        assertTrue(PublicSigningKeys.entries.all { hex.matches(it.sha256) })
        assertEquals(PublicSigningKeys.entries.size, PublicSigningKeys.entries.map { it.sha256 }.toSet().size)
        assertEquals(PublicSigningKeys.entries.size, PublicSigningKeys.entries.map { it.name }.toSet().size)
    }

    @Test
    fun matchAcceptsLowerCaseAndColons() {
        val sha = PublicSigningKeys.entries.first().sha256
        assertNotNull(PublicSigningKeys.match(sha.lowercase()))
        assertNotNull(PublicSigningKeys.match(sha.chunked(2).joinToString(":")))
        assertNotNull(PublicSigningKeys.match(" $sha "))
        assertNull(PublicSigningKeys.match(sha.dropLast(1) + "0"))
        assertNull(PublicSigningKeys.match(""))
    }

    // ---- 8 ---------------------------------------------------------------------------------------------

    private val testkey = PublicSigningKeys.entries.first { it.name.contains("(testkey)") }.sha256
    private val tunnelsDebug = PublicSigningKeys.entries.first { it.name == "Tunnels' retired debug key" }.sha256
    private val platform = PublicSigningKeys.entries.first { it.name.contains("platform") }.sha256
    private val ordinary = "AB".repeat(32)

    private val device = DeviceFacts(36, listOf("arm64-v8a"), "io.github.stronghorse44.tunnels")
    private fun apk(signers: Set<String>, lineage: Set<String> = signers, code: Long = 20) = ApkFacts(
        packageName = "com.example", versionName = "2.0", versionCode = code, minSdk = 26, targetSdk = 35,
        nativeAbis = emptySet(), testOnly = false, debuggable = false,
        signerSha256 = signers, lineageSha256 = lineage, requestedPermissions = emptyList(),
    )
    private fun installed(signers: Set<String>) = InstalledFacts("1.0", 10, signers, null, debuggable = false)

    @Test
    fun aNewInstallSignedWithTheTestKeyWarnsFirst() {
        val r = PreInstallChecks.evaluate(apk(setOf(testkey)), null, device)
        val first = r.checks.first()
        assertEquals(CheckLevel.WARN, first.level)
        assertEquals("Signed with a publicly known key", first.title)
        assertEquals(
            "This APK is signed with Android's public test key (testkey), a key anyone can download. Anyone could have built this " +
                "file, including a changed copy of a real app, and Android can't tell.",
            first.detail,
        )
        assertEquals(listOf(CheckAction.DONT_INSTALL), first.actions)
        assertEquals(PreInstallChecks.PUBLIC_KEY_WHY, first.why)
        assertFalse("a warning, never a blocker (I-Q1)", r.blocked)
    }

    @Test
    fun anUpdateWhereBothCopiesHoldTheTunnelsDebugKeyOffersUninstall() {
        val r = PreInstallChecks.evaluate(apk(setOf(tunnelsDebug)), installed(setOf(tunnelsDebug)), device)
        assertEquals(InstallKind.UPDATE, r.kind)
        val first = r.checks.first()
        assertEquals("Signed with a publicly known key", first.title)
        assertTrue(first.detail.contains("Tunnels' retired debug key"))
        assertTrue(first.detail.endsWith(
            "The copy already on this phone is signed with the same public key, so anyone can make an update Android accepts " +
                "for it. Uninstall it unless you build it yourself.",
        ))
        assertEquals(listOf(CheckAction.DONT_INSTALL, CheckAction.UNINSTALL_INSTALLED), first.actions)
        assertTrue("the true OK stays", r.checks.any { it.level == CheckLevel.OK && it.title == "Signed by the same key" })
        assertFalse(r.blocked)
    }

    @Test
    fun aKeyRotatedFromOnePublicKeyToAnotherIsStillTheH1Case() {
        // The installed copy holds testkey; the APK rotated from testkey to the platform key, so Android accepts it.
        val r = PreInstallChecks.evaluate(apk(setOf(platform), lineage = setOf(testkey, platform)), installed(setOf(testkey)), device)
        val first = r.checks.first()
        assertEquals("Signed with a publicly known key", first.title)
        assertTrue(first.detail.contains("Android's public platform test key"))
        assertEquals(listOf(CheckAction.DONT_INSTALL, CheckAction.UNINSTALL_INSTALLED), first.actions)
        assertTrue(r.checks.any { it.title == "Signed by the same developer (key rotated)" })
    }

    @Test
    fun anInstalledCopyOnAnotherPublicKeyIsNotTheH1Case() {
        val r = PreInstallChecks.evaluate(apk(setOf(testkey)), installed(setOf(platform)), device)
        assertEquals(listOf(CheckAction.DONT_INSTALL), r.checks.first().actions)
        assertTrue(r.checks.any { it.title == "Different signing key" })
    }

    @Test
    fun aPublicKeyOnlyInTheLineageGetsTheSecondWarning() {
        val r = PreInstallChecks.evaluate(apk(setOf(ordinary), lineage = setOf(ordinary, testkey)), null, device)
        val first = r.checks.first()
        assertEquals(CheckLevel.WARN, first.level)
        assertEquals("Signing history includes a publicly known key", first.title)
        assertEquals(
            "This app once used Android's public test key (testkey), a key anyone can download. An APK signed with that old key " +
                "may still be accepted as this app's update.",
            first.detail,
        )
        assertEquals(listOf(CheckAction.DONT_INSTALL), first.actions)
        assertEquals(PreInstallChecks.PUBLIC_KEY_WHY, first.why)
        assertTrue(r.checks.none { it.title == "Signed with a publicly known key" })
    }

    @Test
    fun anOrdinaryKeyGetsNoSuchCheck() {
        for (inst in listOf(null, installed(setOf(ordinary)))) {
            val r = PreInstallChecks.evaluate(apk(setOf(ordinary)), inst, device)
            assertTrue(r.checks.none { it.title.contains("publicly known") })
            assertTrue(r.checks.all { it.actions.isEmpty() && it.why == null })
        }
    }

    @Test
    fun anUnsignedApkKeepsOnlyItsBlocker() {
        val r = PreInstallChecks.evaluate(apk(emptySet()), null, device)
        assertEquals(listOf("Not signed"), r.checks.map { it.title })
        assertTrue(r.blocked)
    }

    @Test
    fun theWhyTextIsExact() {
        assertEquals(
            "Android trusts an update when it carries the same signature as the installed app. A key that is published (Android's " +
                "test keys, or a key a project committed to a public repository, as Tunnels once did for its debug build) proves " +
                "nothing about who made the file. Install it only if you built it yourself from source you trust.",
            PreInstallChecks.PUBLIC_KEY_WHY,
        )
    }
}
