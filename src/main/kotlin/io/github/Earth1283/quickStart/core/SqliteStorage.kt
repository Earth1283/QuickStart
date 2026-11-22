package io.github.Earth1283.quickStart.core

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.TimeSource
import kotlinx.coroutines.suspendCancellableCoroutine
import org.sqlite.SQLiteDataSource

internal class StorageFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal data class StorageIncident(val at: Instant, val operation: String, val reason: String)

internal class SqliteStorage(
    private val file: Path,
    private val maxPendingOperations: Int = MAX_PENDING_OPERATIONS_DEFAULT,
    private val reportFailure: (Throwable) -> Unit,
) : RuntimeModule {
    private val gate = Any()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(maxPendingOperations + 1), { task ->
        Thread(task, "QuickStart-SQLite").apply { isDaemon = false }
    })
    private var accepting = true
    private var connection: Connection? = null
    // Only the SQLite worker uses the statements, and the connection closes them at shutdown.
    private val statements = mutableMapOf<String, PreparedStatement>()
    val closed = CompletableFuture<Unit>()
    private val runtime = Runtime.getRuntime()
    private val shutdownHook = Thread({
        close()
        closed.join()
    }, "QuickStart-SQLite-shutdown")
    private var hookRegistered = false
    private val incident = AtomicReference<StorageIncident>()
    val lastFailure: StorageIncident? get() = incident.get()
    val timings = LatencyMeasurements()

    override suspend fun prepare() = submit("initialize", true) {
        runtime.addShutdownHook(shutdownHook)
        hookRegistered = true
        Files.createDirectories(file.toAbsolutePath().parent)
        val database = SQLiteDataSource().apply { url = "jdbc:sqlite:${file.toAbsolutePath().toUri()}" }.connection
        connection = database
        database.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout = 1000")
            statement.executeQuery("PRAGMA journal_mode = WAL").use { rows ->
                check(rows.next() && rows.getString(1).equals("wal", ignoreCase = true)) { "SQLite WAL mode is unavailable" }
            }
        }
        migrate(database)
        verify(database)
    }

    suspend fun health() = query("health check", action = ::verify)

    suspend fun get(group: DestinationGroup, name: String): Position? = query("read ${group.kind.key}") { database ->
        database.withStatement("SELECT world_id, x, y, z, yaw, pitch FROM destinations WHERE kind = ? AND owner = ? AND name = ?") { statement ->
            statement.bind(group, name)
            statement.executeQuery().use { result -> if (result.next()) result.position() else null }
        }
    }

    suspend fun names(group: DestinationGroup): List<String> = query("list ${group.kind.key}s") { database ->
        database.withStatement("SELECT name FROM destinations WHERE kind = ? AND owner = ? ORDER BY name") { statement ->
            statement.bind(group)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val name = result.getString("name")
                        check(DestinationNames.normalize(name) == name) { "Invalid saved destination name" }
                        add(name)
                    }
                }
            }
        }
    }

    suspend fun save(group: DestinationGroup, name: String, position: Position, limit: Int? = null): SaveResult =
        query("save ${group.kind.key}", write = true) { database ->
            require(DestinationNames.normalize(name) == name) { "Invalid destination name" }
            database.transaction {
                val exists = withStatement("SELECT 1 FROM destinations WHERE kind = ? AND owner = ? AND name = ?") { statement ->
                    statement.bind(group, name)
                    statement.executeQuery().use(ResultSet::next)
                }
                if (!exists && limit != null && limitReached(group, limit)) return@transaction SaveResult.LIMIT_REACHED
                withStatement(UPSERT_DESTINATION) { statement ->
                    statement.bind(group, name)
                    statement.setString(4, position.worldId.toString())
                    statement.setDouble(5, position.x)
                    statement.setDouble(6, position.y)
                    statement.setDouble(7, position.z)
                    statement.setFloat(8, position.yaw)
                    statement.setFloat(9, position.pitch)
                    statement.executeUpdate()
                }
                if (exists) SaveResult.UPDATED else SaveResult.CREATED
            }
        }

    suspend fun delete(group: DestinationGroup, name: String): Boolean = query("delete ${group.kind.key}", write = true) { database ->
        database.withStatement("DELETE FROM destinations WHERE kind = ? AND owner = ? AND name = ?") { statement ->
            statement.bind(group, name)
            statement.executeUpdate() > 0
        }
    }

    override fun close() {
        synchronized(gate) {
            if (!accepting) return
            accepting = false
            executor.execute {
                try {
                    connection?.close()
                    closed.complete(Unit)
                } catch (failure: Exception) {
                    closed.completeExceptionally(failure)
                    reportFailure(failure)
                } finally {
                    statements.clear()
                    connection = null
                    if (hookRegistered) {
                        try {
                            runtime.removeShutdownHook(shutdownHook)
                        } catch (_: IllegalStateException) {
                        }
                    }
                }
            }
            executor.shutdown()
        }
    }

    private suspend fun <T> query(operation: String, write: Boolean = false, action: (Connection) -> T): T =
        submit(operation, write) { action(checkNotNull(connection) { "SQLite is not initialized" }) }

    private suspend fun <T> submit(operation: String, write: Boolean, action: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            synchronized(gate) {
                if (!continuation.isActive) return@synchronized
                if (!accepting || executor.queue.size >= maxPendingOperations) {
                    val reason = if (accepting) "SQLite queue is full. Try again shortly." else "SQLite is shutting down."
                    continuation.resumeWith(Result.failure(StorageFailure(reason)))
                    return@synchronized
                }
                val queued = TimeSource.Monotonic.markNow()
                executor.execute {
                    if (!write && !continuation.isActive) return@execute
                    val result = runCatching(action).recoverCatching { failure ->
                        val reason = failure.message ?: failure.javaClass.simpleName
                        incident.set(StorageIncident(Instant.now(), operation, reason))
                        throw StorageFailure("SQLite could not $operation: $reason", failure)
                    }
                    timings.record(queued.elapsedNow().inWholeNanoseconds)
                    continuation.resumeWith(result)
                }
            }
        }

    private fun migrate(database: Connection) {
        database.transaction {
            createStatement().use { statement ->
                val version = statement.executeQuery("PRAGMA user_version").use { rows ->
                    check(rows.next()) { "Unable to read SQLite schema version" }
                    rows.getInt(1)
                }
                when (version) {
                    0 -> {
                        statement.executeUpdate(
                            """
                            CREATE TABLE destinations (
                                kind TEXT NOT NULL CHECK(kind IN ('home', 'warp', 'spawn')),
                                owner TEXT NOT NULL,
                                name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 32),
                                world_id TEXT NOT NULL,
                                x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
                                yaw REAL NOT NULL, pitch REAL NOT NULL,
                                PRIMARY KEY(kind, owner, name),
                                CHECK((kind = 'home' AND length(owner) = 36) OR (kind != 'home' AND owner = '')),
                                CHECK(kind != 'spawn' OR name = 'spawn')
                            ) WITHOUT ROWID, STRICT
                            """.trimIndent(),
                        )
                        statement.executeUpdate("PRAGMA user_version = 1")
                    }
                    1 -> Unit
                    else -> error("Unsupported SQLite schema version $version. Check the installed QuickStart version.")
                }
            }
        }
    }

    private fun verify(database: Connection) {
        database.createStatement().use { statement ->
            statement.executeQuery("SELECT kind, owner, name, world_id, x, y, z, yaw, pitch FROM destinations LIMIT 0").close()
        }
    }

    private fun Connection.limitReached(group: DestinationGroup, limit: Int): Boolean =
        limit <= 0 || withStatement("SELECT 1 FROM destinations WHERE kind = ? AND owner = ? LIMIT 1 OFFSET ?") { statement ->
            statement.bind(group)
            statement.setInt(3, limit - 1)
            statement.executeQuery().use(ResultSet::next)
        }

    private inline fun <T> Connection.withStatement(sql: String, action: (PreparedStatement) -> T): T {
        val statement = statements.getOrPut(sql) { prepareStatement(sql) }
        try {
            return action(statement)
        } catch (failure: SQLException) {
            statements.remove(sql)
            try {
                statement.close()
            } catch (closeFailure: SQLException) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private fun PreparedStatement.bind(group: DestinationGroup, name: String? = null) {
        setString(1, group.kind.key)
        setString(2, group.owner)
        if (name != null) setString(3, name)
    }

    private fun ResultSet.position() = Position(
        UUID.fromString(getString("world_id")), coordinate("x").toDouble(), coordinate("y").toDouble(),
        coordinate("z").toDouble(), coordinate("yaw").toFloat(), coordinate("pitch").toFloat(),
    )

    private fun ResultSet.coordinate(column: String): Number =
        getObject(column) as? Number ?: error("Invalid saved coordinate $column")

    private inline fun <T> Connection.transaction(action: Connection.() -> T): T {
        autoCommit = false
        try {
            val result = action()
            commit()
            return result
        } catch (failure: Throwable) {
            try {
                rollback()
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        } finally {
            autoCommit = true
        }
    }

    private companion object {
        const val MAX_PENDING_OPERATIONS_DEFAULT = 128
        val UPSERT_DESTINATION = """
            INSERT INTO destinations (kind, owner, name, world_id, x, y, z, yaw, pitch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(kind, owner, name) DO UPDATE SET
                world_id = excluded.world_id, x = excluded.x, y = excluded.y, z = excluded.z,
                yaw = excluded.yaw, pitch = excluded.pitch
        """.trimIndent()
    }
}
