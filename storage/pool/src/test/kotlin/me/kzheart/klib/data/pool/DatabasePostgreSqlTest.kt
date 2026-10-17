package me.kzheart.klib.data.pool

import java.math.BigDecimal
import java.sql.Connection
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.future.await
import me.kzheart.klib.data.jdbc.*
import me.kzheart.klib.scope.ScopeImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "KLIB_POSTGRESQL_TEST_URL", matches = ".+")
class DatabasePostgreSqlTest {
    @Test fun `pooled Kotlin SQL maps UUID instant decimal bytes null and zero row cardinality`() = runBlocking {
        val scope = ScopeImpl("pg-pool")
        val source = JdbcDataSources.driver("org.postgresql.Driver", javaClass.classLoader, System.getenv("KLIB_POSTGRESQL_TEST_URL"), properties())
        val db = Databases.openAwait(scope, DatabaseConfig.builder(JdbcPools.dbcp(source, JdbcPoolOptions(2))).build())
        try {
            val id = UUID.randomUUID()
            val time = Instant.parse("2026-10-17T01:02:03.123456Z")
            val row = db.read {
                queryOne("SELECT ?::uuid AS id, ?::timestamptz AS at, ?::numeric AS money, ?::bytea AS bytes, ?::text AS note, NULL::integer AS n",
                    id, time, BigDecimal("12.3400"), byteArrayOf(0, -1, 42), SqlValue.nullOf(Types.VARCHAR)) {
                    Profile(it.uuid("id"), it.instant("at"), it.decimal("money"), it.bytes("bytes"), it.stringOrNull("note"), it.intOrNull("n"))
                }
            }!!
            assertEquals(id, row.id); assertEquals(time, row.time); assertEquals(0, BigDecimal("12.3400").compareTo(row.money))
            assertArrayEquals(byteArrayOf(0, -1, 42), row.bytes); assertNull(row.note); assertNull(row.number)
            assertNull(db.read { queryOne("SELECT 1 AS n WHERE false") { it.int("n") } })
            assertEquals(listOf(1, 2), db.read { query("SELECT 1 AS n UNION ALL SELECT 2") { it.int("n") } })
            assertThrows(Exception::class.java) { runBlocking { db.read { queryOne("SELECT 1 AS n UNION ALL SELECT 2") { it.int("n") } } } }
            assertThrows(Exception::class.java) { runBlocking { db.read { queryOne("SELECT NULL AS n") { it.int("n") } } } }
            // read 仍是自动提交便利入口，不伪称权限隔离。
            db.transaction {
                val connection = connection()
                assertFalse(connection.autoCommit)
                execute("CREATE TEMP TABLE klib_typed_test (id UUID PRIMARY KEY, note TEXT) ON COMMIT DROP")
                assertEquals(1, execute("INSERT INTO klib_typed_test VALUES (?, ?)", id, "quoted' value"))
                assertEquals("quoted' value", queryOne("SELECT note FROM klib_typed_test WHERE id = ?", id) { it.string("note") })
                assertSame(connection, connection())
            }
            val failure = assertThrows(JdbcTransactionException::class.java) { runBlocking { db.transaction { execute("SELECT broken_column") } } }
            assertEquals(JdbcTransactionException.Outcome.ROLLED_BACK, failure.outcome())
            db.transaction(SqlTransactionOptions.defaults().withReadOnly(true).withIsolation(Connection.TRANSACTION_REPEATABLE_READ)) {
                assertTrue(connection().isReadOnly)
            }
        } finally { db.closeAsync().await(); scope.close() }
    }

    @Test fun `Java stages use same typed mapper and pool actually reuses physical connection`() {
        val scope = ScopeImpl("java-pool")
        val source = JdbcDataSources.driver("org.postgresql.Driver", javaClass.classLoader, System.getenv("KLIB_POSTGRESQL_TEST_URL"), properties())
        val db = Databases.open(scope, DatabaseConfig.builder(JdbcPools.dbcp(source, JdbcPoolOptions(1, Duration.ofSeconds(2)))).build()).toCompletableFuture().get(10, TimeUnit.SECONDS)
        var backend = 0
        try {
            val first = db.readAsync { it.queryOne("SELECT pg_backend_pid() AS pid", SqlArguments.of(), SqlRows.mapper { row -> row.int("pid") }).get() }.toCompletableFuture().get()
            val second = db.readAsync { it.queryOne("SELECT pg_backend_pid() AS pid", SqlArguments.of(), SqlRows.mapper { row -> row.int("pid") }).get() }.toCompletableFuture().get()
            assertEquals(first, second)
            backend = first
        } finally { db.dispose(); scope.close() }
        source.connection.use { control ->
            control.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE pid = ?").use { statement ->
                statement.setInt(1, backend)
                statement.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
            }
        }
    }

    @Test fun `query timeout rolls back and executor stays usable`() = runBlocking {
        val scope = ScopeImpl("timeout")
        val config = DatabaseConfig.driver("org.postgresql.Driver", javaClass.classLoader, System.getenv("KLIB_POSTGRESQL_TEST_URL"), properties())
            .statementTimeout(Duration.ofSeconds(1)).build()
        val db = Databases.openAwait(scope, config)
        try {
            val failure = assertThrows(JdbcTransactionException::class.java) {
                runBlocking { db.transaction { queryOne("SELECT 1 AS n FROM pg_sleep(3)") { it.int("n") } } }
            }
            assertEquals(JdbcTransactionException.Outcome.ROLLED_BACK, failure.outcome())
            assertEquals(1, db.read { queryOne("SELECT 1 AS n") { it.int("n") } })
        } finally { db.closeAsync().await(); scope.close() }
    }

    @Test fun `Kotlin opening cancellation closes a source acquired after cancellation`() = runBlocking {
        val scope = ScopeImpl("cancel-open")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = AtomicInteger()
        val source = JdbcDataSources.driver("org.postgresql.Driver", javaClass.classLoader, System.getenv("KLIB_POSTGRESQL_TEST_URL"), properties())
        val config = DatabaseConfig.builder {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
            JdbcSource.owned(source) { closed.incrementAndGet() }
        }.build()
        val job = launch(Dispatchers.Default) { Databases.openAwait(scope, config); fail("cancelled open returned") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); job.cancelAndJoin()
        } finally { release.countDown(); scope.close() }
        assertEquals(1, closed.get())
    }
    private fun properties() = Properties().apply {
        System.getenv("KLIB_POSTGRESQL_TEST_USER")?.let { setProperty("user", it) }
        System.getenv("KLIB_POSTGRESQL_TEST_PASSWORD")?.let { setProperty("password", it) }
        setProperty("connectTimeout", "3"); setProperty("socketTimeout", "5")
    }
    data class Profile(val id: UUID, val time: Instant, val money: BigDecimal, val bytes: ByteArray, val note: String?, val number: Int?)
}
