package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.AltAccount
import io.github.Earth1283.quickStart.core.ChatAction
import io.github.Earth1283.quickStart.core.CommandActor
import io.github.Earth1283.quickStart.core.CommandProblem
import io.github.Earth1283.quickStart.core.IpNetwork
import io.github.Earth1283.quickStart.core.Message
import io.github.Earth1283.quickStart.core.MessageGroup
import io.github.Earth1283.quickStart.core.ModerationRecord
import io.github.Earth1283.quickStart.core.RuntimeModule
import io.github.Earth1283.quickStart.core.Sanction
import io.github.Earth1283.quickStart.core.SanctionKey
import io.github.Earth1283.quickStart.core.SanctionKind
import io.github.Earth1283.quickStart.core.SanctionTarget
import io.github.Earth1283.quickStart.core.SqliteStorage
import io.github.Earth1283.quickStart.core.TargetKind
import io.github.Earth1283.quickStart.core.chatPage
import io.github.Earth1283.quickStart.core.formatDuration
import io.github.Earth1283.quickStart.core.formatExpiration
import io.github.Earth1283.quickStart.core.formatTimestamp
import io.github.Earth1283.quickStart.core.matchingSuggestions
import io.github.Earth1283.quickStart.core.parseIp
import io.github.Earth1283.quickStart.platform.EntityUnavailable
import io.github.Earth1283.quickStart.platform.PlayerSession
import io.github.Earth1283.quickStart.platform.Players
import io.github.Earth1283.quickStart.platform.ServerScheduler
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.event.player.PlayerKickEvent

