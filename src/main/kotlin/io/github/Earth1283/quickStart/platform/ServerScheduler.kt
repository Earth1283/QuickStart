package io.github.Earth1283.quickStart.platform

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.Plugin

internal class EntityUnavailable : IllegalStateException("The player connection is no longer active")

internal class ServerScheduler(private val plugin: Plugin) {
    private val server = plugin.server

    suspend fun <T> global(action: () -> T): T = scheduled(action) { run, _ ->
        server.globalRegionScheduler.run(plugin) { run.run() }
    }

    suspend fun <T> region(location: Location, action: () -> T): T = scheduled(action) { run, _ ->
        server.regionScheduler.run(plugin, location) { run.run() }
    }

    suspend fun <T> entity(entity: Entity, action: () -> T): T = scheduled(action) { run, retired ->
        entity.scheduler.run(plugin, { run.run() }, retired)
    }

    suspend fun <T> async(action: () -> T): T = withContext(Dispatchers.Default) { action() }

    suspend fun <T> blocking(action: () -> T): T = withContext(Dispatchers.IO) { action() }

    private suspend fun <T> scheduled(
        action: () -> T,
        submit: (Runnable, Runnable) -> ScheduledTask?,
    ): T = suspendCancellableCoroutine { continuation ->
        val retired = Runnable { continuation.resumeWithException(EntityUnavailable()) }
        val run = Runnable {
            if (continuation.isActive) continuation.resumeWith(runCatching(action))
        }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        val task = try {
            submit(run, retired)
        } catch (disabled: IllegalPluginAccessException) {
            continuation.cancel(CancellationException("QuickStart scheduling stopped", disabled))
            return@suspendCancellableCoroutine
        }
        if (task == null) retired.run()
        else continuation.invokeOnCancellation { task.cancel() }
    }
}
