package io.github.Earth1283.quickStart.core

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal suspend fun warmModules(
    moduleIds: List<String>,
    settings: WarmupSettings,
    onlinePlayers: StateFlow<Int>,
    initialize: suspend (String) -> Unit,
) = coroutineScope {
    val pending = MutableStateFlow(moduleIds)
    val workers = maxOf(settings.workers(0), settings.workers(1)).coerceAtMost(moduleIds.size)
    repeat(workers) { worker ->
        launch {
            while (true) {
                combine(onlinePlayers, pending) { online, modules ->
                    modules.isEmpty() || worker < settings.workers(online)
                }.first { it }
                val next = synchronized(pending) {
                    val modules = pending.value
                    if (modules.isEmpty()) return@launch
                    if (worker >= settings.workers(onlinePlayers.value)) null
                    else modules.first().also { pending.value = modules.drop(1) }
                }
                if (next != null) initialize(next)
            }
        }
    }
}
