package io.github.Earth1283.quickStart.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `teleport request messages include clickable responses and describe the destination`() {
        val tooltips = mapOf(
            "requests.incoming" to "Let .Alex_1 teleport to you.",
            "requests.incoming-here" to "Teleport to .Alex_1.",
            "requests.incoming-here-fixed" to "Teleport to where .Alex_1 sent the invitation.",
        )
        for ((key, tooltip) in tooltips) {
            val rendered = defaults.render(Message(key, "player" to ".Alex_1", "seconds" to 45))
            val links = rendered.iterable(ComponentIteratorType.DEPTH_FIRST).filter { it.clickEvent() != null }

            assertEquals(listOf("[Accept]", "[Deny]"), links.map(plain::serialize))
            assertEquals(
                listOf(ClickEvent.runCommand("/quickstart:tpaccept .Alex_1"), ClickEvent.runCommand("/quickstart:tpdeny .Alex_1")),
                links.map { it.clickEvent() },
            )
            assertEquals(listOf(HoverEvent.Action.SHOW_TEXT, HoverEvent.Action.SHOW_TEXT), links.map { it.hoverEvent()?.action() })
            assertEquals("$tooltip Request lasts 45s.", plain.serialize(links.first().hoverEvent()?.value() as net.kyori.adventure.text.Component))
        }
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
}
