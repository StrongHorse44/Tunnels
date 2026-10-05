package io.github.stronghorse44.tunnels.backups

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId

/**
 * Runs the tunnel's real scan() on the emulator against a fake export folder (a plain directory behind the same
 * FolderSource interface the SAF folder uses), through the encrypted store's settings, then the rules and actions.
 */
@RunWith(AndroidJUnit4::class)
class BackupsSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = 86_400_000L
    private lateinit var dir: File
    private lateinit var module: BackupsTunnel

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "backups-smoke").apply { deleteRecursively(); mkdirs() }
        module = BackupsTunnel(context, { _, _ -> FileFolderSource(dir) }, { System.currentTimeMillis() }, ZoneId.systemDefault())
        runBlocking {
            module.config.setFolder("content://smoke.test/tree/fake")
            module.config.update { BackupSettings() }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            module.config.setFolder(null)
            module.config.update { BackupSettings() }
        }
        dir.deleteRecursively()
    }

    /** A real FWX v1 header (spec section 1), a stand-in MAC and filler: B06 reads the header only. */
    private fun bundle(appId: String, daysAgo: Long, bodyBytes: Int = 100_000): ByteArray {
        val created = System.currentTimeMillis() - daysAgo * day
        val kdf = "pbkdf2-hmac-sha256".toByteArray()
        val app = appId.toByteArray()
        val length = 42 + app.size + kdf.size + 16
        fun u(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * (n - 1 - it))).toByte() }
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x46, 0x57, 0x58, 0x0D, 0x0A, 0x1A, 0x0A))
            write(u(1, 2)); write(u(length.toLong(), 2)); write(app.size); write(app)
            write(u(1, 4)); write(u(created, 8)); write(kdf.size); write(kdf)
            write(u(600_000, 4)); write(16); write(ByteArray(16) { it.toByte() }); write(ByteArray(7) { 3 }); write(u(1 shl 20, 4))
            write(ByteArray(32) { 1 })
            write(ByteArray(bodyBytes) { 2 })
        }.toByteArray()
    }

    private fun scan(): List<Observation> = runBlocking { module.scan { _, _, _ -> } }

    private fun value(obs: List<Observation>, subject: String, key: String) = obs.firstOrNull { it.subject == subject && it.key == key }?.value

    private fun drafts(obs: List<Observation>): List<FindingDraft> =
        module.rules.flatMap { it.evaluate(RuleContext(module.id, obs, emptyList(), true)) }

    @Test
    fun scansAFakeExportFolderAndRaisesFindingsWithActions() {
        assertEquals("backups", module.id)
        assertTrue("no permission", module.requiredPermissions.isEmpty())
        assertEquals(BackupKeys.VOLATILE, module.volatileKeys)

        File(dir, "tunnels.fwx").writeBytes(bundle("tunnels", 2))
        File(dir, "prikey-new.fwx").writeBytes(bundle("prikey", 3))
        val prikeyOld = File(dir, "prikey-old.fwx").apply { writeBytes(bundle("prikey", 60)) }
        File(dir, "mardigras.fwx").writeBytes(bundle("mardigras", 45))
        File(dir, "photo.jpg").writeBytes(ByteArray(1000))

        // First scan: every app found is watched from now on.
        val first = scan()
        assertTrue(first.all { it.tunnelId == "backups" })
        assertEquals("ok", value(first, "folder", "state"))
        assertEquals("fresh", value(first, "tunnels", "status"))
        assertEquals("2", value(first, "prikey", "files"))
        assertEquals("fresh", value(first, "prikey", "status"))
        assertEquals("stale", value(first, "mardigras", "status"))
        val stale = drafts(first).single()
        assertEquals("mardigras", stale.subject)
        assertEquals(BackupRules.STALE, stale.kind)
        assertEquals(Severity.WARN, stale.severity)
        val actions = module.actionsFor(stale)
        assertEquals("Open Mardi Gras", actions.first().label)
        val result = runBlocking { (actions.first() as FindingAction.Perform).run() }
        assertTrue(result, result.contains("Mardi Gras"))
        assertTrue(runBlocking { module.config.settings() }.seen.containsAll(setOf("tunnels", "prikey", "mardigras")))

        // Move the newest prikey bundle and the old one out: prikey is missing, with its action.
        val moved = File(context.cacheDir, "backups-smoke-out").apply { deleteRecursively(); mkdirs() }
        File(dir, "prikey-new.fwx").renameTo(File(moved, "prikey-new.fwx"))
        prikeyOld.renameTo(File(moved, "prikey-old.fwx"))
        val gone = scan()
        assertEquals("missing", value(gone, "prikey", "status"))
        assertTrue(drafts(gone).any { it.subject == "prikey" && it.kind == BackupRules.MISSING })
        assertEquals("Open Prikey", module.actionsFor(drafts(gone).first { it.subject == "prikey" }).first().label)

        // Put them back and rescan: the finding is gone.
        File(moved, "prikey-new.fwx").renameTo(File(dir, "prikey-new.fwx"))
        val back = scan()
        assertEquals("fresh", value(back, "prikey", "status"))
        assertTrue(drafts(back).none { it.subject == "prikey" })
        moved.deleteRecursively()

        // The restore drill: a date 100 days back is due, with actions; today is not.
        runBlocking { module.config.update { it.copy(drill = module.today().minusDays(100)) } }
        val due = drafts(scan()).single { it.kind == BackupRules.DRILL_DUE }
        assertTrue(module.actionsFor(due).isNotEmpty())
        runBlocking { module.config.update { it.copy(drill = module.today()) } }
        assertTrue(drafts(scan()).none { it.kind == BackupRules.DRILL_DUE })
    }

    @Test
    fun noFolderLostFolderAndTheBoundsOfWhatItReads() {
        runBlocking { module.config.setFolder(null) }
        val none = scan()
        assertEquals("none", value(none, "folder", "state"))
        assertTrue(drafts(none).isEmpty())

        // A folder whose access is gone is "lost", and judges no app.
        runBlocking { module.config.setFolder("content://smoke.test/tree/fake") }
        val lostModule = BackupsTunnel(context, { _, _ -> null }, { System.currentTimeMillis() }, ZoneId.systemDefault())
        val lost = runBlocking { lostModule.scan { _, _, _ -> } }
        assertEquals("lost", value(lost, "folder", "state"))
        assertNotNull(lost.firstOrNull { it.subject == "drill" })
        assertTrue(lost.none { it.subject == "prikey" })
        // ... and says so, with an action, instead of letting the stale findings vanish.
        val warning = lostModule.rules.flatMap { it.evaluate(RuleContext(lostModule.id, lost, emptyList(), true)) }.single()
        assertEquals(BackupRules.FOLDER_LOST, warning.kind)
        assertEquals(Severity.WARN, warning.severity)
        assertTrue(lostModule.actionsFor(warning).isNotEmpty())
    }

    @Test
    fun lumenPerItemFilesAreCountedNotOpenedAndRaiseNothing() {
        repeat(600) { File(dir, "lumen-20261003T101500Z-$it.fwx").writeBytes(ByteArray(2_000) { 4 }) }
        File(dir, "lumen-20261003T101500Z.fwx").writeBytes(bundle("lumen", 2))
        File(dir, "tunnels.fwx").writeBytes(bundle("tunnels", 1))
        File(dir, "prikey.fwx").writeBytes(bundle("prikey", 1))
        runBlocking { module.config.update { it.copy(seen = setOf("lumen", "tunnels", "prikey")) } }
        val obs = scan()
        assertEquals("fresh", value(obs, "lumen", "status"))
        assertEquals("600", value(obs, "lumen", "items"))
        assertEquals("false", value(obs, "folder", "truncated"))
        assertTrue(drafts(obs).isEmpty())
    }

    @Test
    fun theStoredObservationsAreSummariesOnly() {
        File(dir, "secret-name-123.fwx").writeBytes(bundle("prikey", 1))
        File(dir, "unknown.fwx").writeBytes(bundle("notanapp", 1))
        val obs = scan()
        assertTrue(obs.none { "secret-name" in it.value || "secret-name" in it.subject || "notanapp" in it.value || "notanapp" in it.subject })
        assertEquals("1", value(obs, "other", "files"))
    }
}
