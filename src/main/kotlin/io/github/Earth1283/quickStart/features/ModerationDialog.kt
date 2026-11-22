package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.CommandActor
import io.github.Earth1283.quickStart.core.CommandProblem
import io.github.Earth1283.quickStart.core.Message
import io.github.Earth1283.quickStart.core.ModerationSettings
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry
import io.papermc.paper.registry.data.dialog.input.TextDialogInput.MultilineOptions
import io.papermc.paper.registry.data.dialog.type.DialogType
import java.time.Duration
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.entity.Player

internal const val MAX_REASON_LENGTH = ModerationSettings.DEFAULT_MAX_REASON_LENGTH

internal data class ModerationMenuResponse(
    val action: String?,
    val expiry: String?,
    val reason: String?,
    val silent: Boolean,
)

internal suspend fun editModerationReason(
    actor: CommandActor,
    command: ModerationCommand,
    target: String,
    initialSilent: Boolean,
    maxReasonLength: Int,
    presets: List<String>,
    submit: (String, Boolean) -> Unit,
) {
    val title = actor.render(Message("moderation.dialog.title", "command" to command.id, "target" to target))
    val inputs = buildList {
        presetInput(actor, presets)?.let(::add)
        add(reasonInput(actor, maxReasonLength))
        add(silentInput(actor, initialSilent))
    }
    showModerationDialog(actor, title, emptyList(), inputs) { response ->
        val resolvedReason = resolveReason(response, presets) ?: ""
        val silent = response.getBoolean("silent") ?: initialSilent
        submit(resolvedReason, silent)
    }
}

internal suspend fun showModerationMenu(
    actor: CommandActor,
    target: String,
    summaryLines: List<Component>,
    commands: Set<ModerationCommand>,
    maxReasonLength: Int,
    presets: List<String>,
    submit: (ModerationMenuResponse) -> Unit,
) {
    val title = actor.render(Message("moderation.dialog.menu-title", "target" to target))
    val options = listOf(OptionEntry.create("select", actor.render(Message("moderation.dialog.select-action")), true)) +
        commands.map { OptionEntry.create(it.id, Component.text("${it.displayName} (/${it.id})"), false) }
    val action = DialogInput.singleOption("action", actor.render(Message("moderation.dialog.action")), options).width(300).build()
    val expiry = DialogInput.text("expiry", actor.render(Message("moderation.dialog.expiry"))).width(300).maxLength(64).build()
    val body = summaryLines.map { DialogBody.plainMessage(it, 300) }
    val inputs = buildList {
        add(action)
        add(expiry)
        presetInput(actor, presets)?.let(::add)
        add(reasonInput(actor, maxReasonLength))
        add(silentInput(actor, false))
    }
    showModerationDialog(actor, title, body, inputs) { response ->
        submit(ModerationMenuResponse(
            action = response.getText("action"),
            expiry = response.getText("expiry"),
            reason = resolveReason(response, presets),
            silent = response.getBoolean("silent") == true,
        ))
    }
}

private fun presetInput(actor: CommandActor, presets: List<String>): DialogInput? {
    if (presets.isEmpty()) return null
    val options = buildList {
        add(OptionEntry.create(CUSTOM_PRESET_ID, actor.render(Message("moderation.dialog.preset-custom")), true))
        presets.forEachIndexed { index, text ->
            add(OptionEntry.create("preset_$index", Component.text(text), false))
        }
    }
    return DialogInput.singleOption("preset", actor.render(Message("moderation.dialog.preset")), options).width(300).build()
}

private fun resolveReason(response: DialogResponseView, presets: List<String>): String? {
    val typed = response.getText("reason")?.trim()?.takeIf(String::isNotEmpty)
    if (typed != null) return typed
    val presetId = response.getText("preset") ?: return null
    if (presetId == CUSTOM_PRESET_ID || !presetId.startsWith("preset_")) return null
    val index = presetId.removePrefix("preset_").toIntOrNull() ?: return null
    return presets.getOrNull(index)
}

private fun reasonInput(actor: CommandActor, maxReasonLength: Int) =
    DialogInput.text("reason", actor.render(Message("moderation.dialog.reason")))
        .width(300).maxLength(maxReasonLength).multiline(MultilineOptions.create(12, 108)).build()

private fun silentInput(actor: CommandActor, initial: Boolean) =
    DialogInput.bool("silent", actor.render(Message("moderation.dialog.silent"))).initial(initial).build()

private suspend fun showModerationDialog(
    actor: CommandActor,
    title: Component,
    body: List<DialogBody>,
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
            val base = DialogBase.builder(title).pause(false).inputs(inputs)
            if (body.isNotEmpty()) base.body(body)
            factory.empty()
                .base(base.build())
                .type(DialogType.confirmation(confirm, cancel))
        })
    }
}

private const val CUSTOM_PRESET_ID = "custom"
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
