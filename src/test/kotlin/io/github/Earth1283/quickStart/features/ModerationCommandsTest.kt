package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.CommandProblem
import io.github.Earth1283.quickStart.core.IpNetwork
import io.github.Earth1283.quickStart.core.parseIp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bukkit.plugin.PluginDescriptionFile

class ModerationCommandsTest {
    private val now = Instant.parse("2026-11-22T10:30:00Z")

    @Test
    fun `temporary commands parse duration and UTC deadlines without consuming the reason`() {
        val relative = ModerationCommand.TEMPBAN.parse(arrayOf("Guest", "1h30m", "Line One<newline>Line Two"), now)
        assertEquals(now.plusSeconds(5400), relative.expiry?.resolve(now))
        assertEquals("Line One<newline>Line Two", relative.reason)
        assertEquals("/tempban <player> <duration> <reason>", ModerationCommand.TEMPBAN.usage)
        assertFailsWith<CommandProblem> { ModerationCommand.TEMPBAN.parse(arrayOf("Guest", "1h"), now) }
        assertFailsWith<CommandProblem> { ModerationCommand.TEMPBAN.requireReason("  ") }
        val absolute = ModerationCommand.TEMPIPMUTE.parse(arrayOf("192.0.2.1", "2026-11-23", "08:15", "Spam"), now)
        assertEquals(Instant.parse("2026-11-23T08:15:00Z"), absolute.expiry?.resolve(now))
        assertEquals("Spam", absolute.reason)
        for (value in listOf("0s", "-1h", "99999999999999999999s", "2026-02-30T09:00", "2026-11-22T10:29:59")) {
            assertFailsWith<CommandProblem> { ModerationExpiry.parse(value, now) }
        }
    }

    @Test
    fun `completion offers supported durations dates and future times`() {
        assertTrue("2026-11-22" in ModerationCommand.TEMPBAN.completeExpiry(arrayOf("Guest", ""), now))
        assertTrue("42m" in ModerationCommand.TEMPMUTE.completeExpiry(arrayOf("Guest", "42"), now))
        assertTrue("11:00" in ModerationCommand.TEMPIPBAN.completeExpiry(arrayOf("Guest", "2026-11-22", ""), now))
        assertFalse("10:00" in ModerationCommand.TEMPIPBAN.completeExpiry(arrayOf("Guest", "2026-11-22", ""), now))
        assertTrue("2026-11-23T09:15" in ModerationCommand.TEMPIPRANGEBAN.completeExpiry(arrayOf("192.0.2.0/24", "2026-11-23T09:1"), now))
    }

    @Test
    fun `network boundaries and canonical addresses include IPv4 and IPv6`() {
        val network = checkNotNull(IpNetwork.parse("192.0.2.15/29"))
        assertEquals("192.0.2.8/29", network.toString())
        assertTrue(network.contains(checkNotNull(parseIp("::ffff:192.0.2.15"))))
        assertFalse(network.contains(checkNotNull(parseIp("192.0.2.16"))))
        assertFalse(network.contains(checkNotNull(parseIp("2001:db8::1"))))
        val ipv6 = checkNotNull(IpNetwork.parse("2001:db8:ffff:1::/49"))
        assertTrue(ipv6.contains(checkNotNull(parseIp("2001:db8:ffff:7fff::"))))
        assertFalse(ipv6.contains(checkNotNull(parseIp("2001:db8:ffff:8000::"))))
        assertNull(IpNetwork.parse("192.0.2.1/33"))
        assertNull(IpNetwork.parse("example.test/24"))
    }

    @Test
    fun `each moderation command declares its own permission`() {
        val plugin = checkNotNull(javaClass.getResourceAsStream("/plugin.yml")).use(::PluginDescriptionFile)
        val permissions = plugin.permissions.map { it.name }.toSet()
        assertEquals(listOf("banip"), plugin.commands["ipban"]?.get("aliases"))
        assertEquals(ModerationCommand.entries.size, ModerationCommand.entries.map { it.permission }.toSet().size)
        for (command in ModerationCommand.entries) {
            assertEquals(command.permission, plugin.commands[command.id]?.get("permission"))
            assertTrue(command.permission in permissions)
        }
    }
}
