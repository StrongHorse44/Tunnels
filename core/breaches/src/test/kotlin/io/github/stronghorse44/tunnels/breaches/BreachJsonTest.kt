package io.github.stronghorse44.tunnels.breaches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BreachJsonTest {
    private fun refused(text: String) {
        try {
            BreachJson.parse(text)
            fail("accepted: ${text.take(60)}")
        } catch (_: BreachJson.JsonException) {
        }
    }

    @Test
    fun readsEveryValueKind() {
        val v = BreachJson.parse("""[{"a": 1, "b": -2.5, "c": "x\n\u00e9\ud83d\ude00", "d": [true, false, null], "e": 12345678901234}]""")
        val o = (v as List<*>)[0] as Map<*, *>
        assertEquals(1L, o["a"])
        assertEquals(-2.5, o["b"])
        assertEquals("x\n\u00e9\uD83D\uDE00", o["c"])
        assertEquals(listOf(true, false, null), o["d"])
        assertEquals(12345678901234L, o["e"])
    }

    @Test
    fun depthCapIsFourContainers() {
        BreachJson.parse("[[[[1]]]]")
        refused("[[[[[1]]]]]")
        refused("{\"a\":{\"b\":{\"c\":{\"d\":{}}}}}")
        BreachJson.parse("[{\"DataClasses\":[\"x\"]}]")
    }

    @Test
    fun itemFieldAndStringCaps() {
        BreachJson.parse("[" + List(BreachJson.MAX_ITEMS) { "0" }.joinToString(",") + "]")
        refused("[" + List(BreachJson.MAX_ITEMS + 1) { "0" }.joinToString(",") + "]")
        BreachJson.parse("{" + List(BreachJson.MAX_FIELDS) { "\"k$it\":0" }.joinToString(",") + "}")
        refused("{" + List(BreachJson.MAX_FIELDS + 1) { "\"k$it\":0" }.joinToString(",") + "}")
        BreachJson.parse("\"" + "a".repeat(BreachJson.MAX_STRING_CHARS) + "\"")
        refused("\"" + "a".repeat(BreachJson.MAX_STRING_CHARS + 1) + "\"")
    }

    @Test
    fun nodeCapIsEnforced() {
        // 40 arrays of 20,000 zeros are 800,000 nodes; 80 are over the 1,500,000 cap.
        val chunk = "[" + List(BreachJson.MAX_ITEMS) { "0" }.joinToString(",") + "]"
        BreachJson.parse("[" + List(40) { chunk }.joinToString(",") + "]")
        refused("[" + List(80) { chunk }.joinToString(",") + "]")
    }

    @Test
    fun strictGrammar() {
        for (bad in listOf(
            "", " ", "[1,]", "[,1]", "{\"a\":1,}", "{'a':1}", "[01]", "[+1]", "[1.]", "[.5]", "[1e]", "[NaN]", "[Infinity]",
            "[\"a\tb\"]", "[\"\\x41\"]", "[\"\\u12\"]", "[\"\\uZZZZ\"]", "[1] [2]", "[1]x", "[tru]", "{\"a\":1,\"a\":2}", "\uFEFF[1]",
            "[\"unterminated", "{\"a\" 1}", "{1:2}", "// c\n[1]", "[1 2]",
        )) refused(bad)
        assertTrue(BreachJson.parse(" \t\r\n[ 1 , 2 ]\n") is List<*>)
    }
}
