package io.github.stronghorse44.tunnels.devicecheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChecksSummaryTest {
    private fun r(id: String, s: CheckStatus) = CheckResult(id, id, s, "")

    @Test
    fun countsWorstFirst() {
        val groups = listOf(
            CheckGroup("a", listOf(r("1", CheckStatus.PASS), r("2", CheckStatus.WARN), r("3", CheckStatus.PASS))),
            CheckGroup("b", listOf(r("4", CheckStatus.TODO), r("5", CheckStatus.FAIL))),
        )
        assertEquals("1 fail · 1 warn · 1 to do · 2 ok", ChecksSummary.line(groups))
        assertEquals(CheckStatus.FAIL, ChecksSummary.worst(groups))
        assertNull(ChecksSummary.worst(emptyList()))
        assertEquals("", ChecksSummary.line(emptyList()))
    }
}
