package io.github.Earth1283.quickStart.core

import java.io.File
import java.io.Reader
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.configuration.file.YamlConfiguration

internal class Message(val key: String, vararg val placeholders: Pair<String, Any>)

internal class Messages private constructor(private val templates: Map<String, String>) {
    fun withOverrides(file: File): Messages {
        if (!file.exists()) return this
        val yaml = YamlConfiguration().apply { load(file) }
        val overrides = templates.mapNotNull { (key, default) ->
            val value = yaml.get(key) ?: return@mapNotNull null
            require(value is String) { "$key must be a MiniMessage string." }
            if (value == default) return@mapNotNull null
            try {
                miniMessage.deserialize(value)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("$key: ${failure.message}", failure)
            }
            key to value
        }
        return if (overrides.isEmpty()) this else Messages(templates + overrides)
    }

    fun render(message: Message): Component {
        val template = templates.getValue(message.key)
        val tags = TagResolver.builder()
        for ((name, value) in message.placeholders) {
            val placeholder = if (value is Message) Placeholder.component(name, render(value))
            else Placeholder.unparsed(name, value.toString())
            tags.resolver(placeholder)
        }
        return miniMessage.deserialize(template, tags.build())
    }

    companion object {
        private val miniMessage = MiniMessage.miniMessage()

        fun loadDefaults(reader: Reader): Messages {
            val yaml = YamlConfiguration().apply { load(reader) }
            return Messages(yaml.getValues(true).mapNotNull { (key, value) ->
                (value as? String)?.let { key to it }
            }.toMap())
        }
    }
}
