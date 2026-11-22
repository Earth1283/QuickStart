package io.github.Earth1283.quickStart.core

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.InvalidConfigurationException
import org.junit.jupiter.api.io.TempDir

class MessagesTest {
    @TempDir
    lateinit var directory: File

    private val defaults = checkNotNull(javaClass.getResourceAsStream("/message.yml"))
        .reader(Charsets.UTF_8).use(Messages::loadDefaults)
    private val plain = PlainTextComponentSerializer.plainText()

    @Test
    fun `custom formatting applies while placeholder values remain literal`() {
        val file = directory.resolve("message.yml")
        file.writeText("homes: {saved: '<gold>Saved <name>!</gold>'}")
        val messages = defaults.withOverrides(file)

        val rendered = messages.render(Message("homes.saved", "name" to "<red>base</red>"))

        assertEquals("Saved <red>base</red>!", plain.serialize(rendered))
        assertEquals(NamedTextColor.GOLD, rendered.color())
        assertEquals("Usage: /warp <name>", plain.serialize(messages.render(Message("usage.required-name", "command" to "warp"))))
    }

    @Test
    fun `request buttons target the exact invitation and describe its destination`() {
        val id = UUID.randomUUID()
        val tooltips = mapOf(
            "incoming" to "Let .Alex_1 teleport to you.",
            "incoming-here" to "Teleport to .Alex_1's current location.",
            "incoming-here-fixed" to "Teleport to where .Alex_1 sent the invitation.",
        )
        for ((variant, tooltip) in tooltips) {
            val catalogMessage = Message(
                "requests.$variant",
                "player" to ".Alex_1",
                "seconds" to 45,
                "accept" to ChatAction(
                    "tpaccept @$id", Message("requests.accept"), Message("requests.$variant-hover", "player" to ".Alex_1"),
                ),
                "deny" to ChatAction(
                    "tpdeny @$id", Message("requests.deny"), Message("requests.deny-hover", "player" to ".Alex_1"),
                ),
                requestId = id,
            )
            val rendered = defaults.render(catalogMessage)
            val links = rendered.iterable(ComponentIteratorType.DEPTH_FIRST).filter { it.clickEvent() != null }
            assertTrue(plain.serialize(rendered).contains("Expires in 45s."))
            assertEquals(listOf("[Accept]", "[Deny]"), links.map(plain::serialize))
            assertEquals(
                listOf(ClickEvent.runCommand("/quickstart:tpaccept @$id"), ClickEvent.runCommand("/quickstart:tpdeny @$id")),
                links.map { it.clickEvent() },
            )
            assertEquals(listOf(HoverEvent.Action.SHOW_TEXT, HoverEvent.Action.SHOW_TEXT), links.map { it.hoverEvent()?.action() })
            assertEquals(tooltip, plain.serialize(links.first().hoverEvent()?.value() as Component))
        }
    }

    @Test
    fun `legacy request templates keep their name and wording but target the exact invitation`() {
        val file = directory.resolve("message.yml")
        file.writeText("""
            requests:
              incoming: "<player> <quickstart:tpaccept:player>Join</quickstart>"
        """.trimIndent())
        val id = UUID.randomUUID()
        val rendered = defaults.withOverrides(file).render(Message("requests.incoming", "player" to "Alex", requestId = id))
        assertEquals("Alex Join", plain.serialize(rendered))
        val link = rendered.iterable(ComponentIteratorType.DEPTH_FIRST).single { it.clickEvent() != null }
        assertEquals(ClickEvent.runCommand("/quickstart:tpaccept @$id"), link.clickEvent())
    }

