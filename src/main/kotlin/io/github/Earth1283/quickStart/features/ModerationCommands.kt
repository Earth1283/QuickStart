package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.CommandProblem
import io.github.Earth1283.quickStart.core.SanctionKind
import io.github.Earth1283.quickStart.core.TargetKind
import io.github.Earth1283.quickStart.core.matchingSuggestions
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal enum class ModerationAction { APPLY, TEMPORARY, REMOVE, KICK, STATUS, HISTORY, ALTS }

internal enum class ModerationCommand(
    val id: String,
    val kind: SanctionKind,
    val target: TargetKind,
    val action: ModerationAction = ModerationAction.APPLY,
) {
    BAN("ban", SanctionKind.BAN, TargetKind.ACCOUNT),
    TEMPBAN("tempban", SanctionKind.BAN, TargetKind.ACCOUNT, ModerationAction.TEMPORARY),
    UNBAN("unban", SanctionKind.BAN, TargetKind.ACCOUNT, ModerationAction.REMOVE),
    IPBAN("ipban", SanctionKind.BAN, TargetKind.IP),
    TEMPIPBAN("tempipban", SanctionKind.BAN, TargetKind.IP, ModerationAction.TEMPORARY),
    UNBANIP("unbanip", SanctionKind.BAN, TargetKind.IP, ModerationAction.REMOVE),
    IPRANGEBAN("iprangeban", SanctionKind.BAN, TargetKind.RANGE),
    TEMPIPRANGEBAN("tempiprangeban", SanctionKind.BAN, TargetKind.RANGE, ModerationAction.TEMPORARY),
    UNBANIPRANGE("unbaniprange", SanctionKind.BAN, TargetKind.RANGE, ModerationAction.REMOVE),
    MUTE("mute", SanctionKind.MUTE, TargetKind.ACCOUNT),
    TEMPMUTE("tempmute", SanctionKind.MUTE, TargetKind.ACCOUNT, ModerationAction.TEMPORARY),
    UNMUTE("unmute", SanctionKind.MUTE, TargetKind.ACCOUNT, ModerationAction.REMOVE),
    IPMUTE("ipmute", SanctionKind.MUTE, TargetKind.IP),
    TEMPIPMUTE("tempipmute", SanctionKind.MUTE, TargetKind.IP, ModerationAction.TEMPORARY),
    UNMUTEIP("unmuteip", SanctionKind.MUTE, TargetKind.IP, ModerationAction.REMOVE),
    KICK("kick", SanctionKind.BAN, TargetKind.ACCOUNT, ModerationAction.KICK),
    BANSTATUS("banstatus", SanctionKind.BAN, TargetKind.ACCOUNT, ModerationAction.STATUS),
    MUTESTATUS("mutestatus", SanctionKind.MUTE, TargetKind.ACCOUNT, ModerationAction.STATUS),
    MODHISTORY("modhistory", SanctionKind.BAN, TargetKind.ACCOUNT, ModerationAction.HISTORY),
    ALTS("alts", SanctionKind.BAN, TargetKind.ACCOUNT, ModerationAction.ALTS);

    val isInspection: Boolean get() = action == ModerationAction.STATUS || action == ModerationAction.HISTORY || action == ModerationAction.ALTS

    val permission: String get() = "quickstart.moderation.$id"

    val usage: String
        get() {
            if (this == TEMPBAN) return "/$id <player> <duration> <reason>"
            val subject = when {
                this == ALTS -> "<player|uuid|ip>"
                isInspection -> "<player|uuid|ip|cidr>"
                else -> when (target) {
                    TargetKind.ACCOUNT -> if (this == KICK) "<player>" else "<player|uuid>"
                    TargetKind.IP -> "<ip|online player>"
                    TargetKind.RANGE -> "<cidr>"
                }
            }
            val arguments = when (action) {
                ModerationAction.REMOVE, ModerationAction.STATUS -> ""
                ModerationAction.HISTORY, ModerationAction.ALTS -> " [page]"
                ModerationAction.TEMPORARY -> " <duration|date time> [reason]"
                else -> " [reason]"
            }
            return "/$id $subject$arguments"
        }

    fun parse(args: Array<out String>, now: Instant = Instant.now()): ModerationArguments {
        val subject = args.firstOrNull()?.takeIf(String::isNotBlank) ?: throw usageProblem()
        if (action == ModerationAction.HISTORY || action == ModerationAction.ALTS) {
            val page = if (args.size == 1) 1 else args.getOrNull(1)?.toIntOrNull()?.takeIf { args.size == 2 && it > 0 }
                ?: throw usageProblem()
            return ModerationArguments(subject, page = page)
        }
        if (action == ModerationAction.REMOVE || action == ModerationAction.STATUS) {
            if (args.size != 1) throw usageProblem()
            return ModerationArguments(subject)
        }
        val expiry = if (action == ModerationAction.TEMPORARY) {
            val first = args.getOrNull(1) ?: throw usageProblem()
            val parts = if (ModerationExpiry.isDate(first)) {
                listOf(first, args.getOrNull(2) ?: throw CommandProblem("moderation.invalid-expiry"))
            } else listOf(first)
            ModerationExpiry.parse(parts.joinToString("T"), now) to parts.size
        } else null
        val reason = args.drop(1 + (expiry?.second ?: 0)).joinToString(" ").trim().takeIf(String::isNotEmpty)
        requireReason(reason)
        return ModerationArguments(subject, expiry?.first, reason)
    }

    fun requireReason(reason: String?) {
        if (this == TEMPBAN && reason.isNullOrBlank()) throw usageProblem()
    }

    private fun usageProblem() = CommandProblem("moderation.usage", "usage" to usage)

    fun completeExpiry(args: Array<out String>, now: Instant = Instant.now()): List<String> {
        if (action != ModerationAction.TEMPORARY) return emptyList()
        return when (args.size) {
            2 -> ModerationExpiry.suggestions(args[1], now)
            3 -> ModerationExpiry.times(args[1], args[2], now)
            else -> emptyList()
        }
    }

    companion object {
        val byId = entries.associateBy(ModerationCommand::id)
    }
}

