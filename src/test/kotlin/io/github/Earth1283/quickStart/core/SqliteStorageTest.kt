package io.github.Earth1283.quickStart.core

import java.nio.file.Path
import java.time.Instant
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
            assertEquals(listOf("home"), storage.cachedNames(homes))
            assertEquals(listOf("town"), storage.cachedNames(DestinationGroup.warps))
            assertTrue(storage.cachedNames(DestinationGroup.homes(UUID.randomUUID())).isEmpty())
        }
        useStorage(file) { storage ->
            assertEquals(listOf("home"), storage.cachedNames(homes))
            assertEquals(listOf("town"), storage.cachedNames(DestinationGroup.warps))
            assertEquals(listOf("home"), storage.names(homes))
            assertEquals(position.copy(y = 70.0), storage.get(homes, "home"))
            assertEquals(position, storage.get(DestinationGroup.warps, "town"))
            assertEquals(position, storage.get(DestinationGroup.spawn, "spawn"))
            assertTrue(storage.delete(homes, "home"))
            assertFalse(storage.delete(homes, "home"))
            assertTrue(storage.cachedNames(homes).isEmpty())
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
            assertEquals(storage.names(homes), storage.cachedNames(homes))
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

    @Test
    fun `version one migrates without losing locations and moderation survives restart`() = runBlocking {
        val file = directory.resolve("moderation.db")
        useStorage(file) { it.save(DestinationGroup.warps, "town", position) }
        SQLiteDataSource().apply { url = "jdbc:sqlite:$file" }.connection.use { database ->
            database.createStatement().use { statement ->
                statement.execute("DROP TABLE player_preferences")
                statement.execute("DROP TABLE player_ips")
                statement.execute("DROP TABLE player_profiles")
                statement.execute("DROP TABLE moderation_history")
                statement.execute("DROP TABLE sanctions")
                statement.execute("PRAGMA user_version = 1")
            }
        }
        val address = checkNotNull(parseIp("192.0.2.1"))
        val target = SanctionTarget.Range(checkNotNull(IpNetwork.parse("192.0.2.0/24")))
        val key = SanctionKey(SanctionKind.BAN, target)
        val actor = UUID.randomUUID()
        val time = Instant.ofEpochMilli(Instant.now().toEpochMilli())
        val record = ModerationRecord("iprangeban", target, target.value, "Staff", time, "First\nSecond")
        val sanction = Sanction(key, record.reason, null)
        useStorage(file) { storage ->
            assertEquals(position, storage.get(DestinationGroup.warps, "town"))
            storage.loadSanctions()
            assertTrue(storage.saveSanction(sanction, record, canShorten = false))
            val deadline = time.plusSeconds(600)
            assertFalse(storage.saveSanction(sanction.copy(expiresAt = deadline), record.copy(expiresAt = deadline), canShorten = false))
            assertEquals(sanction, storage.sanctions.find(SanctionKind.BAN, actor, address))
        }
        val removal = record.copy(command = "unbaniprange", reason = null)
        useStorage(file) { storage ->
            storage.loadSanctions()
            assertEquals(sanction, storage.sanctions.find(SanctionKind.BAN, actor, address))
            assertTrue(storage.deleteSanction(key, removal))
            assertFalse(storage.deleteSanction(key, removal))
            assertNull(storage.sanctions.find(SanctionKind.BAN, actor, address))
            assertEquals(listOf(removal, record), storage.moderationHistory(target, 5, 0))
            assertEquals(listOf(record), storage.moderationHistory(target, 5, 1))
        }
    }

    @Test
    fun `version two migrates to version three and persists profiles, alts, and preferences`() = runBlocking {
        val file = directory.resolve("v3-migration.db")
        useStorage(file) { it.save(DestinationGroup.warps, "hub", position) }
        SQLiteDataSource().apply { url = "jdbc:sqlite:$file" }.connection.use { database ->
            database.createStatement().use { statement ->
                statement.execute("DROP TABLE player_preferences")
                statement.execute("DROP TABLE player_ips")
                statement.execute("DROP TABLE player_profiles")
                statement.execute("PRAGMA user_version = 2")
            }
        }
        val alexId = UUID.randomUUID()
        val steveId = UUID.randomUUID()
        val thirdId = UUID.randomUUID()
        val joined = Instant.ofEpochMilli(1_700_000_000_000L)
        val quit = joined.plusSeconds(120)

        useStorage(file) { storage ->
            assertEquals(position, storage.get(DestinationGroup.warps, "hub"))
            storage.recordPlayerJoin(alexId, "Alex", "192.0.2.10", joined)
            storage.recordPlayerQuit(alexId, "Alex", quit, position)
            storage.recordPlayerJoin(steveId, "Steve", "192.0.2.10", quit.plusSeconds(30))
            storage.recordPlayerJoin(thirdId, "Other", "198.51.100.5", quit.plusSeconds(60))
            assertEquals(PlayerPreferences(respectBedSpawn = false, safeTeleport = null), storage.setRespectBedSpawn(alexId, false))
            assertEquals(PlayerPreferences(respectBedSpawn = false, safeTeleport = false), storage.setSafeTeleport(alexId, false))
            assertTrue("Alex" in storage.cachedPlayerNames())
        }

        useStorage(file) { storage ->
            val profile = checkNotNull(storage.findPlayerProfileByName("alex"))
            assertEquals(alexId, profile.id)
            assertEquals(joined, profile.firstSeen)
            assertEquals(quit, profile.lastQuit)
            assertEquals("192.0.2.10", profile.lastIp)
            assertEquals(position.worldId, profile.lastPosition?.worldId)
            assertEquals(position.x, profile.lastPosition?.x)

            val alts = storage.findAltsForAccount(alexId)
            assertEquals(listOf("192.0.2.10"), alts.checkedIps)
            assertEquals(listOf(steveId), alts.accounts.map(AltAccount::id))
            assertEquals(listOf("192.0.2.10"), alts.accounts.single().sharedIps)

            val onIp = storage.accountsByIp("192.0.2.10")
            assertEquals(listOf(steveId, alexId), onIp.map(AltAccount::id))

            assertEquals(PlayerPreferences(respectBedSpawn = false, safeTeleport = false), storage.preferences(alexId))
            assertEquals(PlayerPreferences.DEFAULT, storage.preferences(steveId))

            storage.setRespectBedSpawn(alexId, null)
            storage.setSafeTeleport(alexId, null)
            assertEquals(PlayerPreferences.DEFAULT, storage.preferences(alexId))
        }

        useStorage(file) { storage ->
            assertEquals(PlayerPreferences.DEFAULT, storage.preferences(alexId))
        }
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
