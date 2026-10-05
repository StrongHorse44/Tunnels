package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.dns.BlockPolicy
import io.github.stronghorse44.tunnels.dns.Upstream
import io.github.stronghorse44.tunnels.export.fwx.FwxError
import io.github.stronghorse44.tunnels.export.fwx.FwxException
import io.github.stronghorse44.tunnels.export.fwx.FwxReader
import io.github.stronghorse44.tunnels.export.fwx.FwxWriter
import io.github.stronghorse44.tunnels.pairing.Pin
import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

/** Test data and helpers shared by the bundle tests. */
object Samples {
    const val PASSPHRASE = "correct horse battery staple"
    fun pass(text: String = PASSPHRASE) = text.toCharArray()

    val nasty = "tab\there\nnewline\rreturn\\back üö 🔑"

    fun pin(seed: Int, lastAuditMs: Long = 1_700_000_100_000): Pin {
        val key = ByteArray(40) { (it * 7 + seed).toByte() }
        return Pin(
            Pin.idOf(key), key, "Pixel $seed", "ab".repeat(32),
            Instant.ofEpochMilli(1_700_000_000_000), Instant.ofEpochMilli(lastAuditMs), 202610, true,
        )
    }

    val snapshots = SnapshotBundle(
        snapshots = listOf(
            BundleSnapshot(1, 1_700_000_000_000, pinned = false, tunnelIds = listOf("doors", "permissions")),
            BundleSnapshot(2, 1_700_000_060_000, pinned = true, tunnelIds = emptyList()),
            BundleSnapshot(3, 1_700_000_120_000, pinned = false, tunnelIds = listOf("silicon")),
        ),
        observations = listOf(
            BundleObservation(1, "permissions", "com.example.app", "perm:CAMERA", "granted"),
            BundleObservation(1, "doors", "com.example.app", "exported:activities", "3"),
            BundleObservation(1, "doors", nasty, nasty, nasty),
            BundleObservation(3, "silicon", "device", "boot", ""),
        ),
    )

    val settings: Map<String, String> = mapOf(
        BlockPolicy.KEY to BlockPolicy(enabled = true, exempt = setOf("com.example.bank")).encode(),
        Upstream.KEY to Upstream.preset("quad9")!!.encode(),
        WatchSettings.KEY to WatchSettings(enabled = true, intervalHours = 12).encode(),
    )

    val pins: String = BundleSettings.canonicalPins(Pin.encode(listOf(pin(1), pin(2))))

    val networks: Set<String> = setOf("0f".repeat(32), "a1".repeat(32))

    val full = TunnelsData(snapshots, settings, pins, networks)

    /** A bundle written with the real writer from arbitrary entries (names and bytes), for crafted-file tests. */
    fun craft(
        entries: List<Pair<String, ByteArray>>,
        appId: String = TunnelsBundle.APP_ID,
        schema: Long = TunnelsBundle.SCHEMA,
        passphrase: String = PASSPHRASE,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val w = FwxWriter(out, appId, schema, 1_791_028_800_000L, passphrase.toCharArray())
        for ((name, bytes) in entries) w.entry(name, bytes)
        w.finish()
        return out.toByteArray()
    }

    /** The entries of [data] as (name, bytes) pairs, for editing before [craft]. */
    fun entriesOf(data: TunnelsData = full): MutableList<Pair<String, ByteArray>> =
        TunnelsBundle.encode(data, "test").map { it.name to it.bytes.copyOf(it.length) }.toMutableList()

    fun bundle(data: TunnelsData = full, passphrase: String = PASSPHRASE): ByteArray =
        craft(entriesOf(data), passphrase = passphrase)

    /** The names a verified FWX bundle lists, as `fwx.py --list` would show them. */
    fun listNames(file: ByteArray, passphrase: String = PASSPHRASE): List<String> {
        val r = FwxReader(ByteArrayInputStream(file), passphrase.toCharArray(), null, null)
        val names = ArrayList<String>()
        while (true) {
            val e = r.next() ?: break
            names += e.name
            e.stream.readBytes()
        }
        return names
    }

    fun assertCode(code: FwxError, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: FwxException) {
            assertEquals(e.toString(), code, e.code)
        }
    }
}
