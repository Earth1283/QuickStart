package io.github.Earth1283.quickStart.core

import io.github.Earth1283.quickStart.api.ModuleState
import io.github.Earth1283.quickStart.api.ModuleStatus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

internal interface RuntimeModule {
    suspend fun prepare() = Unit
    fun activate() = Unit
    fun close() = Unit
}

internal data class ModuleDefinition(
    val id: String,
    val enabled: Boolean = true,
    val critical: Boolean = false,
    val required: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
    val create: (CoroutineScope) -> RuntimeModule,
)

internal class ModuleUnavailable(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal fun dependencyOrder(definitions: List<ModuleDefinition>): List<ModuleDefinition> {
    val byId = definitions.associateBy(ModuleDefinition::id)
    require(byId.size == definitions.size) { "Module identifiers must be unique" }
    val visiting = linkedSetOf<String>()
    val ordered = linkedMapOf<String, ModuleDefinition>()

    fun visit(definition: ModuleDefinition) {
        if (definition.id in ordered) return
        require(visiting.add(definition.id)) {
            val cycle = visiting.dropWhile { it != definition.id } + definition.id
            "Module dependency cycle: ${cycle.joinToString(" -> ")}"
        }
        for (dependency in definition.required) {
            val required = requireNotNull(byId[dependency]) { "${definition.id} requires unknown module $dependency" }
            require(!definition.enabled || required.enabled) { "${definition.id} requires disabled module $dependency" }
            visit(required)
        }
        definition.optional.mapNotNull(byId::get).forEach(::visit)
        visiting.remove(definition.id)
        ordered[definition.id] = definition
    }

    definitions.forEach(::visit)
    return ordered.values.toList()
}

internal class Modules(
    parent: CoroutineScope,
    definitions: List<ModuleDefinition>,
    private val activateOnServer: suspend (() -> Unit) -> Unit,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    private class Entry(val definition: ModuleDefinition, parent: CoroutineScope) {
        val job = if (definition.enabled) SupervisorJob(parent.coroutineContext[Job]) else null
        val scope = job?.let { CoroutineScope(parent.coroutineContext + it) }
        var state = if (definition.enabled) ModuleState.NEW else ModuleState.DISABLED
        var elapsedMillis: Long? = null
        var failure: String? = null
        var instance: RuntimeModule? = null
        var initialization: Deferred<RuntimeModule>? = null

        fun status(): ModuleStatus = synchronized(this) {
            ModuleStatus(definition.id, state, definition.required.toList(), definition.optional.toList(), elapsedMillis, failure)
        }
    }

    private val ordered = dependencyOrder(definitions).map { Entry(it, parent) }
    private val byId = ordered.associateBy { it.definition.id }
    private val closing = AtomicBoolean()

    val criticalIds = ordered.filter { it.definition.enabled && it.definition.critical }.map { it.definition.id }
    val optionalIds = ordered.filter { it.definition.enabled && !it.definition.critical }.map { it.definition.id }

    fun statuses(): List<ModuleStatus> = ordered.map(Entry::status)

    fun readyInstance(id: String): RuntimeModule? {
        val entry = byId.getValue(id)
        return synchronized(entry) {
            entry.instance.takeIf { !closing.get() && entry.state == ModuleState.READY }
        }
    }

    suspend fun initialize(id: String): RuntimeModule {
        val entry = byId.getValue(id)
        val scope = entry.scope ?: throw ModuleUnavailable("$id is disabled")
        val initialization = synchronized(entry) {
            if (closing.get()) throw ModuleUnavailable("QuickStart is stopping")
            entry.initialization ?: scope.async(start = CoroutineStart.LAZY) { start(entry, scope) }
                .also { entry.initialization = it }
        }
        return initialization.await()
    }

    suspend fun <T> withModule(id: String, action: suspend (RuntimeModule) -> T): T {
        val entry = byId.getValue(id)
        val instance = initialize(id)
        val operation = synchronized(entry) {
            if (closing.get()) throw ModuleUnavailable("QuickStart is stopping")
            checkNotNull(entry.scope).async { action(instance) }
        }
        try {
            return operation.await()
        } finally {
            if (!operation.isCompleted) operation.cancel()
        }
    }

    private suspend fun start(entry: Entry, scope: CoroutineScope): RuntimeModule {
        val definition = entry.definition
        val started = TimeSource.Monotonic.markNow()
        try {
            transition(entry, ModuleState.PREPARING)
            for (dependency in definition.required) {
                try {
                    initialize(dependency)
                } catch (failure: ModuleUnavailable) {
                    throw ModuleUnavailable("${definition.id} requires $dependency: ${failure.message}", failure)
                }
            }
            for (dependency in definition.optional.filter { byId[it]?.definition?.enabled == true }) {
                try {
                    initialize(dependency)
                } catch (_: ModuleUnavailable) {
                }
            }
            val instance = synchronized(entry) {
                checkRunning()
                definition.create(scope).also { entry.instance = it }
            }
            instance.prepare()
            transition(entry, ModuleState.PREPARED)
            activateOnServer {
                synchronized(entry) {
                    checkRunning()
                    entry.state = ModuleState.ACTIVATING
                    instance.activate()
                    entry.elapsedMillis = started.elapsedNow().inWholeMilliseconds
                    entry.state = ModuleState.READY
                }
            }
            return instance
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            val reason = failure.message ?: failure.javaClass.simpleName
            synchronized(entry) {
                entry.elapsedMillis = started.elapsedNow().inWholeMilliseconds
                if (!closing.get()) {
                    entry.state = ModuleState.FAILED
                    entry.failure = reason
                }
            }
            closeInstance(entry)
            reportFailure(definition.id, failure)
            throw ModuleUnavailable("${definition.id} is unavailable: $reason", failure)
        }
    }

    private fun checkRunning() {
        if (closing.get()) throw CancellationException("QuickStart is stopping")
    }

    private fun transition(entry: Entry, state: ModuleState) = synchronized(entry) {
        checkRunning()
        entry.state = state
    }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        val active = ordered.asReversed().filter { it.job != null }
        val remaining = AtomicInteger(active.size)
        for (entry in active) {
            synchronized(entry) { entry.state = ModuleState.STOPPING }
            val job = checkNotNull(entry.job)
            job.invokeOnCompletion {
                if (remaining.decrementAndGet() == 0) {
                    for (stopped in active) {
                        closeInstance(stopped)
                        synchronized(stopped) { stopped.state = ModuleState.STOPPED }
                    }
                }
            }
            job.cancel()
        }
    }

    private fun closeInstance(entry: Entry) {
        val instance = synchronized(entry) { entry.instance.also { entry.instance = null } } ?: return
        try {
            instance.close()
        } catch (failure: Exception) {
            reportFailure(entry.definition.id, failure)
        }
    }
}