internal class Moderation(
    private val storage: SqliteStorage,
    private val players: Players,
    private val scheduler: ServerScheduler,
    private val server: Server,
    private val scope: CoroutineScope,
    private val render: (Message) -> Component,
    private val submitDialog: (CommandActor, suspend () -> Unit) -> Unit,
) : RuntimeModule {
    override suspend fun prepare() = storage.loadSanctions()

    suspend fun execute(command: ModerationCommand, args: Array<out String>, actor: CommandActor) {
        requirePermission(actor, command)
        val input = command.parse(args)
        val target = resolveTarget(command, input.target)
        val label = if (target is SanctionTarget.Range) target.value else input.target
        when (command.action) {
            ModerationAction.STATUS -> return status(command.kind, target, label, actor)
            ModerationAction.HISTORY -> return history(target, label, input.page, actor)
            ModerationAction.ALTS -> return alts(target, label, input.page, actor)
            else -> Unit
        }
        if (input.reason == "<usedialogue>") {
            editModerationReason(actor, command, label) { reason ->
                submitDialog(actor) {
                    apply(command, target, label, input.expiry, reason.trim().takeIf(String::isNotEmpty), actor)
                }
            }
        } else {
            apply(command, target, label, input.expiry, input.reason, actor)
        }
    }

    suspend fun openMenu(input: String, actor: CommandActor) {
        val allowed = permittedCommands(actor)
        if (allowed.isEmpty()) throw CommandProblem("common.no-permission")
        val target = resolveTarget(ModerationCommand.BANSTATUS, input)
        val online = (target as? SanctionTarget.Account)?.let { players.find(it.id)?.takeIf(PlayerSession::active) }
        val available = buildMap {
            for (command in allowed) {
                val resolved = when {
                    command == ModerationCommand.KICK -> target.takeIf { online != null }
                    command == ModerationCommand.ALTS -> target.takeIf { it !is SanctionTarget.Range }
                    command.isInspection || command.target == target.kind -> target
                    command.target == TargetKind.IP -> online?.address?.let(SanctionTarget::Ip)
                    else -> null
                }
                if (resolved != null) put(command, resolved)
            }
        }
        if (available.isEmpty()) throw CommandProblem("moderation.dialog.no-actions")
        showModerationMenu(actor, input, available.keys) { choice, deadline, reason ->
            submitDialog(actor) {
                val command = choice?.let(ModerationCommand.byId::get) ?: throw CommandProblem("moderation.dialog.action-required")
                val resolved = available[command] ?: throw CommandProblem("moderation.dialog.action-required")
                requirePermission(actor, command)
                when (command.action) {
                    ModerationAction.STATUS -> status(command.kind, resolved, input, actor)
                    ModerationAction.HISTORY -> history(resolved, input, 1, actor)
                    ModerationAction.ALTS -> alts(resolved, input, 1, actor)
                    else -> {
                        val expiry = if (command.action == ModerationAction.TEMPORARY) {
                            val value = deadline?.trim()?.replace(Regex("\\s+"), "T").orEmpty()
                            ModerationExpiry.parse(value, Instant.now())
                        } else null
                        apply(command, resolved, input, expiry, reason?.trim()?.takeIf(String::isNotEmpty), actor)
                    }
                }
            }
        }
    }

    fun complete(command: ModerationCommand, args: Array<out String>): List<String> {
        if (args.size != 1) return command.completeExpiry(args)
        val names = if (command.target == TargetKind.RANGE) emptyList() else {
            val online = players.onlineSessions().map(PlayerSession::name)
            if (command == ModerationCommand.KICK || command.target == TargetKind.IP) online
            else online + storage.cachedPlayerNames()
        }
        val targets = when {
            command.action == ModerationAction.HISTORY || command.action == ModerationAction.ALTS ->
                SanctionKind.entries.flatMap { storage.sanctions.targetValues(it, null) }
            command.isInspection -> storage.sanctions.targetValues(command.kind, null)
            command.action == ModerationAction.REMOVE -> storage.sanctions.targetValues(command.kind, command.target)
            else -> emptyList()
        }
        return matchingSuggestions(names + targets, args[0])
    }

    fun completeMenu(sender: CommandSender, prefix: String): List<String> = matchingSuggestions(
        ModerationCommand.entries.filter { sender.hasPermission(it.permission) }
            .flatMap { complete(it, arrayOf(prefix)) }, prefix,
    )

    fun banNotice(id: UUID, address: InetAddress?): Component? =
        storage.sanctions.find(SanctionKind.BAN, id, address)?.let { render(notice(it)) }

    fun kickIfBanned(session: PlayerSession): Boolean {
        val sanction = storage.sanctions.find(SanctionKind.BAN, session.id, session.address) ?: return false
        val cause = if (sanction.key.target is SanctionTarget.Account) PlayerKickEvent.Cause.BANNED else PlayerKickEvent.Cause.IP_BANNED
        session.player.kick(render(notice(sanction)), cause)
        return true
    }

    fun muteNotice(session: PlayerSession): Message? =
        storage.sanctions.find(SanctionKind.MUTE, session.id, session.address)?.let(::notice)

    private suspend fun resolveTarget(command: ModerationCommand, input: String): SanctionTarget {
        if (command.isInspection) {
            parseIp(input)?.let { return SanctionTarget.Ip(it) }
            if ('/' in input) {
                if (command == ModerationCommand.ALTS) throw CommandProblem("moderation.alts.invalid-target")
                return SanctionTarget.Range(IpNetwork.parse(input) ?: throw CommandProblem("moderation.invalid-range"))
            }
        }
        return when (command.target) {
            TargetKind.ACCOUNT -> {
                val uuid = players.find(input)?.id ?: parseId(input)
                val id = if (command == ModerationCommand.KICK) {
                    uuid?.let(players::find)?.takeIf(PlayerSession::active)?.id
                } else uuid
                    ?: storage.findPlayerProfileByName(input)?.id
                    ?: scheduler.global { server.getOfflinePlayerIfCached(input)?.uniqueId }
                SanctionTarget.Account(id ?: throw CommandProblem(
                    if (command == ModerationCommand.KICK) "moderation.offline-player" else "moderation.unknown-account",
                ))
            }
            TargetKind.IP -> SanctionTarget.Ip(parseIp(input) ?: players.find(input)?.address
                ?: throw CommandProblem("moderation.invalid-ip"))
            TargetKind.RANGE -> SanctionTarget.Range(IpNetwork.parse(input) ?: throw CommandProblem("moderation.invalid-range"))
        }
    }

    private suspend fun apply(
        command: ModerationCommand,
        target: SanctionTarget,
        label: String,
        expiry: ModerationExpiry?,
        reason: String?,
        actor: CommandActor,
    ) {
        requirePermission(actor, command)
        command.requireReason(reason)
        if (reason != null && reason.length > MAX_REASON_LENGTH) {
            throw CommandProblem("moderation.reason-length", "limit" to MAX_REASON_LENGTH)
        }
        val key = SanctionKey(command.kind, target)
        when (command.action) {
            ModerationAction.KICK -> {
                val session = players.find((target as SanctionTarget.Account).id) ?: throw CommandProblem("moderation.offline-player")
                try {
                    scope.async {
                        scheduler.entity(session.player) {
                            session.requireActive()
                            session.player.kick(render(Message("moderation.kicked", "reason" to reasonText(reason))), PlayerKickEvent.Cause.KICK_COMMAND)
                        }
                        storage.logModeration(ModerationRecord(command.id, target, label, actor.name, Instant.now(), reason))
                    }.await()
                } catch (_: EntityUnavailable) {
                    throw CommandProblem("moderation.offline-player")
                }
                actor.reply(listOf(Message("moderation.kick-confirmed", "target" to label)) + controls(actor, key, allowUndo = false))
            }
            ModerationAction.REMOVE -> {
                val removed = storage.deleteSanction(key, ModerationRecord(command.id, target, label, actor.name, Instant.now()))
                val reply = Message(if (removed) "moderation.removed" else "moderation.no-match", "command" to command.id, "target" to label)
                actor.reply(listOf(reply) + controls(actor, key, allowUndo = false))
            }
            ModerationAction.APPLY, ModerationAction.TEMPORARY -> {
                val now = Instant.now()
                val expiresAt = expiry?.resolve(now)?.let { Instant.ofEpochMilli(it.toEpochMilli()) }
                val sanction = Sanction(key, reason, expiresAt)
                // A committed ban still needs to reach connected players if the issuing player leaves.
                val undo = reversal(key)
                val canShorten = undo != null && actor.onOwner { it.hasPermission(undo.permission) }
                val record = ModerationRecord(command.id, target, label, actor.name, now, reason, expiresAt)
                val applied = scope.async {
                    storage.saveSanction(sanction, record, canShorten).also { saved ->
                        if (saved && key.kind == SanctionKind.BAN) disconnectMatching(target)
                    }
                }.await()
                if (!applied) throw CommandProblem("moderation.cannot-shorten")
                val reply = Message("moderation.applied", "command" to command.id, "target" to label, "expiration" to expiration(expiresAt))
                actor.reply(listOf(reply) + controls(actor, key, allowUndo = true))
            }
            ModerationAction.STATUS, ModerationAction.HISTORY, ModerationAction.ALTS -> error("Expected a moderation change")
        }
    }

    private suspend fun status(kind: SanctionKind, target: SanctionTarget, label: String, actor: CommandActor) {
        val address = when (target) {
            is SanctionTarget.Account -> players.find(target.id)?.address
            is SanctionTarget.Ip -> target.address
            is SanctionTarget.Range -> null
        }
        val active = storage.sanctions.matching(kind, target, address)
        val title = if (kind == SanctionKind.BAN) "moderation.status.bans" else "moderation.status.mutes"
        val permitted = permittedCommands(actor)
        val replies = buildList {
            add(Message(title, "target" to label))
            if (active.isEmpty()) add(Message("moderation.status.clear"))
            for (sanction in active) {
                add(Message("moderation.status.entry", "target" to sanction.key.target.value,
                    "reason" to reasonText(sanction.reason), "expiration" to expiration(sanction.expiresAt)))
                addAll(controls(permitted, sanction.key, allowUndo = true, showStatus = false))
            }
            if (active.isEmpty() || active.none { it.key.target == target }) {
                addAll(controls(permitted, SanctionKey(kind, target), allowUndo = false, showStatus = false))
            }
        }
        actor.reply(replies)
    }

    private suspend fun history(target: SanctionTarget, label: String, page: Int, actor: CommandActor) {
        val rows = storage.moderationHistory(target, HISTORY_SIZE + 1, (page - 1L) * HISTORY_SIZE)
        val replies = buildList {
            add(Message("moderation.history.heading", "target" to label, "page" to page))
            if (rows.isEmpty()) add(Message("moderation.history.empty"))
            for (row in rows.take(HISTORY_SIZE)) {
                add(Message("moderation.history.entry", "command" to row.command, "actor" to row.actor,
                    "time" to formatTimestamp(row.time), "target" to row.label))
                if (row.reason != null || row.expiresAt != null) {
                    add(Message("moderation.history.details", "reason" to reasonText(row.reason),
                        "expiration" to expiration(row.expiresAt)))
                }
            }
            val buttons = buildList {
                if (page > 1) add(button("previous", ModerationCommand.MODHISTORY, "${target.value} ${page - 1}"))
                if (rows.size > HISTORY_SIZE && page < Int.MAX_VALUE) {
                    add(button("next", ModerationCommand.MODHISTORY, "${target.value} ${page + 1}"))
                }
            }
            if (buttons.isNotEmpty()) add(actionRow(buttons))
        }
        actor.reply(replies)
    }

    private suspend fun alts(target: SanctionTarget, label: String, page: Int, actor: CommandActor) {
        val now = Instant.now()
        val permitted = permittedCommands(actor)
        val canSeeSeen = actor.onOwner { it.hasPermission("quickstart.seen") }
        val (heading, emptyKey, accounts) = when (target) {
            is SanctionTarget.Account -> {
                val liveIp = players.find(target.id)?.takeIf(PlayerSession::active)?.ipString
                val lookup = storage.findAltsForAccount(target.id, setOfNotNull(liveIp))
                val merged = mergeLiveAlts(target.id, lookup.checkedIps, lookup.accounts, now)
                Triple(
                    Message("moderation.alts.heading-account", "target" to label, "ips" to lookup.checkedIps.size, "page" to page),
                    if (lookup.checkedIps.isEmpty()) "moderation.alts.no-ips" else "moderation.alts.empty",
                    merged,
                )
            }
            is SanctionTarget.Ip -> {
                val stored = storage.accountsByIp(target.value)
                val merged = mergeLiveAlts(null, listOf(target.value), stored, now)
                Triple(
                    Message("moderation.alts.heading-ip", "target" to target.value, "page" to page),
                    "moderation.alts.empty",
                    merged,
                )
            }
            is SanctionTarget.Range -> throw CommandProblem("moderation.alts.invalid-target")
        }
        val chatPage = accounts.chatPage(page)
        val replies = buildList {
            add(heading)
            if (chatPage.entries.isEmpty()) {
                add(Message(emptyKey, "target" to label))
            } else {
                for (account in chatPage.entries) {
                    val online = players.find(account.id)?.takeIf(PlayerSession::active)
                    val address = online?.address ?: account.sharedIps.firstNotNullOfOrNull(::parseIp)
                    val banned = storage.sanctions.find(SanctionKind.BAN, account.id, address, now) != null
                    val muted = storage.sanctions.find(SanctionKind.MUTE, account.id, address, now) != null
                    val statusKey = when {
                        banned -> "moderation.alts.badge-banned"
                        muted && online != null -> "moderation.alts.badge-online-muted"
                        online != null -> "moderation.alts.badge-online"
                        muted -> "moderation.alts.badge-muted"
                        else -> "moderation.alts.badge-offline"
                    }
                    val age = if (online != null) {
                        Message("moderation.alts.active-now")
                    } else {
                        Message("moderation.alts.last-seen", "duration" to formatDuration(Duration.between(account.lastSeen, now)))
                    }
                    add(Message(
                        "moderation.alts.entry",
                        "player" to account.name,
                        "status" to Message(statusKey),
                        "seen" to age,
                        "ips" to account.sharedIps.joinToString(", "),
                    ))
                    val entryButtons = buildList {
                        if (canSeeSeen) {
                            val value = "seen ${account.id}"
                            add(Message("ui.control", "action" to ChatAction(
                                value,
                                Message("moderation.buttons.seen"),
                                Message("moderation.buttons.hover", "command" to value),
                            )))
                        }
                        if (ModerationCommand.BANSTATUS in permitted) add(button("status", ModerationCommand.BANSTATUS, account.id.toString()))
                        if (ModerationCommand.MODHISTORY in permitted) add(button("history", ModerationCommand.MODHISTORY, account.id.toString()))
                    }
                    if (entryButtons.isNotEmpty()) add(actionRow(entryButtons))
                }
                val nav = buildList {
                    if (chatPage.number > 1) add(button("previous", ModerationCommand.ALTS, "${target.value} ${chatPage.number - 1}"))
                    if (chatPage.number < chatPage.total) add(button("next", ModerationCommand.ALTS, "${target.value} ${chatPage.number + 1}"))
                }
                if (nav.isNotEmpty()) add(actionRow(nav))
            }
        }
        actor.reply(replies)
    }

    private fun mergeLiveAlts(
        excludeId: UUID?,
        checkedIps: List<String>,
        stored: List<AltAccount>,
        now: Instant,
    ): List<AltAccount> {
        if (checkedIps.isEmpty()) return stored
        val ipSet = checkedIps.toSet()
        val byId = stored.associateByTo(linkedMapOf(), AltAccount::id)
        for (session in players.onlineSessions()) {
            val ip = session.ipString ?: continue
            if (session.id == excludeId || ip !in ipSet) continue
            val existing = byId[session.id]
            if (existing == null) {
                byId[session.id] = AltAccount(session.id, session.name, now, listOf(ip))
            } else {
                val ips = if (ip in existing.sharedIps) existing.sharedIps else existing.sharedIps + ip
                byId[session.id] = existing.copy(name = session.name, lastSeen = now, sharedIps = ips)
            }
        }
        return byId.values.sortedByDescending(AltAccount::lastSeen)
    }

    private suspend fun permittedCommands(actor: CommandActor): Set<ModerationCommand> = actor.onOwner { sender ->
        ModerationCommand.entries.filterTo(mutableSetOf()) { sender.hasPermission(it.permission) }
    }

    private suspend fun controls(actor: CommandActor, key: SanctionKey, allowUndo: Boolean): List<Message> =
        controls(permittedCommands(actor), key, allowUndo)

    private fun controls(permitted: Set<ModerationCommand>, key: SanctionKey, allowUndo: Boolean, showStatus: Boolean = true): List<Message> {
        val status = if (key.kind == SanctionKind.BAN) ModerationCommand.BANSTATUS else ModerationCommand.MUTESTATUS
        val undo = reversal(key)
        val value = key.target.value
        val buttons = buildList {
            if (showStatus && status in permitted) add(button("status", status, value))
            if (ModerationCommand.MODHISTORY in permitted) add(button("history", ModerationCommand.MODHISTORY, value))
            if (key.target !is SanctionTarget.Range && ModerationCommand.ALTS in permitted) add(button("alts", ModerationCommand.ALTS, value))
            if (allowUndo && undo != null && undo in permitted) {
                val label = if (key.kind == SanctionKind.BAN) "unban" else "unmute"
                add(button(label, undo, value, suggest = true))
            }
        }
        return if (buttons.isEmpty()) emptyList() else listOf(actionRow(buttons))
    }

    private fun reversal(key: SanctionKey) = ModerationCommand.entries.firstOrNull {
        it.action == ModerationAction.REMOVE && it.kind == key.kind && it.target == key.target.kind
    }

    private fun actionRow(buttons: List<Message>) =
        Message("ui.action-row", "actions" to MessageGroup(buttons, Component.text("  ")))

    private fun button(label: String, command: ModerationCommand, args: String, suggest: Boolean = false): Message {
        val value = "${command.id} $args"
        return Message("ui.control", "action" to ChatAction(value, Message("moderation.buttons.$label"),
            Message("moderation.buttons.hover", "command" to value), suggest))
    }

    private fun disconnectMatching(target: SanctionTarget) {
        for (session in players.onlineSessions().filter { target.matches(it.id, it.address) }) {
            scope.launch {
                try {
                    scheduler.entity(session.player) {
                        if (session.active) kickIfBanned(session)
                    }
                } catch (_: EntityUnavailable) {
                }
            }
        }
    }

    private fun notice(sanction: Sanction) = Message(
        if (sanction.key.kind == SanctionKind.BAN) "moderation.banned" else "moderation.muted",
        "reason" to reasonText(sanction.reason),
        "expiration" to expiration(sanction.expiresAt),
    )

    private fun expiration(at: Instant?, now: Instant = Instant.now()): Any =
        at?.let { formatExpiration(it, now) } ?: Message("moderation.never")

    private fun reasonText(value: String?): Any = if (value == null) Message("moderation.no-reason") else reasons.deserialize(value)

    private suspend fun requirePermission(actor: CommandActor, command: ModerationCommand) {
        if (!actor.onOwner { it.hasPermission(command.permission) }) throw CommandProblem("common.no-permission")
    }

    companion object {
        private const val HISTORY_SIZE = 5
        private val reasons = MiniMessage.builder().tags(TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(),
            StandardTags.reset(), StandardTags.newline(),
        )).build()
        private val mutedCommands = setOf("me", "msg", "tell", "w", "teammsg", "tm", "message", "m", "whisper", "pm", "reply", "r", "say", "tellraw")

        fun isChatCommand(message: String) = message.substringAfter('/').substringBefore(' ').substringAfterLast(':')
            .lowercase(Locale.ROOT) in mutedCommands

        private fun parseId(value: String): UUID? = try {
            UUID.fromString(value).takeIf { it.toString().equals(value, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