    @Test
    fun `management actions prefill commands and console sees literal syntax`() {
        val message = Message("ui.control", "action" to ChatAction(
            "delhome base", Message("ui.delete"), Message("homes.delete-hover", "name" to "base"), suggest = true,
        ))
        val player = defaults.render(message)
        val link = player.iterable(ComponentIteratorType.DEPTH_FIRST).single { it.clickEvent() != null }
        assertEquals(ClickEvent.suggestCommand("/quickstart:delhome base"), link.clickEvent())
        val console = defaults.render(message, interactive = false)
        assertEquals("/delhome base", plain.serialize(console))
        assertTrue(console.iterable(ComponentIteratorType.DEPTH_FIRST).none { it.clickEvent() != null })
    }

    @Test
    fun `menu page only links to neighboring available pages`() {
        val page = (1..11).toList().chatPage(2).navigation(FeatureCommand.HOMES)
        val rendered = defaults.render(page)
        assertTrue(plain.serialize(rendered).startsWith("Page 2 of 3"))
        assertEquals(
            listOf(ClickEvent.runCommand("/quickstart:homes 1"), ClickEvent.runCommand("/quickstart:homes 3")),
            rendered.iterable(ComponentIteratorType.DEPTH_FIRST).mapNotNull { it.clickEvent() },
        )
    }

    @Test
    fun `destination names stay clickable inside the list template`() {
        for ((section, command) in mapOf("homes" to "home", "warps" to "warp")) {
            val item = defaults.render(Message("$section.item", "name" to "base-1"))
            val list = defaults.render(Message("$section.list", "names" to item))
            val link = list.iterable(ComponentIteratorType.DEPTH_FIRST).single { it.clickEvent() != null }

            assertEquals("base-1", plain.serialize(link))
            assertEquals(ClickEvent.runCommand("/quickstart:$command base-1"), link.clickEvent())
            assertEquals("Teleport to $command base-1", plain.serialize(link.hoverEvent()?.value() as net.kyori.adventure.text.Component))
        }
    }

    @Test
    fun `invalid overrides cannot replace a loaded catalog`() {
        val file = directory.resolve("message.yml")
        file.writeText("homes: {empty: 'Nothing saved'}")
        val messages = defaults.withOverrides(file)

        file.writeText("homes: [")
        assertFailsWith<InvalidConfigurationException> { defaults.withOverrides(file) }
        file.writeText("homes: {empty: true}")
        assertFailsWith<IllegalArgumentException> { defaults.withOverrides(file) }

        assertEquals("Nothing saved", plain.serialize(messages.render(Message("homes.empty"))))
    }

    @Test
    fun `bedspawn, safeteleport, seen, and alts message templates render cleanly`() {
        assertTrue(plain.serialize(defaults.render(Message("bedspawn.enabled"))).contains("bed or respawn anchor"))
        assertTrue(plain.serialize(defaults.render(Message("bedspawn.buttons.enable"))).contains("[Use bed/anchor]"))
        assertTrue(plain.serialize(defaults.render(Message("bedspawn.buttons.disable"))).contains("[Use server spawn]"))
        assertTrue(plain.serialize(defaults.render(Message("safeteleport.disabled"))).contains("disabled"))
        assertTrue(plain.serialize(defaults.render(Message("safeteleport.buttons.enable"))).contains("[Enable safety]"))
        assertTrue(plain.serialize(defaults.render(Message("safeteleport.buttons.disable"))).contains("[Disable safety]"))
        assertTrue(plain.serialize(defaults.render(Message("teleport.unsafe-hazard"))).contains("/safeteleport off"))
        assertEquals(
            "Alex is currently online (for 5m 10s).",
            plain.serialize(defaults.render(Message("seen.online", "player" to "Alex", "duration" to "5m 10s"))),
        )
        val altEntry = defaults.render(Message(
            "moderation.alts.entry",
            "player" to "Steve",
            "status" to Message("moderation.alts.badge-banned"),
            "seen" to Message("moderation.alts.last-seen", "duration" to "2h 15m"),
            "ips" to "192.0.2.10",
        ))
        assertTrue(plain.serialize(altEntry).contains("Steve [Banned]"))
        assertTrue(plain.serialize(altEntry).contains("192.0.2.10"))
    }
}
