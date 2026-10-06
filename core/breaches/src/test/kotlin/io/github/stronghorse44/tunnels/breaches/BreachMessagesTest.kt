package io.github.stronghorse44.tunnels.breaches

import org.junit.Assert.assertTrue
import org.junit.Test

class BreachMessagesTest {
    @Test
    fun everyHttpAnswerHasWords() {
        for (code in listOf(204, 301, 400, 401, 403, 404, 429, 500, 503)) assertTrue(BreachMessages.forHttp(code).contains(Regex("\\d{3}")))
    }

    @Test
    fun noConnectionBlamesThePermissionFirst() {
        assertTrue(BreachMessages.noConnection(false).contains("Network permission is off"))
        assertTrue(BreachMessages.noConnection(true).contains("Could not reach"))
    }
}
