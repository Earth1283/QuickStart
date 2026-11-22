package io.github.Earth1283.quickStart.core

import java.util.Locale
import net.kyori.adventure.text.Component

private const val PAGE_SIZE = 5
private const val COMPLETION_LIMIT = 50

internal fun pageCount(size: Int): Int = ((size.toLong() + PAGE_SIZE - 1) / PAGE_SIZE).toInt()

internal data class ChatPage<T>(val entries: List<T>, val number: Int, val total: Int)

internal fun <T> List<T>.chatPage(requested: Int): ChatPage<T> {
    val total = pageCount(size)
    if (total == 0) return ChatPage(emptyList(), 1, 0)
    val number = requested.coerceIn(1, total)
    val start = (number - 1) * PAGE_SIZE
    return ChatPage(subList(start, start + minOf(PAGE_SIZE, size - start)), number, total)
}

internal fun matchingSuggestions(values: Iterable<String>, prefix: String): List<String> = values.asSequence()
    .filter { it.startsWith(prefix, ignoreCase = true) }
    .distinctBy { it.lowercase(Locale.ROOT) }
    .sortedWith(String.CASE_INSENSITIVE_ORDER)
    .take(COMPLETION_LIMIT)
    .toList()

internal fun pageSuggestions(size: Int, prefix: String): List<String> = (1..pageCount(size)).asSequence()
    .map(Int::toString)
    .filter { it.startsWith(prefix) }
    .take(COMPLETION_LIMIT)
    .toList()

internal fun ChatPage<*>.navigation(command: FeatureCommand): Message {
    fun pageAction(page: Int, direction: String): Message = Message(
        "ui.control",
        "action" to ChatAction(
            "${command.id} $page",
            Message("ui.$direction"),
            Message("ui.go-to-page", "page" to page),
        ),
    )

    val actions = buildList {
        if (number > 1) add(pageAction(number - 1, "previous"))
        if (number < total) add(pageAction(number + 1, "next"))
    }
    return Message("ui.page", "page" to number, "pages" to total, "controls" to MessageGroup(actions, Component.space()))
}

internal suspend fun replyWithNames(
    call: FeatureCall,
    names: List<String>,
    save: FeatureCommand,
    delete: FeatureCommand,
) {
    val actor = call.actor
    val section = call.command.module
    val player = actor.session != null
    val (canSave, canDelete) = if (player) actor.onOwner {
        it.hasPermission(save.permission) to it.hasPermission(delete.permission)
    } else false to false
    if (names.isEmpty() && !canSave) return actor.reply("$section.empty")
    val page = names.chatPage(call.page())
    val notices = buildList {
        if (names.isEmpty()) {
            add(Message("$section.empty"))
        } else {
            val entries = page.entries.map { name ->
                val item = Message(if (player) "$section.item" else "$section.console-item", "name" to name)
                if (!canDelete) item else Message(
                    "ui.destination-row",
                    "destination" to item,
                    "delete" to ChatAction(
                        "${delete.id} $name",
                        Message("ui.delete"),
                        Message("$section.delete-hover", "name" to name),
                        suggest = true,
                    ),
                )
            }
            val separator = if (player) Component.newline() else Component.text(", ")
            add(Message("$section.list", "names" to MessageGroup(entries, separator)))
            add(page.navigation(call.command))
        }
        if (canSave) add(Message(
            "ui.control",
            "action" to ChatAction("${save.id} ", Message("$section.add"), Message("$section.add-hover"), suggest = true),
        ))
    }
    actor.reply(notices)
}
