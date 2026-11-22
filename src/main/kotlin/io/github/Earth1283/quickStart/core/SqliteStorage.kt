package io.github.Earth1283.quickStart.core

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
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
    // The SQLite worker publishes immutable name lists after its writes commit.
    private val savedNames = ConcurrentHashMap<DestinationGroup, List<String>>()
    val sanctions = SanctionIndex()
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
        loadSavedNames(database)
    }

    fun cachedNames(group: DestinationGroup): List<String> = savedNames[group].orEmpty()

    suspend fun health() = query("health check", action = ::verify)

    suspend fun loadSanctions() = query("load moderation", write = true) { database ->
        val now = Instant.now().toEpochMilli()
        database.withStatement("DELETE FROM sanctions WHERE expires_at <= ?") { statement ->
            statement.setLong(1, now)
            statement.executeUpdate()
        }
        database.createStatement().use { statement ->
            statement.executeQuery("SELECT kind, scope, target, reason, expires_at FROM sanctions").use { rows ->
                while (rows.next()) {
                    val target = SanctionTarget.decode(TargetKind.valueOf(rows.getString("scope")), rows.getString("target"))
                    val key = SanctionKey(SanctionKind.valueOf(rows.getString("kind")), target)
                    sanctions.put(Sanction(key, rows.getString("reason"), rows.expiration()))
                }
            }
        }
    }

    suspend fun saveSanction(sanction: Sanction, record: ModerationRecord, canShorten: Boolean): Boolean =
        query("save sanction", write = true) { database ->
            val previous = sanctions.get(sanction.key)
            val deadline = sanction.expiresAt
            if (!canShorten && deadline != null && previous?.active(Instant.now()) == true &&
                (previous.expiresAt == null || deadline.isBefore(previous.expiresAt))
            ) return@query false
            database.transaction {
                withStatement(UPSERT_SANCTION) { statement ->
                    statement.bind(sanction.key)
                    statement.setString(4, sanction.reason)
                    statement.setExpiration(5, sanction.expiresAt)
                    statement.executeUpdate()
                }
                recordModeration(record)
            }
            sanctions.put(sanction)
            true
        }

    suspend fun deleteSanction(key: SanctionKey, record: ModerationRecord): Boolean = query("delete sanction", write = true) { database ->
        val removed = database.transaction {
            val active = withStatement(
                "DELETE FROM sanctions WHERE kind = ? AND scope = ? AND target = ? AND (expires_at IS NULL OR expires_at > ?)",
            ) { statement ->
                statement.bind(key)
                statement.setLong(4, record.time.toEpochMilli())
                statement.executeUpdate() > 0
            }
            if (active) recordModeration(record)
            active
        }
        sanctions.remove(key)
        removed
    }

    suspend fun logModeration(record: ModerationRecord) = query("record moderation action", write = true) { database ->
        database.recordModeration(record)
    }

    suspend fun moderationHistory(target: SanctionTarget, limit: Int, offset: Long): List<ModerationRecord> = query("read moderation history") { database ->
        database.withStatement(
            "SELECT command, label, actor, recorded_at, reason, expires_at FROM moderation_history " +
                "WHERE scope = ? AND target = ? ORDER BY id DESC LIMIT ? OFFSET ?",
        ) { statement ->
            statement.setString(1, target.kind.name)
            statement.setString(2, target.value)
            statement.setInt(3, limit)
            statement.setLong(4, offset)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(ModerationRecord(rows.getString("command"), target, rows.getString("label"), rows.getString("actor"),
                            Instant.ofEpochMilli(rows.getLong("recorded_at")), rows.getString("reason"), rows.expiration()))
                    }
                }
            }
        }
    }

    private fun Connection.recordModeration(record: ModerationRecord) {
        withStatement(
            "INSERT INTO moderation_history (scope, target, command, label, actor, recorded_at, reason, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ) { statement ->
            statement.setString(1, record.target.kind.name)
            statement.setString(2, record.target.value)
            statement.setString(3, record.command)
            statement.setString(4, record.label)
            statement.setString(5, record.actor)
            statement.setLong(6, record.time.toEpochMilli())
            statement.setString(7, record.reason)
            statement.setExpiration(8, record.expiresAt)
            statement.executeUpdate()
        }
    }

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
            val result = database.transaction {
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
            if (result == SaveResult.CREATED && group.kind != DestinationKind.SPAWN) {
                savedNames[group] = (cachedNames(group) + name).sorted()
            }
            result
        }

    suspend fun delete(group: DestinationGroup, name: String): Boolean = query("delete ${group.kind.key}", write = true) { database ->
        val removed = database.withStatement("DELETE FROM destinations WHERE kind = ? AND owner = ? AND name = ?") { statement ->
            statement.bind(group, name)
            statement.executeUpdate() > 0
        }
        if (removed && group.kind != DestinationKind.SPAWN) {
            val names = cachedNames(group) - name
            if (names.isEmpty()) savedNames.remove(group) else savedNames[group] = names
        }
        removed
    }

    private fun loadSavedNames(database: Connection) {
        val names = mutableMapOf<DestinationGroup, MutableList<String>>()
        database.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT kind, owner, name FROM destinations WHERE kind IN ('home', 'warp') ORDER BY kind, owner, name",
            ).use { rows ->
                while (rows.next()) {
                    val name = rows.getString("name")
                    if (DestinationNames.normalize(name) != name) continue
                    val group = when (rows.getString("kind")) {
                        "home" -> DestinationGroup(DestinationKind.HOME, rows.getString("owner"))
                        else -> DestinationGroup.warps
                    }
                    names.getOrPut(group, ::mutableListOf).add(name)
                }
            }
        }
        savedNames.putAll(names.mapValues { (_, groupNames) -> groupNames.toList() })
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
                    savedNames.clear()
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
                check(version in 0..2) { "Unsupported SQLite schema version $version. Check the installed QuickStart version." }
                if (version == 0) {
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
                }
                if (version < 2) {
                    statement.executeUpdate(
                        """
                        CREATE TABLE sanctions (
                            kind TEXT NOT NULL CHECK(kind IN ('BAN', 'MUTE')),
                            scope TEXT NOT NULL CHECK(scope IN ('ACCOUNT', 'IP', 'RANGE')),
                            target TEXT NOT NULL,
                            reason TEXT,
                            expires_at INTEGER,
                            PRIMARY KEY(kind, scope, target),
                            CHECK(kind = 'BAN' OR scope != 'RANGE')
                        ) WITHOUT ROWID, STRICT
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE moderation_history (
                            id INTEGER PRIMARY KEY,
                            scope TEXT NOT NULL CHECK(scope IN ('ACCOUNT', 'IP', 'RANGE')),
                            target TEXT NOT NULL,
                            command TEXT NOT NULL,
                            label TEXT NOT NULL,
                            actor TEXT NOT NULL,
                            recorded_at INTEGER NOT NULL,
                            reason TEXT,
                            expires_at INTEGER
                        ) STRICT
                        """.trimIndent(),
                    )
                    statement.executeUpdate("CREATE INDEX moderation_history_target ON moderation_history(scope, target, id DESC)")
                    statement.executeUpdate("PRAGMA user_version = 2")
                }
            }
        }
    }

    private fun verify(database: Connection) {
        database.createStatement().use { statement ->
            statement.executeQuery("SELECT kind, owner, name, world_id, x, y, z, yaw, pitch FROM destinations LIMIT 0").close()
            statement.executeQuery("SELECT kind, scope, target, reason, expires_at FROM sanctions LIMIT 0").close()
            statement.executeQuery("SELECT id, scope, target, command, label, actor, recorded_at, reason, expires_at FROM moderation_history LIMIT 0").close()
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

    private fun PreparedStatement.bind(key: SanctionKey) {
        setString(1, key.kind.name)
        setString(2, key.target.kind.name)
        setString(3, key.target.value)
    }

    private fun PreparedStatement.setExpiration(parameter: Int, expiresAt: Instant?) {
        if (expiresAt == null) setNull(parameter, Types.BIGINT) else setLong(parameter, expiresAt.toEpochMilli())
    }

    private fun ResultSet.expiration(): Instant? = getLong("expires_at").let { if (wasNull()) null else Instant.ofEpochMilli(it) }

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
        val UPSERT_SANCTION = """
            INSERT INTO sanctions (kind, scope, target, reason, expires_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(kind, scope, target) DO UPDATE SET reason = excluded.reason, expires_at = excluded.expires_at
        """.trimIndent()
        val UPSERT_DESTINATION = """
            INSERT INTO destinations (kind, owner, name, world_id, x, y, z, yaw, pitch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(kind, owner, name) DO UPDATE SET
                world_id = excluded.world_id, x = excluded.x, y = excluded.y, z = excluded.z,
                yaw = excluded.yaw, pitch = excluded.pitch
        """.trimIndent()
    }
}
