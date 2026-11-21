package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.CommandModule
import io.github.Earth1283.quickStart.core.DestinationGroup
import io.github.Earth1283.quickStart.core.FeatureCall
import io.github.Earth1283.quickStart.core.FeatureCommand
import io.github.Earth1283.quickStart.core.SaveResult
import io.github.Earth1283.quickStart.core.SqliteStorage
import io.github.Earth1283.quickStart.core.replyWithNames
import io.github.Earth1283.quickStart.platform.Teleports
import org.bukkit.command.CommandSender

internal class Warps(private val storage: SqliteStorage, private val teleports: Teleports) : CommandModule {
    override suspend fun execute(call: FeatureCall) {
        val actor = call.actor
        val warps = DestinationGroup.warps
        when (call.command) {
            FeatureCommand.SETWARP -> {
                val name = call.name()
                val result = storage.save(warps, name, teleports.position(actor.player()))
                actor.reply(if (result == SaveResult.UPDATED) "Warp '$name' updated." else "Warp '$name' saved.")
            }
            FeatureCommand.WARP -> {
                val name = call.name()
                val visible = !call.settings.perWarpPermissions || actor.onOwner { canUse(it, name) }
                val position = if (visible) storage.get(warps, name) else null
                if (position == null) return actor.reply("Warp '$name' was not found.")
                call.teleportResult(teleports.move(actor.player(), position, call.settings.warpCooldownSeconds.takeIf { it > 0 }), "Teleported to warp '$name'.")
            }
            FeatureCommand.DELWARP -> {
                val name = call.name()
                val removed = storage.delete(warps, name)
                actor.reply(if (removed) "Warp '$name' deleted." else "Warp '$name' was not found.")
            }
            FeatureCommand.WARPS -> {
                val names = storage.names(warps)
                val visible = if (call.settings.perWarpPermissions) {
                    actor.onOwner { sender -> names.filter { canUse(sender, it) } }
                } else names
                replyWithNames(actor, visible, "Warps", "No warps are available.")
            }
            else -> error("Unsupported warps command ${call.command.id}")
        }
    }

    private fun canUse(sender: CommandSender, name: String): Boolean =
        sender.hasPermission("quickstart.warps.bypass") || sender.hasPermission("quickstart.warp.$name")
}
