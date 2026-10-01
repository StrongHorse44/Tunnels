package io.github.stronghorse44.tunnels.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallLogicTest {
    private val device = DeviceFacts(36, listOf("arm64-v8a"), "io.github.stronghorse44.tunnels")
    private val apk = ApkFacts(
        packageName = "com.example", versionName = "2.0", versionCode = 20, minSdk = 26, targetSdk = 35,
        nativeAbis = setOf("arm64-v8a", "armeabi-v7a"), testOnly = false, debuggable = false,
        signerSha256 = setOf("AA"), lineageSha256 = setOf("AA"), requestedPermissions = emptyList(),
    )
    private val installed = InstalledFacts("1.0", 10, setOf("AA"), "org.fdroid.fdroid", debuggable = false)

    @Test
    fun cleanUpdate() {
        val r = PreInstallChecks.evaluate(apk, installed, device)
        assertEquals(InstallKind.UPDATE, r.kind)
        assertFalse(r.blocked)
    }

    @Test
    fun signerMismatchBlocks() {
        val r = PreInstallChecks.evaluate(apk, installed.copy(signerSha256 = setOf("BB")), device)
        assertTrue(r.blocked)
        assertTrue(r.checks.any { it.title == "Different signing key" })
    }

    @Test
    fun rotatedKeyAllowed() {
        val rotated = apk.copy(signerSha256 = setOf("CC"), lineageSha256 = setOf("AA", "CC"))
        assertFalse(PreInstallChecks.evaluate(rotated, installed, device).blocked)
    }

    @Test
    fun thirtyTwoBitOnlyBlocks() {
        val r = PreInstallChecks.evaluate(apk.copy(nativeAbis = setOf("armeabi-v7a")), null, device)
        assertEquals(InstallKind.NEW, r.kind)
        assertTrue(r.blocked)
    }

    @Test
    fun downgradeAndTestOnlyBlock() {
        assertTrue(PreInstallChecks.evaluate(apk.copy(versionCode = 5), installed, device).blocked)
        assertTrue(PreInstallChecks.evaluate(apk.copy(testOnly = true), null, device).blocked)
    }

    @Test
    fun bundleShapeAndSplitSelection() {
        assertEquals(PackageShape.APK, Bundles.shape(listOf("AndroidManifest.xml", "classes.dex")))
        assertEquals(PackageShape.BUNDLE, Bundles.shape(listOf("manifest.json", "base.apk", "config.arm64_v8a.apk")))
        assertEquals(PackageShape.NOT_AN_APP, Bundles.shape(listOf("readme.txt")))
        val chosen = Bundles.selectSplits(
            listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.armeabi_v7a.apk", "split_config.x86_64.apk", "split_config.xxhdpi.apk", "split_config.en.apk"),
            listOf("arm64-v8a"),
        )
        assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.xxhdpi.apk", "split_config.en.apk"), chosen)
        assertEquals("x86_64", Bundles.abiOf("config.x86_64.apk"))
        assertEquals(listOf("splits/base-master.apk"), Bundles.selectSplits(listOf("standalones/standalone-arm64_v8a.apk", "splits/base-master.apk"), listOf("arm64-v8a")))
    }

    @Test
    fun explainsFailures() {
        val e = InstallFailure.explain(InstallStatus.FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.x signatures do not match")
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", e.code)
        assertEquals("Signing key doesn't match", e.title)
        assertEquals("Cancelled", InstallFailure.explain(InstallStatus.FAILURE_ABORTED, null).title)
    }
}
