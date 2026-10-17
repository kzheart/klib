package me.kzheart.klib.data.postgresql

import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.kzheart.klib.data.jdbc.JdbcDataSources
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "KLIB_POSTGRESQL_TEST_URL", matches = ".+")
class ReadyClosePostgreSqlTest {
    @Test fun readyContinuationCannotWaitForReceiverToCloseItself() {
        for (wait in 0..2) {
            val connect = CountDownLatch(1)
            val properties = Properties().apply {
                System.getenv("KLIB_POSTGRESQL_TEST_USER")?.let { setProperty("user", it) }
                System.getenv("KLIB_POSTGRESQL_TEST_PASSWORD")?.let { setProperty("password", it) }
            }
            val source = JdbcDataSources.driver("org.postgresql.Driver", javaClass.classLoader,
                System.getenv("KLIB_POSTGRESQL_TEST_URL"), properties)
            val session = PostgreSqlListenSession({
                assertTrue(connect.await(5, TimeUnit.SECONDS))
                source.connection
            }, setOf("klib_ready_close"), PostgreSqlListenOptions.defaults(), PostgreSqlNotificationListener { })
            val continuation = session.ready().thenRun {
                assertTrue(Thread.currentThread().name.endsWith("-receive"))
                val closing = session.closeAsync().toCompletableFuture()
                assertThrows(IllegalStateException::class.java) {
                    when (wait) {
                        0 -> closing.join()
                        1 -> closing.get()
                        else -> closing.get(5, TimeUnit.SECONDS)
                    }
                }
            }
            try {
                session.start()
                connect.countDown()
                continuation.toCompletableFuture().get(10, TimeUnit.SECONDS)
                session.completion().toCompletableFuture().get(10, TimeUnit.SECONDS)
            } finally {
                connect.countDown()
                session.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS)
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        fun threads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith("klib-pg-listen-") }
        while (threads() > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(0, threads())
    }
}
