package io.github.Earth1283.quickStart.core

import io.github.Earth1283.quickStart.features.ModerationCommand
import java.io.File
import java.io.Reader
import java.util.Locale
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

internal data class MessagesLoadResult(
    val messages: Messages,
    val warnings: List<String>,
    val overrideCount: Int,
)

internal class Messages private constructor(private val templates: Map<String, String>) {
    fun withOverrides(file: File): Messages = withOverridesWithDiagnostics(file).messages

    fun withOverridesWithDiagnostics(file: File): MessagesLoadResult {
        if (!file.exists()) return MessagesLoadResult(this, emptyList(), 0)
        val yaml = YamlConfiguration().apply { load(file) }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val overrides = mutableMapOf<String, String>()

        for ((key, default) in templates) {
            val value = yaml.get(key) ?: continue
            if (value !is String) {
                errors.add("$key must be a MiniMessage string.")
                continue
            }
            if (value == default) continue
            try {
                miniMessage.deserialize(value)
            } catch (failure: IllegalArgumentException) {
                errors.add("$key: ${failure.message}")
                continue
            }
            val expectedPlaceholders = extractPlaceholders(default)
            val tagError = validateQuickstartTags(key, value, expectedPlaceholders)
            if (tagError != null) {
                errors.add(tagError)
                continue
            }
            val actualPlaceholders = extractPlaceholders(value)
            val missing = expectedPlaceholders - actualPlaceholders
            if (missing.isNotEmpty()) {
                warnings.add("message.yml '$key' omits placeholder(s): ${missing.sorted().joinToString(", ") { "<$it>" }}")
            }
            overrides[key] = value
        }

        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.joinToString("; "))
        }

        for (path in collectLeafPaths(yaml)) {
            if (path !in templates) {
                val suggestion = closestKey(path, templates.keys)
                warnings.add(
                    if (suggestion != null) "Unknown message key '$path' (did you mean '$suggestion'?)"
                    else "Unknown message key '$path'",
                )
            }
        }

        val catalog = if (overrides.isEmpty()) this else Messages(templates + overrides)
        return MessagesLoadResult(catalog, warnings, overrides.size)
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
            if (!isKnownCommand(command)) {
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
        private val quickstartTagPattern = Regex("<quickstart(?::([^:>]*))?(?::([^:>]*))?(?::[^>]*)?>")
        private val simpleTagPattern = Regex("(?<!\\\\)<([a-z][a-z0-9_-]*)>")
        private val standardTags = setOf(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white",
            "bold", "b", "italic", "i", "em", "underlined", "u", "strikethrough", "st",
            "obfuscated", "obf", "reset", "newline", "br", "rainbow", "gradient",
        )

        private fun isKnownCommand(command: String): Boolean =
            command == "qs" || command in FeatureCommand.byId || command in ModerationCommand.byId

        private fun validateQuickstartTags(key: String, template: String, expectedPlaceholders: Set<String>): String? {
            for (match in quickstartTagPattern.findAll(template)) {
                val command = match.groupValues[1]
                val placeholder = match.groupValues[2]
                if (command.isEmpty()) return "$key: <quickstart> tag requires a command name."
                if (!isKnownCommand(command)) return "$key: unknown QuickStart command '$command' in <quickstart> tag."
                if (placeholder.isEmpty()) return "$key: <quickstart:$command> tag requires a placeholder name."
                if (expectedPlaceholders.isNotEmpty() && placeholder !in expectedPlaceholders) {
                    return "$key: unknown placeholder '$placeholder' in <quickstart:$command:$placeholder> tag."
                }
            }
            return null
        }

        internal fun extractPlaceholders(template: String): Set<String> = buildSet {
            for (match in simpleTagPattern.findAll(template)) {
                val tag = match.groupValues[1].lowercase(Locale.ROOT)
                if (tag !in standardTags) add(tag)
            }
            for (match in quickstartTagPattern.findAll(template)) {
                val placeholder = match.groupValues[2].lowercase(Locale.ROOT)
                if (placeholder.isNotEmpty()) add(placeholder)
            }
        }

        fun loadDefaults(reader: Reader): Messages {
            val yaml = YamlConfiguration().apply { load(reader) }
            return Messages(yaml.getValues(true).mapNotNull { (key, value) ->
                (value as? String)?.let { key to it }
            }.toMap())
        }
    }
}
