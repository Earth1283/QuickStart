package io.github.Earth1283.quickStart.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
