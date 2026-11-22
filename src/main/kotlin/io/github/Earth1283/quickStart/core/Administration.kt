package io.github.Earth1283.quickStart.core

import io.github.Earth1283.quickStart.api.ModuleState
import io.github.Earth1283.quickStart.features.ModerationCommand
import java.util.Locale
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender

internal class Administration(private val runtime: QuickStartRuntime) {
    suspend fun execute(actor: CommandActor, args: Array<out String>) {
        val words = args.map { it.lowercase(Locale.ROOT) }
        if (words.isEmpty() || words == listOf("help")) {
            actor.reply(actor.onOwner { help(it, player = actor.session != null) })
            return
        }
        if (words.first() == "mod") {
            if (args.size != 2) throw CommandProblem("moderation.dialog.usage")
            runtime.openModeration(args[1], actor)
            return
        }
        if (!actor.onOwner { it.hasPermission("quickstart.admin") }) throw CommandProblem("common.no-permission")
        val lines = when {
            words == listOf("modules") -> buildList {
                add(Message("modules.heading"))
                for (module in runtime.modules()) {
                    val required = module.requiredDependencies.joinToString().ifEmpty { "none" }
                    val optional = module.optionalDependencies.joinToString().ifEmpty { "none" }
                    add(Message(
                        "modules.line",
                        "module" to module.id,
                        "state" to moduleStateBadge(module.state),
                        "required" to required,
                        "optional" to optional,
                    ))
                }
                add(adminNav("modules"))
            }
            words == listOf("doctor") -> runtime.doctor() + adminNav("doctor")
            words == listOf("config") -> runtime.inspectConfig(null) + adminNav("config")
            words.size == 2 && words.first() == "config" -> {
                val section = words[1]
                if (section !in configSections) throw CommandProblem("usage.qs-config")
                runtime.inspectConfig(section) + adminNav("config")
            }
            words == listOf("diagnostics", "startup") -> buildList {
                add(Message("diagnostics.heading"))
                addAll(runtime.startup.lines())
                for (module in runtime.modules()) {
                    module.elapsedMillis?.let { elapsed ->
                        val summary = Message("diagnostics.module", "module" to module.id,
                            "state" to moduleStateBadge(module.state), "millis" to elapsed)
                        add(module.failure?.let { Message("diagnostics.module-failed", "summary" to summary, "detail" to it) }
                            ?: summary)
                    }
                }
                add(adminNav("startup"))
            }
            words == listOf("reload") -> runtime.reload(dryRun = false)
            words == listOf("reload", "check") -> runtime.reload(dryRun = true)
            else -> throw CommandProblem("usage.qs")
        }
        actor.reply(lines)
    }

    fun complete(sender: CommandSender, args: Array<out String>): List<String> {
        val admin = sender.hasPermission("quickstart.admin")
        val moderator = runtime.canModerate(sender)
        val suggestions = when {
            args.size == 1 -> buildList {
                add("help")
                if (admin) addAll(listOf("modules", "doctor", "config", "diagnostics", "reload"))
                if (moderator) add("mod")
            }
            args.size == 2 && moderator && args.first().equals("mod", ignoreCase = true) -> return runtime.completeModeration(sender, args[1])
            args.size == 2 && admin && args.first().equals("diagnostics", ignoreCase = true) -> listOf("startup")
            args.size == 2 && admin && args.first().equals("config", ignoreCase = true) -> configSections
            args.size == 2 && admin && args.first().equals("reload", ignoreCase = true) -> listOf("check")
            else -> return emptyList()
        }
        return matchingSuggestions(suggestions, args.last())
    }

    private fun moduleStateBadge(state: ModuleState): Message {
        val key = when (state) {
            ModuleState.READY -> "modules.state-ready"
            ModuleState.DISABLED -> "modules.state-disabled"
            ModuleState.FAILED -> "modules.state-failed"
            else -> "modules.state-other"
        }
        return Message(key, "state" to state.name.lowercase(Locale.ROOT))
    }

