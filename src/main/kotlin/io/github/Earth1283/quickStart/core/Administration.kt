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
                add("QuickStart commands")
                if (available.isNotEmpty()) add(available.joinToString("  "))
                if (admin) add("/qs modules  /qs doctor  /qs diagnostics startup  /qs reload")
            }
            actor.reply(*lines.toTypedArray())
            return
        }
        if (!actor.onOwner { it.hasPermission("quickstart.admin") }) throw CommandProblem("You do not have permission.")
        val lines = when (words) {
            listOf("modules") -> runtime.modules().map { module ->
                val required = module.requiredDependencies.joinToString().ifEmpty { "none" }
                val optional = module.optionalDependencies.joinToString().ifEmpty { "none" }
                "${module.id}: ${module.state.name.lowercase(Locale.ROOT)} (required: $required, optional: $optional)"
            }
            listOf("doctor") -> runtime.doctor()
            listOf("diagnostics", "startup") -> runtime.startup.lines() + runtime.modules().mapNotNull { module ->
                module.elapsedMillis?.let { elapsed ->
                    val summary = "${module.id}: ${module.state.name.lowercase(Locale.ROOT)} in $elapsed ms"
                    module.failure?.let { "$summary ($it)" } ?: summary
                }
            }
            listOf("reload") -> runtime.reload()
            else -> throw CommandProblem("Usage: /qs [help|modules|doctor|diagnostics startup|reload]")
        }
        actor.reply(*lines.toTypedArray())
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
