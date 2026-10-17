package me.kzheart.klib.data.postgresql

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.kzheart.klib.data.*
import me.kzheart.klib.data.cache.DataCodec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "KLIB_POSTGRESQL_TEST_URL", matches = ".+")
class TypedKeyValuePostgreSqlTest {
    @Test fun `typed values share actual KV transactions and reject escaped views`() = runBlocking {
        val provider = PostgreSqlStorageProvider(System.getenv("KLIB_POSTGRESQL_TEST_URL"), System.getenv("KLIB_POSTGRESQL_TEST_USER"), System.getenv("KLIB_POSTGRESQL_TEST_PASSWORD"))
        try {
            provider.withSession {
                val values = typed("typed-" + UUID.randomUUID(), object : DataCodec<String> {
                    override fun encode(value: String) = value.toByteArray(StandardCharsets.UTF_8)
                    override fun decode(value: ByteArray) = String(value, StandardCharsets.UTF_8)
                })
                assertNull(values.getAwait("absent"))
                values.putAwait("key", "中文😀")
                assertEquals("中文😀", values.getAwait("key"))
                var escaped: TypedKeyValueView<String>? = null
                values.transaction { escaped = this; put("key", "committed"); put("other", "yes") }
                assertThrows(IllegalStateException::class.java) { escaped!!.get("key") }
                assertEquals(mapOf("key" to "committed", "other" to "yes"), values.entriesAwait())
                assertThrows(Exception::class.java) { runBlocking { values.transaction { put("key", "rollback"); error("rollback") } } }
                assertEquals("committed", values.getAwait("key"))
                values.deleteAwait("key"); values.deleteAwait("other")
                assertTrue(values.entriesAwait().isEmpty())
            }
        } finally { provider.dispose() }
    }
}
