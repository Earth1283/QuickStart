package io.github.Earth1283.quickStart.features

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeleportRequestsTest {
    @Test
    fun `requests expire and one outgoing request is retained per sender`() {
        var now = 0L
        val requests = TeleportRequests<String>({ true }) { now }
        assertEquals(RequestStatus.SENT, requests.send("Alex", "Steve", false, 5, 30))
        assertEquals(RequestStatus.COOLDOWN, requests.send("Alex", "Sam", true, 5, 30))
        now = TimeUnit.SECONDS.toNanos(5)
        assertEquals(RequestStatus.SENT, requests.send("Alex", "Sam", true, 5, 30))
        assertNull(requests.take("Steve") { true })
        now = TimeUnit.SECONDS.toNanos(35)
        assertNull(requests.take("Sam") { true })
    }

    @Test
    fun `requests can be selected by sender and removed on disconnect`() {
        val players = mutableSetOf("Alex", "Sam", "Steve")
        val requests = TeleportRequests(players::contains) { 0L }
        requests.send("Alex", "Steve", true, 0, 30)
        requests.send("Sam", "Steve", false, 0, 30)
        assertEquals("Sam", requests.take("Steve") { true }?.from)
        assertEquals(true, requests.take("Steve") { it == "Alex" }?.bringHere)
        requests.send("Steve", "Sam", true, 0, 30)
        players.remove("Steve")
        requests.disconnect("Steve")
        assertNull(requests.take("Sam") { true })
        assertEquals(RequestStatus.DISCONNECTED, requests.send("Alex", "Steve", false, 0, 30))
    }
}
