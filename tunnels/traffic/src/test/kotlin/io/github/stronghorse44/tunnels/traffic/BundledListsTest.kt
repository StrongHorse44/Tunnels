package io.github.stronghorse44.tunnels.traffic

import io.github.stronghorse44.tunnels.dns.Blocklists
import io.github.stronghorse44.tunnels.dns.HostList
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledListsTest {
    /** The module directory when Gradle runs the test, or a checkout root above the working directory. */
    private fun asset(path: String): File {
        val here = File("src/main/assets/$path")
        if (here.isFile) return here
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, "tunnels/traffic/src/main/assets/$path").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("asset $path not found from ${File("").absolutePath}")
    }

    @Test
    fun everyBundledListShipsWithItsLicenceAndParses() {
        for (list in Blocklists.ALL) {
            val file = asset(list.asset)
            val header = file.useLines { lines -> lines.takeWhile { it.startsWith("#") }.toList() }
            assertTrue("${list.id} names its licence", header.any { it.startsWith("# Licence:") })
            assertTrue("${list.id} names its source", header.any { it.startsWith("# Source:") })
            val hosts = file.useLines { HostList.parse(it) }
            assertTrue("${list.id} has ${hosts.size} hosts", hosts.size > 1000)
        }
        val adaway = asset(Blocklists.ADAWAY.asset).useLines { HostList.parse(it) }
        assertTrue("doubleclick.net" in adaway || "ad.doubleclick.net" in adaway || "googleads.g.doubleclick.net" in adaway)
    }
}
