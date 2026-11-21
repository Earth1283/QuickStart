package io.github.Earth1283.quickStart.core

import java.util.Locale
import org.bukkit.command.CommandSender

internal class Administration(private val runtime: QuickStartRuntime) {
    suspend fun execute(actor: CommandActor, args: Array<out String>) {
        val words = args.map { it.lowercase(Locale.ROOT) }
        if (words.isEmpty() || words == listOf("help")) {
            val admin = actor.onOwner { it.hasPermission("quickstart.admin") }
            val commands = FeatureCommand.entries.filter { it.module in runtime.config.enabledModules }
            val available = actor.onOwner { sender ->
                commands.filter { sender.hasPermission(permission(it)) }.map { "/${it.id}" }
            }
            val lines = buildList {
                add(Message("help.heading"))
                if (available.isNotEmpty()) add(Message("help.commands", "commands" to available.joinToString("  ")))
                if (admin) add(Message("help.admin-commands"))
            }
            actor.reply(lines)
            return
        }
        if (!actor.onOwner { it.hasPermission("quickstart.admin") }) throw CommandProblem("common.no-permission")
        val lines = when (words) {
            listOf("modules") -> runtime.modules().map { module ->
                val required = module.requiredDependencies.joinToString().ifEmpty { "none" }
                val optional = module.optionalDependencies.joinToString().ifEmpty { "none" }
                Message("modules.line", "module" to module.id, "state" to module.state.name.lowercase(Locale.ROOT),
                    "required" to required, "optional" to optional)
            }
            listOf("doctor") -> runtime.doctor()
            listOf("diagnostics", "startup") -> runtime.startup.lines() + runtime.modules().mapNotNull { module ->
                module.elapsedMillis?.let { elapsed ->
                    val summary = Message("diagnostics.module", "module" to module.id,
                        "state" to module.state.name.lowercase(Locale.ROOT), "millis" to elapsed)
                    module.failure?.let { Message("diagnostics.module-failed", "summary" to summary, "detail" to it) }
                        ?: summary
                }
            }
            listOf("reload") -> runtime.reload()
            else -> throw CommandProblem("usage.qs")
        }
        actor.reply(lines)
    }

    fun complete(sender: CommandSender, args: Array<out String>): List<String> {
        val admin = sender.hasPermission("quickstart.admin")
        val suggestions = when {
            args.size == 1 && admin -> listOf("help", "modules", "doctor", "diagnostics", "reload")
            args.size == 1 -> listOf("help")
            args.size == 2 && admin && args.first().equals("diagnostics", ignoreCase = true) -> listOf("startup")
            else -> return emptyList()
        }
        return suggestions.filter { it.startsWith(args.last(), ignoreCase = true) }
    }

    private fun permission(command: FeatureCommand): String = when (command) {
        FeatureCommand.TPA, FeatureCommand.TPAHERE, FeatureCommand.TPACCEPT, FeatureCommand.TPDENY -> "quickstart.tpa"
        FeatureCommand.BACK -> "quickstart.back"
        FeatureCommand.HOME, FeatureCommand.SETHOME, FeatureCommand.DELHOME, FeatureCommand.HOMES -> "quickstart.homes"
        FeatureCommand.WARP, FeatureCommand.WARPS -> "quickstart.warps"
        FeatureCommand.SETWARP, FeatureCommand.DELWARP -> "quickstart.warps.manage"
        FeatureCommand.SPAWN -> "quickstart.spawn"
        FeatureCommand.SETSPAWN -> "quickstart.spawn.manage"
    }
}
