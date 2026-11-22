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
        assertEquals("/alts <player|uuid|ip> [page]", ModerationCommand.ALTS.usage)
        assertEquals(2, ModerationCommand.ALTS.parse(arrayOf("Guest", "2")).page)
        assertFailsWith<CommandProblem> { ModerationCommand.ALTS.parse(arrayOf("Guest", "0")) }
        assertEquals(ModerationCommand.entries.size, ModerationCommand.entries.map { it.permission }.toSet().size)
        for (command in ModerationCommand.entries) {
            assertEquals(command.permission, plugin.commands[command.id]?.get("permission"))
            assertTrue(command.permission in permissions)
        }
        assertTrue("quickstart.moderation.notify" in permissions)
        assertTrue("quickstart.moderation.exempt" in permissions)
        assertTrue("quickstart.moderation.exempt.bypass" in permissions)
    }

    @Test
    fun `silent flag, optional removal reasons, banlist, mutelist, and global or actor modhistory parse cleanly`() {
        val silentBefore = ModerationCommand.BAN.parse(arrayOf("-s", "Guest", "Griefing"), now)
        assertTrue(silentBefore.silent)
        assertEquals("Guest", silentBefore.target)
        assertEquals("Griefing", silentBefore.reason)

        val silentAfterTarget = ModerationCommand.TEMPBAN.parse(arrayOf("Guest", "-s", "1h", "Spam"), now)
        assertTrue(silentAfterTarget.silent)
        assertEquals("Guest", silentAfterTarget.target)
        assertEquals(now.plusSeconds(3600), silentAfterTarget.expiry?.resolve(now))
        assertEquals("Spam", silentAfterTarget.reason)

        val silentAfterExpiry = ModerationCommand.TEMPMUTE.parse(arrayOf("Guest", "30m", "--silent", "Flood"), now)
        assertTrue(silentAfterExpiry.silent)
        assertEquals("Flood", silentAfterExpiry.reason)
        assertTrue("42m" in ModerationCommand.TEMPBAN.completeExpiry(arrayOf("-s", "Guest", "42"), now))

        val unbanWithReason = ModerationCommand.UNBAN.parse(arrayOf("Guest", "Appeal", "accepted"), now)
        assertEquals("Guest", unbanWithReason.target)
        assertEquals("Appeal accepted", unbanWithReason.reason)
        assertNull(ModerationCommand.UNBAN.parse(arrayOf("Guest"), now).reason)

        assertFailsWith<CommandProblem> {
            ModerationCommand.BAN.parse(arrayOf("Guest"), now, requiredReasonCommands = setOf("ban", "tempban"))
        }
        assertNull(ModerationCommand.TEMPBAN.parse(arrayOf("Guest", "1h"), now, requiredReasonCommands = emptySet()).reason)

        assertEquals(1, ModerationCommand.BANLIST.parse(emptyArray(), now).page)
        assertEquals(3, ModerationCommand.MUTELIST.parse(arrayOf("3"), now).page)
        assertFailsWith<CommandProblem> { ModerationCommand.BANLIST.parse(arrayOf("0"), now) }

        val recent = ModerationCommand.MODHISTORY.parse(emptyArray(), now)
        assertEquals("", recent.target)
        assertNull(recent.actorFilter)
        assertEquals(1, recent.page)

        val recentPage2 = ModerationCommand.MODHISTORY.parse(arrayOf("2"), now)
        assertEquals("", recentPage2.target)
        assertEquals(2, recentPage2.page)

        val byActor = ModerationCommand.MODHISTORY.parse(arrayOf("actor:Admin", "2"), now)
        assertEquals("Admin", byActor.actorFilter)
        assertEquals(2, byActor.page)
        assertFailsWith<CommandProblem> { ModerationCommand.MODHISTORY.parse(arrayOf("actor:"), now) }

        assertFalse(checkNotNull(IpNetwork.parse("192.0.2.0/24")).isIpv6)
        assertTrue(checkNotNull(IpNetwork.parse("2001:db8::/32")).isIpv6)
    }

    @Test
    fun `custom muted command list controls chat command blocking`() {
        assertTrue(Moderation.isChatCommand("/msg Alex hi"))
        assertFalse(Moderation.isChatCommand("/party hi"))
        assertTrue(Moderation.isChatCommand("/party hi", setOf("party", "g")))
        assertTrue(Moderation.isChatCommand("/myplugin:party hi", setOf("party")))
    }
}
