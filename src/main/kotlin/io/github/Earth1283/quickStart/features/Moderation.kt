package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.AltAccount
import io.github.Earth1283.quickStart.core.ChatAction
import io.github.Earth1283.quickStart.core.CommandActor
import io.github.Earth1283.quickStart.core.CommandProblem
import io.github.Earth1283.quickStart.core.IpNetwork
import io.github.Earth1283.quickStart.core.Message
import io.github.Earth1283.quickStart.core.MessageGroup
import io.github.Earth1283.quickStart.core.ModerationRecord
import io.github.Earth1283.quickStart.core.ModerationSettings
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
import io.github.Earth1283.quickStart.core.pageSuggestions
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
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
    private val settings: () -> ModerationSettings = { ModerationSettings() },
    private val logAudit: (String) -> Unit = {},
) : RuntimeModule {
    override suspend fun prepare() = storage.loadSanctions()

    suspend fun execute(command: ModerationCommand, args: Array<out String>, actor: CommandActor) {
        requirePermission(actor, command)
        val config = settings()
        val input = command.parse(args, requiredReasonCommands = config.requireReasonFor)
        when (command.action) {
            ModerationAction.LIST -> return sanctionList(command.kind, input.page, actor)
            ModerationAction.HISTORY -> {
                if (input.actorFilter != null) return actorHistory(input.actorFilter, input.page, actor)
                if (input.target.isEmpty()) return recentHistory(input.page, actor)
            }
            else -> Unit
        }
        val target = resolveTarget(command, input.target)
        val label = if (target is SanctionTarget.Range) target.value else input.target
        when (command.action) {
            ModerationAction.STATUS -> return status(command.kind, target, label, actor)
            ModerationAction.HISTORY -> return history(target, label, input.page, actor)
            ModerationAction.ALTS -> return alts(target, label, input.page, actor)
            else -> Unit
        }
        if (input.reason == "<usedialogue>") {
            editModerationReason(
                actor,
                command,
                label,
                input.silent,
                config.maxReasonLength,
                config.reasonPresets,
            ) { reason, silent ->
                submitDialog(actor) {
                    apply(command, target, label, input.expiry, reason.trim().takeIf(String::isNotEmpty), silent, actor)
                }
            }
        } else {
            apply(command, target, label, input.expiry, input.reason, input.silent, actor)
        }
    }

    suspend fun openMenu(input: String, actor: CommandActor) {
        val allowed = permittedCommands(actor)
        if (allowed.isEmpty()) throw CommandProblem("common.no-permission")
        val target = resolveTarget(ModerationCommand.BANSTATUS, input)
        val online = (target as? SanctionTarget.Account)?.let { players.find(it.id)?.takeIf(PlayerSession::active) }
        val resolvedIp = when (target) {
            is SanctionTarget.Ip -> target.address
            is SanctionTarget.Account -> online?.address ?: storage.playerProfile(target.id)?.lastIp?.let(::parseIp)
            is SanctionTarget.Range -> null
        }
        val available = buildMap {
            for (command in allowed) {
                if (command.action == ModerationAction.LIST) continue
                val resolved = when {
                    command == ModerationCommand.KICK -> target.takeIf { online != null }
                    command == ModerationCommand.ALTS -> target.takeIf { it !is SanctionTarget.Range }
                    command.isInspection || command.target == target.kind -> target
                    command.target == TargetKind.IP -> resolvedIp?.let(SanctionTarget::Ip)
                    else -> null
                }
                if (resolved != null) put(command, resolved)
            }
        }
        if (available.isEmpty()) throw CommandProblem("moderation.dialog.no-actions")
        val summaryLines = buildTargetSummary(target, input, online, resolvedIp)
        val config = settings()
        showModerationMenu(actor, input, summaryLines, available.keys, config.maxReasonLength, config.reasonPresets) { response ->
            submitDialog(actor) {
                val command = response.action?.let(ModerationCommand.byId::get)
                    ?: throw CommandProblem("moderation.dialog.action-required")
                val resolved = available[command] ?: throw CommandProblem("moderation.dialog.action-required")
                requirePermission(actor, command)
                when (command.action) {
                    ModerationAction.STATUS -> status(command.kind, resolved, input, actor)
                    ModerationAction.HISTORY -> history(resolved, input, 1, actor)
                    ModerationAction.ALTS -> alts(resolved, input, 1, actor)
                    ModerationAction.LIST -> sanctionList(command.kind, 1, actor)
                    else -> {
                        val expiry = if (command.action == ModerationAction.TEMPORARY) {
                            val value = response.expiry?.trim()?.replace(Regex("\\s+"), "T").orEmpty()
                            ModerationExpiry.parse(value, Instant.now())
                        } else null
                        apply(
                            command,
                            resolved,
                            input,
                            expiry,
                            response.reason?.trim()?.takeIf(String::isNotEmpty),
                            response.silent,
                            actor,
                        )
                    }
                }
            }
        }
    }

    private suspend fun buildTargetSummary(
        target: SanctionTarget,
        label: String,
        online: PlayerSession?,
        resolvedIp: InetAddress?,
    ): List<Component> {
        val now = Instant.now()
        val bans = storage.sanctions.matching(SanctionKind.BAN, target, resolvedIp, now)
        val mutes = if (target is SanctionTarget.Range) emptyList() else storage.sanctions.matching(SanctionKind.MUTE, target, resolvedIp, now)
        val statusText = when {
            online != null -> render(Message("moderation.dialog.summary-online", "target" to label))
            target is SanctionTarget.Account -> render(Message("moderation.dialog.summary-offline", "target" to label))
            else -> render(Message("moderation.dialog.summary-target", "target" to label))
        }
        val sanctionsText = render(Message(
            "moderation.dialog.summary-sanctions",
            "bans" to bans.size,
            "mutes" to mutes.size,
        ))
        return buildList {
            add(statusText)
            add(sanctionsText)
            if (target is SanctionTarget.Account) {
                val altCount = runCatching {
                    storage.findAltsForAccount(target.id, setOfNotNull(online?.ipString)).accounts.size
                }.getOrNull()
                if (altCount != null && altCount > 0) {
                    add(render(Message("moderation.dialog.summary-alts", "count" to altCount)))
                }
            }
        }
    }

    fun complete(command: ModerationCommand, args: Array<out String>): List<String> {
        if (command.action == ModerationAction.LIST) {
            return if (args.size == 1) pageSuggestions(storage.sanctions.allActive(command.kind).size, args[0]) else emptyList()
        }
        if (args.size != 1) return command.completeExpiry(args)
        val prefix = args[0]
        if (command.action == ModerationAction.REMOVE) {
            val active = storage.sanctions.activeTargets(command.kind, command.target)
            val candidates = active.flatMap { target ->
                when (target) {
                    is SanctionTarget.Account -> {
                        val name = resolveAccountName(target.id)
                        if (name != null) {
                            if ('-' in prefix) listOf(name, target.value) else listOf(name)
                        } else listOf(target.value)
                    }
                    else -> listOf(target.value)
                }
            }
            return matchingSuggestions(candidates, prefix)
        }
        val names = if (command.target == TargetKind.RANGE) emptyList() else {
            val online = players.onlineSessions().map(PlayerSession::name)
            if (command == ModerationCommand.KICK) online
            else online + storage.cachedPlayerNames()
        }
        val targets = when {
            command.action == ModerationAction.HISTORY || command.action == ModerationAction.ALTS ->
                SanctionKind.entries.flatMap { activeTargetLabels(it, prefix) }
            command.isInspection -> activeTargetLabels(command.kind, prefix)
            else -> emptyList()
        }
        val actorHints = if (command.action == ModerationAction.HISTORY) {
            val staff = (players.onlineSessions().map(PlayerSession::name) + storage.cachedPlayerNames() + "CONSOLE")
                .map { "actor:$it" }
            listOf("actor:") + staff
        } else emptyList()
        return matchingSuggestions(names + targets + actorHints, prefix)
    }

    private fun activeTargetLabels(kind: SanctionKind, prefix: String): List<String> =
        storage.sanctions.activeTargets(kind, null).flatMap { target ->
            when (target) {
                is SanctionTarget.Account -> {
                    val name = resolveAccountName(target.id)
                    if (name != null) {
                        if ('-' in prefix) listOf(name, target.value) else listOf(name)
                    } else listOf(target.value)
                }
                else -> listOf(target.value)
            }
        }

    fun completeMenu(sender: CommandSender, prefix: String): List<String> = matchingSuggestions(
        ModerationCommand.entries
            .filter { it.action != ModerationAction.LIST && sender.hasPermission(it.permission) }
            .flatMap { complete(it, arrayOf(prefix)) },
        prefix,
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

    fun isMutedCommand(message: String): Boolean = isChatCommand(message, settings().mutedCommands)

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
            TargetKind.IP -> {
                val resolved = parseIp(input)
                    ?: players.find(input)?.address
                    ?: parseId(input)?.let { storage.playerProfile(it)?.lastIp }?.let(::parseIp)
                    ?: storage.findPlayerProfileByName(input)?.lastIp?.let(::parseIp)
                    ?: throw CommandProblem("moderation.invalid-ip")
                SanctionTarget.Ip(resolved)
            }
            TargetKind.RANGE -> SanctionTarget.Range(IpNetwork.parse(input) ?: throw CommandProblem("moderation.invalid-range"))
        }
    }

    private suspend fun apply(
        command: ModerationCommand,
        target: SanctionTarget,
        label: String,
        expiry: ModerationExpiry?,
        reason: String?,
        silent: Boolean,
        actor: CommandActor,
    ) {
        requirePermission(actor, command)
        val config = settings()
        command.requireReason(reason, config.requireReasonFor)
        if (reason != null && reason.length > config.maxReasonLength) {
            throw CommandProblem("moderation.reason-length", "limit" to config.maxReasonLength)
        }
        val key = SanctionKey(command.kind, target)
        if (command.action != ModerationAction.REMOVE) {
            enforceGuardrails(command, target, label, actor, config)
        }
        when (command.action) {
            ModerationAction.KICK -> {
                val session = players.find((target as SanctionTarget.Account).id) ?: throw CommandProblem("moderation.offline-player")
                val now = Instant.now()
                try {
                    scope.async {
                        scheduler.entity(session.player) {
                            session.requireActive()
                            session.player.kick(render(Message("moderation.kicked", "reason" to reasonText(reason))), PlayerKickEvent.Cause.KICK_COMMAND)
                        }
                        storage.logModeration(ModerationRecord(command.id, target, label, actor.name, now, reason))
                    }.await()
                } catch (_: EntityUnavailable) {
                    throw CommandProblem("moderation.offline-player")
                }
                val reply = Message(
                    if (silent) "moderation.kick-confirmed-silent" else "moderation.kick-confirmed",
                    "target" to label,
                )
                actor.reply(listOf(reply) + controls(actor, key, allowUndo = false))
                notifyStaffAndAudit(command, label, actor, reason, expiresAt = null, silent = silent, config = config)
            }
            ModerationAction.REMOVE -> {
                val removed = storage.deleteSanction(key, ModerationRecord(command.id, target, label, actor.name, Instant.now(), reason))
                val replyKey = when {
                    !removed -> "moderation.no-match"
                    silent -> "moderation.removed-silent"
                    else -> "moderation.removed"
                }
                val reply = Message(replyKey, "command" to command.id, "target" to label)
                actor.reply(listOf(reply) + controls(actor, key, allowUndo = false))
                if (removed) {
                    notifyStaffAndAudit(command, label, actor, reason, expiresAt = null, silent = silent, config = config)
                }
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
                val reply = Message(
                    if (silent) "moderation.applied-silent" else "moderation.applied",
                    "command" to command.id,
                    "target" to label,
                    "expiration" to expiration(expiresAt),
                )
                actor.reply(listOf(reply) + controls(actor, key, allowUndo = true))
                notifyStaffAndAudit(command, label, actor, reason, expiresAt = expiresAt, silent = silent, config = config)
            }
            ModerationAction.STATUS, ModerationAction.HISTORY, ModerationAction.ALTS, ModerationAction.LIST ->
                error("Expected a moderation change")
        }
    }

    private suspend fun enforceGuardrails(
        command: ModerationCommand,
        target: SanctionTarget,
        label: String,
        actor: CommandActor,
        config: ModerationSettings,
    ) {
        val actorSession = actor.session
        if (actorSession != null) {
            if (target is SanctionTarget.Account && target.id == actorSession.id) {
                throw CommandProblem("moderation.cannot-target-self")
            }
            if (target !is SanctionTarget.Account && target.matches(actorSession.id, actorSession.address)) {
                throw CommandProblem("moderation.cannot-target-self-ip")
            }
        }
        if (target is SanctionTarget.Range) {
            val minPrefix = if (target.network.isIpv6) config.minIpv6CidrPrefix else config.minIpv4CidrPrefix
            if (target.network.prefix < minPrefix) {
                throw CommandProblem(
                    "moderation.cidr-too-broad",
                    "network" to target.network.toString(),
                    "min" to minPrefix,
                )
            }
        }
        if (actorSession != null) {
            val canBypassExempt = actor.onOwner { it.hasPermission(EXEMPT_BYPASS_PERMISSION) }
            if (!canBypassExempt) {
                val matchingOnline = players.onlineSessions().filter {
                    it.id != actorSession.id && target.matches(it.id, it.address)
                }
                for (session in matchingOnline) {
                    val isExempt = try {
                        scheduler.entity(session.player) {
                            session.active && session.player.hasPermission(EXEMPT_PERMISSION)
                        }
                    } catch (_: EntityUnavailable) {
                        false
                    }
                    if (isExempt) {
                        throw CommandProblem("moderation.target-exempt", "target" to (if (target is SanctionTarget.Account) label else session.name))
                    }
                }
            }
        }
    }

    private fun notifyStaffAndAudit(
        command: ModerationCommand,
        targetLabel: String,
        actor: CommandActor,
        reason: String?,
        expiresAt: Instant?,
        silent: Boolean,
        config: ModerationSettings,
    ) {
        if (config.logToConsole) {
            val parts = buildList {
                add("[Moderation] ${actor.name} ran /${command.id} on $targetLabel")
                if (expiresAt != null) add("expires ${formatExpiration(expiresAt)}")
                if (reason != null) add("reason: ${plainText(reasonText(reason))}")
                if (silent) add("(silent)")
            }
            logAudit(parts.joinToString(" | "))
        }
        if (silent || !config.broadcastToStaff) return
        val alertKey = when (command.action) {
            ModerationAction.REMOVE -> "moderation.notify.removed"
            ModerationAction.KICK -> "moderation.notify.kicked"
            else -> "moderation.notify.applied"
        }
        val notice = Message(
            alertKey,
            "actor" to actor.name,
            "command" to command.id,
            "target" to targetLabel,
            "reason" to reasonText(reason),
            "expiration" to expiration(expiresAt),
        )
        val rendered = render(notice)
        val excludeId = actor.session?.id
        for (session in players.onlineSessions()) {
            if (session.id == excludeId) continue
            scope.launch {
                try {
                    scheduler.entity(session.player) {
                        if (session.active && session.player.hasPermission(NOTIFY_PERMISSION)) {
                            session.player.sendMessage(rendered)
                        }
                    }
                } catch (_: EntityUnavailable) {
                }
            }
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
                add(Message("moderation.status.entry", "target" to displayTarget(sanction.key.target),
                    "reason" to reasonText(sanction.reason), "expiration" to expiration(sanction.expiresAt)))
                addAll(controls(permitted, sanction.key, allowUndo = true, showStatus = false))
            }
            if (active.isEmpty() || active.none { it.key.target == target }) {
                addAll(controls(permitted, SanctionKey(kind, target), allowUndo = false, showStatus = false))
            }
        }
        actor.reply(replies)
    }

    private suspend fun sanctionList(kind: SanctionKind, page: Int, actor: CommandActor) {
        val active = storage.sanctions.allActive(kind)
        val chatPage = active.chatPage(page)
        val permitted = permittedCommands(actor)
        val command = if (kind == SanctionKind.BAN) ModerationCommand.BANLIST else ModerationCommand.MUTELIST
        val headingKey = if (kind == SanctionKind.BAN) "moderation.list.bans-heading" else "moderation.list.mutes-heading"
        val emptyKey = if (kind == SanctionKind.BAN) "moderation.list.bans-empty" else "moderation.list.mutes-empty"
        val replies = buildList {
            add(Message(headingKey, "count" to active.size, "page" to chatPage.number, "pages" to maxOf(1, chatPage.total)))
            if (chatPage.entries.isEmpty()) {
                add(Message(emptyKey))
            } else {
                for (sanction in chatPage.entries) {
                    add(Message(
                        "moderation.status.entry",
                        "target" to displayTarget(sanction.key.target),
                        "reason" to reasonText(sanction.reason),
                        "expiration" to expiration(sanction.expiresAt),
                    ))
                    addAll(controls(permitted, sanction.key, allowUndo = true, showStatus = true))
                }
                val nav = buildList {
                    if (chatPage.number > 1) add(button("previous", command, "${chatPage.number - 1}"))
                    if (chatPage.number < chatPage.total) add(button("next", command, "${chatPage.number + 1}"))
                }
                if (nav.isNotEmpty()) add(actionRow(nav))
            }
        }
        actor.reply(replies)
    }

    private suspend fun history(target: SanctionTarget, label: String, page: Int, actor: CommandActor) {
        val rows = storage.moderationHistory(target, HISTORY_SIZE + 1, (page - 1L) * HISTORY_SIZE)
        replyHistory(
            Message("moderation.history.heading", "target" to label, "page" to page),
            rows,
            page,
            navPrefix = "${target.value} ",
            actor = actor,
        )
    }

    private suspend fun recentHistory(page: Int, actor: CommandActor) {
        val rows = storage.recentModerationHistory(HISTORY_SIZE + 1, (page - 1L) * HISTORY_SIZE)
        replyHistory(
            Message("moderation.history.heading-recent", "page" to page),
            rows,
            page,
            navPrefix = "",
            actor = actor,
        )
    }

    private suspend fun actorHistory(staffName: String, page: Int, actor: CommandActor) {
        val rows = storage.moderationHistoryByActor(staffName, HISTORY_SIZE + 1, (page - 1L) * HISTORY_SIZE)
        replyHistory(
            Message("moderation.history.heading-actor", "actor" to staffName, "page" to page),
            rows,
            page,
            navPrefix = "actor:$staffName ",
            actor = actor,
        )
    }

    private suspend fun replyHistory(
        heading: Message,
        rows: List<ModerationRecord>,
        page: Int,
        navPrefix: String,
        actor: CommandActor,
    ) {
        val replies = buildList {
            add(heading)
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
                if (page > 1) add(button("previous", ModerationCommand.MODHISTORY, "$navPrefix${page - 1}"))
                if (rows.size > HISTORY_SIZE && page < Int.MAX_VALUE) {
                    add(button("next", ModerationCommand.MODHISTORY, "$navPrefix${page + 1}"))
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
        val canOpenMenu = permitted.any { it.action != ModerationAction.LIST }
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
                        if (canOpenMenu) add(moderateButton(account.name))
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

    private fun resolveAccountName(id: UUID): String? =
        players.find(id)?.name ?: storage.knownPlayerName(id)

    private fun displayTarget(target: SanctionTarget): String = when (target) {
        is SanctionTarget.Account -> {
            val name = resolveAccountName(target.id)
            if (name != null) "$name (${target.value})" else target.value
        }
        else -> target.value
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
            if (permitted.any { it.action != ModerationAction.LIST }) {
                val menuTarget = if (key.target is SanctionTarget.Account) resolveAccountName(key.target.id) ?: value else value
                add(moderateButton(menuTarget))
            }
            if (allowUndo && undo != null && undo in permitted) {
                val label = if (key.kind == SanctionKind.BAN) "unban" else "unmute"
                add(button(label, undo, value, suggest = true))
            }
        }
        return if (buttons.isEmpty()) emptyList() else listOf(actionRow(buttons))
    }

    private fun moderateButton(target: String): Message {
        val value = "qs mod $target"
        return Message("ui.control", "action" to ChatAction(
            value,
            Message("moderation.buttons.moderate"),
            Message("moderation.buttons.hover", "command" to value),
        ))
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

    private fun plainText(value: Any): String = when (value) {
        is Component -> PlainTextComponentSerializer.plainText().serialize(value)
        is Message -> PlainTextComponentSerializer.plainText().serialize(render(value))
        else -> value.toString()
    }

    private suspend fun requirePermission(actor: CommandActor, command: ModerationCommand) {
        if (!actor.onOwner { it.hasPermission(command.permission) }) throw CommandProblem("common.no-permission")
    }

    companion object {
        private const val HISTORY_SIZE = 5
        const val NOTIFY_PERMISSION = "quickstart.moderation.notify"
        const val EXEMPT_PERMISSION = "quickstart.moderation.exempt"
        const val EXEMPT_BYPASS_PERMISSION = "quickstart.moderation.exempt.bypass"
        private val reasons = MiniMessage.builder().tags(TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(),
            StandardTags.reset(), StandardTags.newline(),
        )).build()

        fun isChatCommand(
            message: String,
            mutedCommands: Set<String> = ModerationSettings.DEFAULT_MUTED_COMMANDS,
        ) = message.substringAfter('/').substringBefore(' ').substringAfterLast(':')
            .lowercase(Locale.ROOT) in mutedCommands

        private fun parseId(value: String): UUID? = try {
            UUID.fromString(value).takeIf { it.toString().equals(value, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
