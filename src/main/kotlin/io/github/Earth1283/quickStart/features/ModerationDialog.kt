package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.CommandActor
import io.github.Earth1283.quickStart.core.CommandProblem
import io.github.Earth1283.quickStart.core.Message
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry
import io.papermc.paper.registry.data.dialog.input.TextDialogInput.MultilineOptions
import io.papermc.paper.registry.data.dialog.type.DialogType
import java.time.Duration
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.entity.Player

internal const val MAX_REASON_LENGTH = 1024

internal suspend fun editModerationReason(
    actor: CommandActor,
    command: ModerationCommand,
    target: String,
    submit: (String) -> Unit,
) {
    val title = actor.render(Message("moderation.dialog.title", "command" to command.id, "target" to target))
    showModerationDialog(actor, title, listOf(reasonInput(actor))) { response ->
        response.getText("reason")?.let(submit)
    }
}

internal suspend fun showModerationMenu(
    actor: CommandActor,
    target: String,
    commands: Set<ModerationCommand>,
    submit: (String?, String?, String?) -> Unit,
) {
    val title = actor.render(Message("moderation.dialog.menu-title", "target" to target))
    val options = listOf(OptionEntry.create("select", actor.render(Message("moderation.dialog.select-action")), true)) +
        commands.map { OptionEntry.create(it.id, Component.text("/${it.id}"), false) }
    val action = DialogInput.singleOption("action", actor.render(Message("moderation.dialog.action")), options).width(300).build()
    val expiry = DialogInput.text("expiry", actor.render(Message("moderation.dialog.expiry"))).width(300).maxLength(64).build()
    showModerationDialog(actor, title, listOf(action, expiry, reasonInput(actor))) { response ->
        submit(response.getText("action"), response.getText("expiry"), response.getText("reason"))
    }
}

private fun reasonInput(actor: CommandActor) = DialogInput.text("reason", actor.render(Message("moderation.dialog.reason")))
    .width(300).maxLength(MAX_REASON_LENGTH).multiline(MultilineOptions.create(12, 108)).build()

private suspend fun showModerationDialog(
    actor: CommandActor,
    title: Component,
    inputs: List<DialogInput>,
    submit: (DialogResponseView) -> Unit,
) {
    val session = actor.session ?: throw CommandProblem("moderation.dialog-unavailable")
    actor.onOwner {
        val player = session.player
        if (clientProtocol(player) < DIALOG_PROTOCOL) throw CommandProblem("moderation.dialog-unavailable")
        val action = DialogAction.customClick({ response, audience ->
            if (audience === player && session.active) submit(response)
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build())
        val confirm = ActionButton.builder(actor.render(Message("moderation.dialog.confirm"))).action(action).build()
        val cancel = ActionButton.builder(actor.render(Message("moderation.dialog.cancel"))).build()
        player.showDialog(Dialog.create { factory ->
            factory.empty()
                .base(DialogBase.builder(title).pause(false).inputs(inputs).build())
                .type(DialogType.confirmation(confirm, cancel))
        })
    }
}

private const val DIALOG_PROTOCOL = 771 // Minecraft 1.21.6.

private fun clientProtocol(player: Player): Int {
    // ViaVersion can translate the handshake before Paper sees it.
    val via = player.server.pluginManager.getPlugin("ViaVersion")?.takeIf { it.isEnabled } ?: return player.protocolVersion
    return try {
        val loader = via.javaClass.classLoader
        val api = loader.loadClass("com.viaversion.viaversion.api.Via").getMethod("getAPI").invoke(null)
        loader.loadClass("com.viaversion.viaversion.api.ViaAPI")
            .getMethod("getPlayerVersion", UUID::class.java).invoke(api, player.uniqueId) as Int
    } catch (_: ReflectiveOperationException) {
        -1
    } catch (_: LinkageError) {
        -1
    }
}
