package io.github.Earth1283.quickStart.core

import io.github.Earth1283.quickStart.platform.EntityUnavailable
import io.github.Earth1283.quickStart.platform.PlayerSession
import io.github.Earth1283.quickStart.platform.ServerScheduler
import io.github.Earth1283.quickStart.platform.TeleportResult
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.command.CommandSender

internal class CommandProblem(key: String, vararg placeholders: Pair<String, Any>) : IllegalArgumentException(key) {
    val notice = Message(key, *placeholders)
}

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

    fun parse(args: Array<out String>, nameMaxLength: Int = 32): String? {
        val value = args.singleOrNull()
        return when (argument) {
            CommandArgument.NONE -> {
                if (args.isNotEmpty()) throw CommandProblem("usage.no-arguments", "command" to id)
                null
            }
            CommandArgument.OPTIONAL_HOME -> {
                if (args.size > 1) throw CommandProblem("usage.optional-name", "command" to id)
                destinationName(value ?: "home", nameMaxLength)
            }
            CommandArgument.REQUIRED_WARP -> destinationName(value ?: throw CommandProblem("usage.required-name", "command" to id), nameMaxLength)
            CommandArgument.REQUIRED_PLAYER -> value ?: throw CommandProblem("usage.required-player", "command" to id)
            CommandArgument.OPTIONAL_PLAYER -> {
                if (args.size > 1) throw CommandProblem("usage.optional-player", "command" to id)
                value
            }
        }
    }

    private fun destinationName(value: String, maxLength: Int) = DestinationNames.normalize(value, maxLength)
        ?: throw CommandProblem("common.invalid-name", "limit" to maxLength)

    companion object {
        val byId = entries.associateBy(FeatureCommand::id)
    }
}

internal class CommandActor(
    private val sender: CommandSender,
    val session: PlayerSession?,
    private val scheduler: ServerScheduler,
    private val messages: () -> Messages,
) {
    fun player(): PlayerSession = session ?: throw CommandProblem("common.player-only")

    fun render(message: Message): Component = messages().render(message)

    suspend fun reply(key: String, vararg placeholders: Pair<String, Any>) = reply(Message(key, *placeholders))

    suspend fun reply(message: Message) = reply(listOf(message))

    suspend fun reply(notices: List<Message>) {
        val catalog = messages()
        try {
            onOwner { notices.forEach { sender.sendMessage(catalog.render(it)) } }
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

    suspend fun teleportResult(result: TeleportResult, success: String, vararg placeholders: Pair<String, Any>) =
        actor.reply(result.failureMessage ?: Message(success, *placeholders))
}

internal interface CommandModule : RuntimeModule {
    suspend fun execute(call: FeatureCall)
}

internal suspend fun replyWithNames(actor: CommandActor, names: List<String>, section: String) {
    if (names.isEmpty()) return actor.reply("$section.empty")
    val separator = JoinConfiguration.commas(true)
    val notices = names.chunked(10).map { chunk ->
        val destinations = chunk.map { actor.render(Message("$section.item", "name" to it)) }
        Message("$section.list", "names" to Component.join(separator, destinations))
    }
    actor.reply(notices)
}
