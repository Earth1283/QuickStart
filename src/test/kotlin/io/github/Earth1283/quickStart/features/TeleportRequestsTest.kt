package io.github.Earth1283.quickStart.features

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TeleportRequestsTest {
    @Test
    fun `invitation IDs only consume the live request they identify`() {
        var now = 0L
        val requests = TeleportRequests<String>({ true }) { now }
        val old = assertIs<RequestSend.Sent<String>>(requests.send("Alex", "Steve", false, 0, 30)).request
        val current = assertIs<RequestSend.Sent<String>>(requests.send("Alex", "Steve", true, 0, 30)).request
        val newer = assertIs<RequestSend.Sent<String>>(requests.send("Sam", "Steve", false, 0, 10)).request
        assertNull(requests.takeById("Steve", old.id))
        assertNull(requests.takeById("Sam", current.id))
        assertEquals(listOf(newer, current), requests.pending("Steve").map { it.request })

        now = TimeUnit.SECONDS.toNanos(10)
        assertNull(requests.takeById("Steve", newer.id))
        assertEquals(listOf(PendingRequest(current, 20)), requests.pending("Steve"))
        assertEquals(current, requests.takeById("Steve", current.id))
        assertNull(requests.takeById("Steve", current.id))
        assertEquals(emptyList(), requests.pending("Steve"))
    }

    @Test
    fun `requests expire and one outgoing request is retained per sender`() {
        var now = 0L
        val requests = TeleportRequests<String>({ true }) { now }
        assertIs<RequestSend.Sent<String>>(requests.send("Alex", "Steve", false, 5, 30))
        assertEquals(RequestSend.Cooldown, requests.send("Alex", "Sam", true, 5, 30))
        now = TimeUnit.SECONDS.toNanos(5)
        assertIs<RequestSend.Sent<String>>(requests.send("Alex", "Sam", true, 5, 30))
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
        assertEquals(RequestSend.Disconnected, requests.send("Alex", "Steve", false, 0, 30))
    }

    @Test
    fun `only the recipient's requests are checked and replacements are newest`() {
        var now = 0L
        val checked = mutableSetOf<String>()
        val requests = TeleportRequests<String>(active = {
            checked.add(it)
            true
        }) { now }
        requests.send("Alex", "Steve", false, 0, 30)
        requests.send("Sam", "Steve", false, 0, 30)
        requests.send("Alex", "Steve", true, 0, 30)
        requests.send("Drew", "Steve", false, 0, 1)
        requests.send("Terry", "Robin", false, 0, 30)
        checked.clear()
        now = TimeUnit.SECONDS.toNanos(1)

        val newest = requests.take("Steve") { true }
        assertEquals("Alex", newest?.from)
        assertEquals(true, newest?.bringHere)
        assertEquals(setOf("Alex", "Steve"), checked)
        assertNull(requests.take("Steve") { it == "Drew" })

        requests.disconnect("Steve")
        requests.send("Sam", "Robin", false, 0, 30)
        assertNull(requests.take("Steve") { true })
        assertEquals("Sam", requests.take("Robin") { true }?.from)
        assertEquals("Terry", requests.take("Robin") { true }?.from)
    }
}
