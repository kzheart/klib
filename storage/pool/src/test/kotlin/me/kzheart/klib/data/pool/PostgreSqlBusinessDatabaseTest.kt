package me.kzheart.klib.data.pool

import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.kzheart.klib.data.jdbc.JdbcTransactionException
import me.kzheart.klib.data.jdbc.*
import me.kzheart.klib.scope.ScopeImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "KLIB_POSTGRESQL_TEST_URL", matches = ".+")
class PostgreSqlBusinessDatabaseTest {
    @Test fun `transaction timeouts reset after commit and failed schema rolls back and unlocks`() = runBlocking {
        val scope = ScopeImpl("business-pg")
        val db = database(scope)
        try {
            db.transaction(SqlWork { sql ->
                assertEquals("1234ms", sql.queryOne("SHOW statement_timeout") { it.string("statement_timeout") })
                assertEquals("2345ms", sql.queryOne("SHOW lock_timeout") { it.string("lock_timeout") })
                assertEquals("3456ms", sql.queryOne("SHOW idle_in_transaction_session_timeout") { it.string("idle_in_transaction_session_timeout") })
            })
            assertEquals("0", db.read(SqlWork { it.queryOne("SHOW statement_timeout") { row -> row.string("statement_timeout") } }))
            val key = "klib-test-${UUID.randomUUID()}"
            try {
                db.migrate(key, SqlWork { sql ->
                    assertEquals("1min", sql.queryOne("SHOW statement_timeout") { it.string("statement_timeout") })
                    sql.execute("SELECT absent_test_column")
                })
                fail<Unit>("invalid schema succeeded")
            } catch (failure: JdbcTransactionException) {
                assertEquals(JdbcTransactionException.Outcome.ROLLED_BACK, failure.outcome())
            }
            assertEquals(true, db.transaction(SqlWork { it.queryOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS locked", key) { row -> row.boolean("locked") } }))
        } finally { scope.close() }
    }

    @Test fun `schema lock excludes another live transaction and is released on commit`() = runBlocking {
        val scope = ScopeImpl("schema-pg")
        val first = database(scope)
        val second = database(scope)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val key = "klib-lock-${UUID.randomUUID()}"
        try {
            val running = async(Dispatchers.Default) {
                first.migrate(key, SqlWork {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                })
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals(false, second.transaction(SqlWork { it.queryOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS locked", key) { row -> row.boolean("locked") } }))
            release.countDown()
            running.await()
            assertEquals(true, second.transaction(SqlWork { it.queryOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS locked", key) { row -> row.boolean("locked") } }))
        } finally { release.countDown(); scope.close() }
    }

    private fun database(scope: ScopeImpl) = PostgreSqlDatabase(scope, PostgreSqlDatabaseOptions(
        System.getenv("KLIB_POSTGRESQL_TEST_URL"), System.getenv("KLIB_POSTGRESQL_TEST_USER") ?: "",
        System.getenv("KLIB_POSTGRESQL_TEST_PASSWORD") ?: "", "klib-business-test", workers = 1,
        statementTimeout = Duration.ofMillis(1234), lockTimeout = Duration.ofMillis(2345),
        idleTransactionTimeout = Duration.ofMillis(3456),
    ), javaClass.classLoader)
}
