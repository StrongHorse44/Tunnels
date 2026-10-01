package io.github.stronghorse44.tunnels.deepmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Exercises the limits with the host's /bin/sh; skipped where there is none. */
class ShellRunnerTest {
    private val sh = "/bin/sh"

    private fun runner(timeout: Long = 20_000L, cap: Int = 1024) = ShellRunner(sh, timeout, cap)

    @Test
    fun returnsCombinedOutputAndExitCode() {
        assumeTrue(File(sh).exists())
        assertEquals("hello", runner().run("echo hello"))
        assertEquals("oops\n[exit 3]", runner().run("echo oops 1>&2; exit 3"))
        assertEquals("", runner().run("true"))
    }

    @Test
    fun killsAfterTimeout() {
        assumeTrue(File(sh).exists())
        val started = System.nanoTime()
        val out = runner(timeout = 300L).run("echo start; sleep 5; echo end")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(out, out.startsWith("start"))
        assertTrue(out, out.endsWith("[timed out]"))
        assertFalse(out.contains("end"))
        assertTrue("took $elapsedMs ms", elapsedMs < 4_000)
    }

    @Test
    fun capsOutput() {
        assumeTrue(File(sh).exists())
        val out = runner(cap = 1024).run("yes | head -c 100000")
        assertTrue(out.endsWith("[truncated]"))
        assertTrue(out.length <= 1024 + "\n[truncated]".length)
    }

    @Test
    fun safeArgumentsAndOneLiners() {
        assertTrue(ShellRunner.isSafeArgument("com.example.app_2"))
        assertFalse(ShellRunner.isSafeArgument("com.example; rm -rf /"))
        assertFalse(ShellRunner.isSafeArgument("a b"))
        assertFalse(ShellRunner.isSafeArgument(""))
        assertEquals("Done.", ShellRunner.oneLine(""))
        assertEquals("Done.", ShellRunner.oneLine("  \n\n"))
        assertEquals("Success", ShellRunner.oneLine("\nSuccess\nmore"))
        assertEquals(160, ShellRunner.oneLine("x".repeat(500)).length)
    }

    @Test
    fun summarisesSeveralCommands() {
        assertEquals("Done.", ShellRunner.summarise(listOf("", "", "")))
        assertEquals("Done.", ShellRunner.summarise(emptyList()))
        assertNull(ShellRunner.errorLine("Success\n"))
        assertEquals("[exit 1]", ShellRunner.errorLine("\n[exit 1]"))
        val notRequested = "Error: Permission android.permission.ACCESS_BACKGROUND_LOCATION is not requested\n[exit 255]"
        assertEquals(
            "2 of 3 done; Error: Permission android.permission.ACCESS_BACKGROUND_LOCATION is not requested",
            ShellRunner.summarise(listOf("", "", notRequested)),
        )
        assertEquals("[timed out]", ShellRunner.summarise(listOf("[timed out]")))
        assertEquals(
            "java.lang.SecurityException: shell cannot revoke",
            ShellRunner.summarise(listOf("java.lang.SecurityException: shell cannot revoke\n\tat x\n[exit 255]")),
        )
        assertTrue(ShellRunner.summarise(listOf("Error: " + "x".repeat(500))).length <= 160)
        assertTrue(ShellRunner.DEFAULT_MAX_OUTPUT_BYTES * 2 <= 512 * 1024)
    }
}
