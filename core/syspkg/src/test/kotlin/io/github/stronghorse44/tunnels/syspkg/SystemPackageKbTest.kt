package io.github.stronghorse44.tunnels.syspkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPackageKbTest {
    @Test
    fun hasAtLeastSeventyDistinctWellFormedEntries() {
        assertTrue("${SystemPackageKb.all.size} entries", SystemPackageKb.all.size >= 70)
        assertEquals("duplicate package names", SystemPackageKb.all.size, SystemPackageKb.byPackage.size)
        for (entry in SystemPackageKb.all) {
            assertTrue(entry.packageName, Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*").matches(entry.packageName))
            assertTrue(entry.packageName, entry.purpose.length in 8..160)
            assertTrue(entry.packageName, entry.purpose.endsWith("."))
        }
    }

    @Test
    fun entriesLiveInAKnownNamespace() {
        for (entry in SystemPackageKb.all) {
            val ns = SystemPackageKb.namespaceOf(entry.packageName)
            assertTrue("${entry.packageName} is in namespace other", ns != Namespace.OTHER)
            if (entry.category == PackageCategory.GRAPHENEOS) assertEquals(entry.packageName, Namespace.GRAPHENEOS, ns)
            if (entry.category == PackageCategory.GOOGLE) assertEquals(entry.packageName, Namespace.GOOGLE, ns)
        }
    }

    @Test
    fun coreEntries() {
        val systemUi = SystemPackageKb.lookup("com.android.systemui")!!
        assertEquals(PackageCategory.UI, systemUi.category)
        assertEquals(DisableRisk.NEVER, systemUi.disableRisk)
        assertEquals(DisableRisk.NEVER, SystemPackageKb.lookup("com.android.phone")!!.disableRisk)
        assertEquals(PackageCategory.PROVIDER, SystemPackageKb.lookup("com.android.providers.settings")!!.category)
        assertEquals(PackageCategory.CONNECTIVITY, SystemPackageKb.lookup("com.android.bluetooth")!!.category)
        assertEquals(PackageCategory.STORE, SystemPackageKb.lookup("com.android.vending")!!.category)
        assertTrue(SystemPackageKb.lookup("com.android.vending")!!.purpose.contains("GrapheneOS"))
        assertEquals(PackageCategory.GRAPHENEOS, SystemPackageKb.lookup("app.grapheneos.camera")!!.category)
        assertTrue(SystemPackageKb.isKnown("android"))
        assertNull(SystemPackageKb.lookup("com.example.nothing"))
        assertFalse(SystemPackageKb.isKnown("com.example.nothing"))
    }

    @Test
    fun namespaceHeuristics() {
        assertTrue(SystemPackageKb.isAospNamespace("android"))
        assertTrue(SystemPackageKb.isAospNamespace("com.android.systemui"))
        assertFalse(SystemPackageKb.isAospNamespace("com.androidx.foo"))
        assertFalse(SystemPackageKb.isAospNamespace("androidx.test"))

        assertTrue(SystemPackageKb.isGrapheneOsNamespace("app.grapheneos.camera"))
        assertTrue(SystemPackageKb.isGrapheneOsNamespace("app.vanadium.webview"))
        assertTrue(SystemPackageKb.isGrapheneOsNamespace("app.attestation.auditor"))
        assertFalse(SystemPackageKb.isGrapheneOsNamespace("app.grapheneosfake"))

        assertTrue(SystemPackageKb.isGoogleNamespace("com.google.android.gms"))
        assertTrue(SystemPackageKb.isGoogleNamespace("com.android.vending"))
        assertFalse(SystemPackageKb.isGoogleNamespace("com.googleish.app"))

        assertEquals(Namespace.GOOGLE, SystemPackageKb.namespaceOf("com.android.vending"))
        assertEquals(Namespace.AOSP, SystemPackageKb.namespaceOf("com.android.settings"))
        assertEquals(Namespace.GRAPHENEOS, SystemPackageKb.namespaceOf("app.grapheneos.pdfviewer"))
        assertEquals(Namespace.OTHER, SystemPackageKb.namespaceOf("com.qualcomm.qti.something"))
        assertEquals(Namespace.OTHER, SystemPackageKb.namespaceOf("com.shannon.imsservice"))
    }

    @Test
    fun labelsRoundTrip() {
        for (c in PackageCategory.entries) assertEquals(c, PackageCategory.byLabel(c.label))
        for (r in DisableRisk.entries) assertEquals(r, DisableRisk.byLabel(r.label))
        for (n in Namespace.entries) assertEquals(n, Namespace.byLabel(n.label))
        assertNull(PackageCategory.byLabel("bogus"))
        assertEquals(PackageCategory.entries.size, PackageCategory.entries.map { it.label }.toSet().size)
    }
}