    private fun adminNav(current: String): Message {
        val actions = buildList {
            if (current != "modules") add(helpAction("modules", "qs modules"))
            if (current != "doctor") add(helpAction("doctor", "qs doctor"))
            if (current != "config") add(helpAction("config", "qs config"))
            if (current != "startup") add(helpAction("startup", "qs diagnostics startup"))
            add(helpAction("reload", "qs reload", suggest = true))
        }
        return Message("ui.action-row", "actions" to MessageGroup(actions, Component.text("  ")))
    }

    private fun help(sender: CommandSender, player: Boolean): List<Message> = buildList {
        add(Message("help.heading"))
        val groups = listOf("homes", "warps", "spawn", "teleportation", "seen").mapNotNull { section ->
            if (section !in runtime.config.enabledModules) return@mapNotNull null
            val available = FeatureCommand.entries.filter {
                it.module == section && (player || !it.playerOnly) && sender.hasPermission(it.permission)
            }
            if (available.isEmpty()) return@mapNotNull null
            val actions = available.map { command ->
                val suggest = command in suggestedActions
                helpAction(command.id, command.id + if (suggest) " " else "", command.usage, suggest)
            }
            Message("help.group", "section" to Message("help.sections.$section"), "actions" to actionGroup(actions))
        }
        if (groups.isNotEmpty()) {
            add(Message("help.commands", "commands" to MessageGroup(groups, Component.newline())))
        }
        if ("moderation" in runtime.config.enabledModules) {
            val actions = ModerationCommand.entries.filter { sender.hasPermission(it.permission) }.map { command ->
                val suggest = command.action != io.github.Earth1283.quickStart.features.ModerationAction.LIST
                Message("ui.control", "action" to ChatAction(
                    if (suggest) "${command.id} " else command.id,
                    Message("help.moderation-command", "command" to command.id),
                    Message("help.moderation-usage", "usage" to command.usage),
                    suggest = suggest,
                    consoleText = command.usage,
                ))
            }
            if (actions.isNotEmpty()) {
                val dialog = helpAction("mod", "qs mod ", "/qs mod <target>", suggest = true)
                add(Message("help.group", "section" to Message("help.sections.moderation"), "actions" to actionGroup(listOf(dialog) + actions)))
            }
        }
        if (sender.hasPermission("quickstart.admin")) {
            val actions = listOf(
                helpAction("modules", "qs modules"),
                helpAction("doctor", "qs doctor"),
                helpAction("config", "qs config"),
                helpAction("startup", "qs diagnostics startup"),
                helpAction("reload", "qs reload", suggest = true),
            )
            add(Message("help.admin-commands", "commands" to actionGroup(actions)))
        }
    }

    private fun actionGroup(actions: List<Message>) = MessageGroup(
        actions.chunked(3).map { row ->
            Message("ui.action-row", "actions" to MessageGroup(row, Component.text("  ")))
        },
        Component.text("\n  "),
    )

    private fun helpAction(
        key: String,
        command: String,
        usage: String = "/$command",
        suggest: Boolean = false,
    ) = Message(
        "ui.control",
        "action" to ChatAction(command, Message("help.labels.$key"), Message("help.details.$key"), suggest, usage),
    )

    companion object {
        val configSections = listOf("startup", "modules", "homes", "warps", "spawn", "teleportation", "moderation", "performance", "storage")

        private val suggestedActions = setOf(
            FeatureCommand.SETHOME, FeatureCommand.DELHOME, FeatureCommand.SETWARP, FeatureCommand.WARP,
            FeatureCommand.DELWARP, FeatureCommand.SETSPAWN, FeatureCommand.TPA, FeatureCommand.TPAHERE,
            FeatureCommand.TPACCEPT, FeatureCommand.TPDENY, FeatureCommand.SEEN,
        )
    }
}