internal data class ModerationArguments(
    val target: String,
    val expiry: ModerationExpiry? = null,
    val reason: String? = null,
    val page: Int = 1,
)

internal sealed interface ModerationExpiry {
    fun resolve(now: Instant = Instant.now()): Instant

    data class Relative(val seconds: Long) : ModerationExpiry {
        override fun resolve(now: Instant): Instant = try {
            now.plusSeconds(seconds).also(Instant::toEpochMilli)
        } catch (_: ArithmeticException) {
            throw invalid()
        } catch (_: DateTimeException) {
            throw invalid()
        }
    }

    data class Absolute(val deadline: Instant) : ModerationExpiry {
        override fun resolve(now: Instant): Instant = deadline.takeIf { it.isAfter(now) } ?: throw invalid()
    }

    companion object {
        private val dateFormat = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private val dateTimeFormat = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2})?Z?")
        private val durationFormat = Regex("(?:[0-9]+[smhdw])+", RegexOption.IGNORE_CASE)
        private val durationPart = Regex("([0-9]+)([smhdw])", RegexOption.IGNORE_CASE)
        private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
        private val durations = listOf("15m", "30m", "1h", "6h", "12h", "1d", "3d", "1w")

        fun isDate(value: String) = dateFormat.matches(value)

        fun parse(value: String, now: Instant): ModerationExpiry = try {
            val expiry = when {
                durationFormat.matches(value) -> {
                    val seconds = durationPart.findAll(value).fold(0L) { total, part ->
                        val unit = when (part.groupValues[2].lowercase()) {
                            "s" -> 1L
                            "m" -> 60L
                            "h" -> 3600L
                            "d" -> 86400L
                            else -> 604800L
                        }
                        Math.addExact(total, Math.multiplyExact(part.groupValues[1].toLong(), unit))
                    }
                    if (seconds == 0L) throw invalid()
                    Relative(seconds)
                }
                dateTimeFormat.matches(value) -> Absolute(LocalDateTime.parse(value.removeSuffix("Z")).toInstant(ZoneOffset.UTC))
                else -> throw invalid()
            }
            expiry.also { it.resolve(now) }
        } catch (_: DateTimeException) {
            throw invalid()
        } catch (_: ArithmeticException) {
            throw invalid()
        } catch (_: NumberFormatException) {
            throw invalid()
        }

        fun suggestions(prefix: String, now: Instant): List<String> {
            val parts = prefix.split('T', limit = 2)
            if (parts.size == 2) return times(parts[0], parts[1], now).map { "${parts[0]}T$it" }
            val dates = if (prefix.isEmpty()) {
                val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
                (0L..6L).map { today.plusDays(it).toString() }
            } else {
                val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
                (0L..365L).asSequence().map { today.plusDays(it).toString() }.filter { it.startsWith(prefix) }.take(14).toList()
            }
            val typed = prefix.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.let { number -> listOf("s", "m", "h", "d", "w").map { number + it } }.orEmpty()
            return matchingSuggestions(durations + typed + dates, prefix)
        }

        fun times(date: String, prefix: String, now: Instant): List<String> {
            if (!isDate(date)) return emptyList()
            val day = try { LocalDate.parse(date) } catch (_: DateTimeException) { return emptyList() }
            val step = if (':' in prefix) 1L else 60L
            val times = (0L until 24 * 60L step step).asSequence().map { LocalTime.MIDNIGHT.plusMinutes(it) }
                .filter { day.atTime(it).toInstant(ZoneOffset.UTC).isAfter(now) }.map { it.format(timeFormat) }
                .filter { it.startsWith(prefix) }.take(30).toList()
            return times
        }

        private fun invalid() = CommandProblem("moderation.invalid-expiry")
    }
}
