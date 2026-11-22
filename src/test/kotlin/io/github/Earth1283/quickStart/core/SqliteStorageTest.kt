package io.github.Earth1283.quickStart.core

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteStorageTest {
    @TempDir lateinit var directory: Path
    private val position = Position(UUID.randomUUID(), -47.25, 64.0, 8193.0, 91f, -15f)

    @Test
    fun `locations persist and deletion survives reopen`() = runBlocking {
        val file = directory.resolve("server data #1").resolve("quickstart.db")
        val homes = DestinationGroup.homes(UUID.randomUUID())
        useStorage(file) { storage ->
            assertEquals(SaveResult.CREATED, storage.save(homes, "home", position, 1))
            assertEquals(SaveResult.UPDATED, storage.save(homes, "home", position.copy(y = 70.0), 1))
            assertEquals(SaveResult.LIMIT_REACHED, storage.save(homes, "nether", position, 1))
            assertEquals(SaveResult.CREATED, storage.save(DestinationGroup.warps, "town", position))
            assertEquals(SaveResult.CREATED, storage.save(DestinationGroup.spawn, "spawn", position))
        }
        useStorage(file) { storage ->
            assertEquals(listOf("home"), storage.names(homes))
            assertEquals(position.copy(y = 70.0), storage.get(homes, "home"))
            assertEquals(position, storage.get(DestinationGroup.warps, "town"))
            assertEquals(position, storage.get(DestinationGroup.spawn, "spawn"))
            assertTrue(storage.delete(homes, "home"))
            assertFalse(storage.delete(homes, "home"))
        }
        useStorage(file) { storage -> assertNull(storage.get(homes, "home")) }
    }

    @Test
    fun `concurrent writes enforce the per-player limit atomically`() = runBlocking {
        val homes = DestinationGroup.homes(UUID.randomUUID())
        useStorage(directory.resolve("concurrent.db")) { storage ->
            val results = List(32) { index -> async { storage.save(homes, "home-$index", position, 5) } }.awaitAll()
            assertEquals(5, results.count { it == SaveResult.CREATED })
            assertEquals(27, results.count { it == SaveResult.LIMIT_REACHED })
            assertEquals(5, storage.names(homes).size)
            assertTrue(storage.names(DestinationGroup.homes(UUID.randomUUID())).isEmpty())
        }
    }

    @Test
    fun `lowering the home limit still allows updates and isolates other owners`() = runBlocking {
        val homes = DestinationGroup.homes(UUID.randomUUID())
        val otherHomes = DestinationGroup.homes(UUID.randomUUID())
        useStorage(directory.resolve("limits.db")) { storage ->
            for (name in listOf("first", "second", "third")) {
                assertEquals(SaveResult.CREATED, storage.save(homes, name, position, 3))
            }
            assertEquals(SaveResult.LIMIT_REACHED, storage.save(homes, "fourth", position, 2))
            assertEquals(SaveResult.UPDATED, storage.save(homes, "third", position, 2))
            assertEquals(SaveResult.CREATED, storage.save(otherHomes, "fourth", position, 2))
            assertEquals(SaveResult.CREATED, storage.save(homes, "fourth", position, 4))
        }
    }

    @Test
    fun `a failed write does not prevent later saves using the same query`() = runBlocking {
        useStorage(directory.resolve("retry.db")) { storage ->
            assertEquals(SaveResult.CREATED, storage.save(DestinationGroup.warps, "town", position))
            assertFailsWith<StorageFailure> { storage.save(DestinationGroup.spawn, "invalid", position) }
            assertEquals(SaveResult.CREATED, storage.save(DestinationGroup.spawn, "spawn", position))
            assertEquals(position, storage.get(DestinationGroup.spawn, "spawn"))
        }
    }

    @Test
    fun `accepted writes drain at shutdown when their callers are cancelled`() = runBlocking {
        val file = directory.resolve("drain.db")
        val homeGroup = DestinationGroup.homes(UUID.randomUUID())
        val names = List(16) { "home-$it" }.sorted()
        useStorage(file) { storage ->
            val calls = names.map { name ->
                async(start = CoroutineStart.UNDISPATCHED) { storage.save(homeGroup, name, position) }
            }
            calls.forEach { it.cancel() }
            storage.close()
        }
        useStorage(file) { storage -> assertEquals(names, storage.names(homeGroup)) }
    }

    @Test
    fun `future schema versions and invalid stored positions are reported`() = runBlocking {
        val file = directory.resolve("invalid.db")
        useStorage(file) { storage -> storage.save(DestinationGroup.warps, "town", position) }
        val dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:$file" }
        dataSource.connection.use { database ->
            database.createStatement().use { it.executeUpdate("UPDATE destinations SET world_id = 'invalid'") }
        }
        useStorage(file) { storage ->
            assertFailsWith<StorageFailure> { storage.get(DestinationGroup.warps, "town") }
            assertEquals("read warp", storage.lastFailure?.operation)
        }
        dataSource.connection.use { database ->
            database.createStatement().use { it.execute("PRAGMA user_version = 53") }
        }
        val failure = assertFailsWith<StorageFailure> { useStorage(file) { error("Unsupported schema opened") } }
        assertTrue(failure.message.orEmpty().contains("Unsupported SQLite schema version 53"))
    }

    private suspend fun useStorage(file: Path, work: suspend (SqliteStorage) -> Unit) {
        val storage = SqliteStorage(file) { throw AssertionError("Storage shutdown failed", it) }
        try {
            storage.prepare()
            storage.health()
            work(storage)
        } finally {
            storage.close()
            storage.closed.get(5, TimeUnit.SECONDS)
        }
    }
}
