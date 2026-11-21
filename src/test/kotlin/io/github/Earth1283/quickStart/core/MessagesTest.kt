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
    fun `both teleport request messages include clickable responses for the requester`() {
        for (key in listOf("requests.incoming", "requests.incoming-here")) {
            val rendered = defaults.render(Message(key, "player" to ".Alex_1"))
            val links = rendered.iterable(ComponentIteratorType.DEPTH_FIRST).filter { it.clickEvent() != null }

            assertEquals(listOf("[Accept]", "[Deny]"), links.map(plain::serialize))
            assertEquals(
                listOf(ClickEvent.runCommand("/quickstart:tpaccept .Alex_1"), ClickEvent.runCommand("/quickstart:tpdeny .Alex_1")),
                links.map { it.clickEvent() },
            )
            assertEquals(listOf(HoverEvent.Action.SHOW_TEXT, HoverEvent.Action.SHOW_TEXT), links.map { it.hoverEvent()?.action() })
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
