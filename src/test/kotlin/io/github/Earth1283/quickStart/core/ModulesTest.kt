package io.github.Earth1283.quickStart.core

import io.github.Earth1283.quickStart.api.ModuleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModulesTest {
    @Test
    fun `dependency graph orders modules and rejects cycles and missing required dependencies`() {
        val storage = definition("storage")
        val homes = definition("homes", required = listOf("storage"))
        val spawn = definition("spawn", optional = listOf("homes", "external"))
        assertEquals(listOf("storage", "homes", "spawn"), dependencyOrder(listOf(spawn, homes, storage)).map { it.id })
        assertFailsWith<IllegalArgumentException> { dependencyOrder(listOf(homes)) }
        assertFailsWith<IllegalArgumentException> { dependencyOrder(listOf(homes, storage.copy(enabled = false))) }
        assertFailsWith<IllegalArgumentException> { dependencyOrder(listOf(homes, storage.copy(optional = listOf("homes")))) }
        assertFailsWith<IllegalArgumentException> { dependencyOrder(listOf(storage, storage)) }
    }

    @Test
    fun `concurrent requests share work and caller cancellation leaves initialization running`() = runBlocking {
        withTimeout(5_000) {
            val preparing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val activated = mutableListOf<String>()
            var creations = 0
            lateinit var modules: Modules
            val storage = definition("storage") { object : RuntimeModule {
                override fun activate() { activated.add("storage") }
            } }
            val homes = definition("homes", required = listOf("storage")) { object : RuntimeModule {
                init { creations++ }
                override suspend fun prepare() {
                    preparing.complete(Unit)
                    release.await()
                }
                override fun activate() {
                    assertEquals(ModuleState.ACTIVATING, modules.statuses().last().state)
                    activated.add("homes")
                }
            } }
            modules = Modules(this, listOf(homes, storage), { it() }, { _, failure -> throw failure })
            try {
                val firstCaller = async { modules.initialize("homes") }
                preparing.await()
                assertEquals(ModuleState.PREPARING, modules.statuses().last().state)
                val otherCallers = List(20) { async { modules.initialize("homes") } }
                firstCaller.cancelAndJoin()
                release.complete(Unit)
                val outcomes = otherCallers.awaitAll()
                outcomes.forEach { assertSame(outcomes.first(), it) }
                assertEquals(1, creations)
                assertEquals(listOf("storage", "homes"), activated)
                assertEquals(ModuleState.READY, modules.statuses().last().state)
            } finally {
                modules.close()
            }
        }
    }

    @Test
    fun `optional failures are isolated and required failures prevent construction`() = runBlocking {
        val failures = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val failure = definition("failure") { object : RuntimeModule {
            override suspend fun prepare() { error("broken database") }
            override fun close() { closed.add("failure") }
        } }
        val dependent = definition("dependent", required = listOf("failure")) { error("Factory should not run") }
        val optional = definition("optional", optional = listOf("failure"))
        val disabled = definition("disabled") { error("Disabled factory should not run") }.copy(enabled = false)
        val modules = Modules(this, listOf(dependent, optional, failure, disabled), { it() }, { id, _ -> failures.add(id) })
        try {
            modules.initialize("optional")
            repeat(2) { assertFailsWith<ModuleUnavailable> { modules.initialize("dependent") } }
            assertFailsWith<ModuleUnavailable> { modules.initialize("disabled") }
            assertEquals(listOf("failure", "dependent"), failures)
            assertEquals(listOf("failure"), closed)
            assertTrue(modules.statuses().first { it.id == "dependent" }.failure.orEmpty().contains("broken database"))
        } finally {
            modules.close()
        }
    }

    @Test
    fun `shutdown during preparation closes dependents before their dependencies`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val wait = CompletableDeferred<Unit>()
            val closed = mutableListOf<String>()
            val complete = CompletableDeferred<Unit>()
            val dependency = definition("dependency") { object : RuntimeModule {
                override fun close() { closed.add("dependency"); complete.complete(Unit) }
            } }
            val dependent = definition("dependent", required = listOf("dependency")) { object : RuntimeModule {
                override suspend fun prepare() { entered.complete(Unit); wait.await() }
                override fun activate() = error("Should not activate after shutdown")
                override fun close() { closed.add("dependent") }
            } }
            val modules = Modules(this, listOf(dependent, dependency), { it() }, { _, failure -> throw failure })
            val initialization = async { modules.initialize("dependent") }
            entered.await()
            modules.close()
            complete.await()
            assertTrue(initialization.isCancelled || initialization.awaitCancellation())
            assertEquals(listOf("dependent", "dependency"), closed)
            assertTrue(modules.statuses().all { it.state == ModuleState.STOPPED })
        }
    }

    private suspend fun kotlinx.coroutines.Deferred<RuntimeModule>.awaitCancellation(): Boolean =
        runCatching { await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException

    private fun definition(
        id: String,
        required: List<String> = emptyList(),
        optional: List<String> = emptyList(),
        create: () -> RuntimeModule = { object : RuntimeModule {} },
    ) = ModuleDefinition(id, required = required, optional = optional) { create() }
}
