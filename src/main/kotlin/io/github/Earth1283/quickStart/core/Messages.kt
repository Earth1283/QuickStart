package io.github.Earth1283.quickStart.core

import java.io.File
import java.io.Reader
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.configuration.file.YamlConfiguration

internal class Message(
    val key: String,
    vararg val placeholders: Pair<String, Any>,
    val requestId: UUID? = null,
)

internal data class MessageGroup(val messages: List<Message>, val separator: Component)

internal data class ChatAction(
    val command: String,
    val label: Message,
    val hover: Message,
    val suggest: Boolean = false,
    val consoleText: String = "/${command.trimEnd()}",
)

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

    fun render(message: Message, interactive: Boolean = true): Component {
        val template = templates.getValue(message.key)
        val tags = TagResolver.builder()
        for ((name, value) in message.placeholders) {
            val placeholder = when (value) {
                is Message -> Placeholder.component(name, render(value, interactive))
                is MessageGroup -> Placeholder.component(name, Component.join(
                    JoinConfiguration.separator(value.separator),
                    value.messages.map { render(it, interactive) },
                ))
                is ChatAction -> Placeholder.component(name, renderAction(value, interactive))
                is Component -> Placeholder.component(name, value)
                else -> Placeholder.unparsed(name, value.toString())
            }
            tags.resolver(placeholder)
        }
        tags.resolver(TagResolver.resolver("quickstart") { args, context ->
            val command = args.popOr("Expected a QuickStart command name").value()
            if (command != "qs" && command !in FeatureCommand.byId) {
                throw context.newException("Unknown QuickStart command: $command", args)
            }
            val name = args.popOr("Expected a text placeholder name").value()
            val argument = message.placeholders.lastOrNull { it.first == name }?.second as? String
                ?: throw context.newException("Unknown text placeholder: $name", args)
            val requestId = message.requestId
            val target = if (requestId != null && name == "player" && command in requestResponses) {
                "@$requestId"
            } else argument
            if (interactive) Tag.styling(ClickEvent.runCommand("/quickstart:$command $target"))
            else Tag.styling()
        })
        return miniMessage.deserialize(template, tags.build())
    }

    private fun renderAction(action: ChatAction, interactive: Boolean): Component {
        if (!interactive) return Component.text(action.consoleText)
        val command = "/quickstart:${action.command}"
        val click = if (action.suggest) ClickEvent.suggestCommand(command) else ClickEvent.runCommand(command)
        return render(action.label).clickEvent(click).hoverEvent(render(action.hover))
    }

    companion object {
        private val requestResponses = setOf("tpaccept", "tpdeny")
        private val miniMessage = MiniMessage.miniMessage()

        fun loadDefaults(reader: Reader): Messages {
            val yaml = YamlConfiguration().apply { load(reader) }
            return Messages(yaml.getValues(true).mapNotNull { (key, value) ->
                (value as? String)?.let { key to it }
            }.toMap())
        }
    }
}
