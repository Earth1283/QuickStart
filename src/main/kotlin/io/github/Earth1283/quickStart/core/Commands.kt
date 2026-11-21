package io.github.Earth1283.quickStart.core

import io.github.Earth1283.quickStart.platform.EntityUnavailable
import io.github.Earth1283.quickStart.platform.PlayerSession
import io.github.Earth1283.quickStart.platform.ServerScheduler
import io.github.Earth1283.quickStart.platform.TeleportResult
import org.bukkit.command.CommandSender

internal class CommandProblem(message: String) : IllegalArgumentException(message)

internal enum class CommandArgument { NONE, OPTIONAL_HOME, REQUIRED_WARP, REQUIRED_PLAYER, OPTIONAL_PLAYER }

internal enum class FeatureCommand(val id: String, val module: String, val argument: CommandArgument = CommandArgument.NONE) {
    TPA("tpa", "teleportation", CommandArgument.REQUIRED_PLAYER),
    TPAHERE("tpahere", "teleportation", CommandArgument.REQUIRED_PLAYER),
    TPACCEPT("tpaccept", "teleportation", CommandArgument.OPTIONAL_PLAYER),
    TPDENY("tpdeny", "teleportation", CommandArgument.OPTIONAL_PLAYER),
    BACK("back", "teleportation"),
    SETHOME("sethome", "homes", CommandArgument.OPTIONAL_HOME),
    HOME("home", "homes", CommandArgument.OPTIONAL_HOME),
    DELHOME("delhome", "homes", CommandArgument.OPTIONAL_HOME),
    HOMES("homes", "homes"),
    SETWARP("setwarp", "warps", CommandArgument.REQUIRED_WARP),
    WARP("warp", "warps", CommandArgument.REQUIRED_WARP),
    DELWARP("delwarp", "warps", CommandArgument.REQUIRED_WARP),
    WARPS("warps", "warps"),
    SETSPAWN("setspawn", "spawn"),
    SPAWN("spawn", "spawn");

    val playerOnly: Boolean get() = this != WARPS && this != DELWARP

    fun parse(args: Array<out String>): String? {
        val value = args.singleOrNull()
        return when (argument) {
            CommandArgument.NONE -> {
                if (args.isNotEmpty()) throw CommandProblem("Usage: /$id")
                null
            }
            CommandArgument.OPTIONAL_HOME -> {
                if (args.size > 1) throw CommandProblem("Usage: /$id [name]")
                destinationName(value ?: "home")
            }
            CommandArgument.REQUIRED_WARP -> destinationName(value ?: throw CommandProblem("Usage: /$id <name>"))
            CommandArgument.REQUIRED_PLAYER -> value ?: throw CommandProblem("Usage: /$id <player>")
            CommandArgument.OPTIONAL_PLAYER -> {
                if (args.size > 1) throw CommandProblem("Usage: /$id [player]")
                value
            }
        }
    }

    private fun destinationName(value: String) = DestinationNames.normalize(value)
        ?: throw CommandProblem("Use 1–32 letters, numbers, underscores or hyphens for the name.")

    companion object {
        val byId = entries.associateBy(FeatureCommand::id)
    }
}

internal class CommandActor(
    private val sender: CommandSender,
    val session: PlayerSession?,
    private val scheduler: ServerScheduler,
) {
    fun player(): PlayerSession = session ?: throw CommandProblem("This command is only available to players.")

    suspend fun reply(vararg messages: String) {
        try {
            onOwner { messages.forEach(sender::sendMessage) }
        } catch (_: EntityUnavailable) {
        }
    }

    suspend fun <T> onOwner(action: (CommandSender) -> T): T {
        val player = session ?: return scheduler.global { action(sender) }
        return scheduler.entity(player.player) {
            player.requireActive()
            action(sender)
        }
    }
}

internal data class FeatureCall(
    val command: FeatureCommand,
    val argument: String?,
    val actor: CommandActor,
    val settings: FeatureSettings,
) {
    fun name(): String = checkNotNull(argument)

    suspend fun teleportResult(result: TeleportResult, success: String) = actor.reply(result.failureMessage ?: success)
}

internal interface CommandModule : RuntimeModule {
    suspend fun execute(call: FeatureCall)
}

internal suspend fun replyWithNames(actor: CommandActor, names: List<String>, label: String, empty: String) {
    if (names.isEmpty()) actor.reply(empty)
    else actor.reply(*names.chunked(10).map { "$label: ${it.joinToString(", ")}" }.toTypedArray())
}
