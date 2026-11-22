package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.CommandModule
import io.github.Earth1283.quickStart.core.DestinationGroup
import io.github.Earth1283.quickStart.core.FeatureCall
import io.github.Earth1283.quickStart.core.FeatureCommand
import io.github.Earth1283.quickStart.core.SaveResult
import io.github.Earth1283.quickStart.core.SqliteStorage
import io.github.Earth1283.quickStart.core.replyWithNames
import io.github.Earth1283.quickStart.platform.TeleportAction
import io.github.Earth1283.quickStart.platform.TeleportCooldown
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
                actor.reply(if (result == SaveResult.UPDATED) "warps.updated" else "warps.saved", "name" to name)
            }
            FeatureCommand.WARP -> {
                val name = call.name()
                val visible = !call.settings.perWarpPermissions || actor.onOwner { canSeeWarp(it, name) }
                val position = if (visible) storage.get(warps, name) else null
                if (position == null) return actor.reply("warps.not-found", "name" to name)
                val cooldown = TeleportCooldown(TeleportAction.WARP, call.settings.warpCooldownSeconds)
                val result = teleports.move(actor.player(), position, cooldown)
                call.teleportResult(result, "warps.teleported", "name" to name)
            }
            FeatureCommand.DELWARP -> {
                val name = call.name()
                val removed = storage.delete(warps, name)
                actor.reply(if (removed) "warps.deleted" else "warps.not-found", "name" to name)
            }
            FeatureCommand.WARPS -> {
                val names = storage.names(warps)
                val visible = if (call.settings.perWarpPermissions) {
                    actor.onOwner { sender -> names.filter { canSeeWarp(sender, it) } }
                } else names
                replyWithNames(call, visible, FeatureCommand.SETWARP, FeatureCommand.DELWARP)
            }
            else -> error("Unsupported warps command ${call.command.id}")
        }
    }
}

internal fun canSeeWarp(sender: CommandSender, name: String): Boolean =
    sender.hasPermission("quickstart.warps.bypass") || sender.hasPermission("quickstart.warp.$name")
