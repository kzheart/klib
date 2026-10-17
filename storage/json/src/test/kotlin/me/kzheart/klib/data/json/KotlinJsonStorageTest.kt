package me.kzheart.klib.data.json

import me.kzheart.klib.data.Migration
import me.kzheart.klib.data.MigrationRunner
import me.kzheart.klib.data.Schema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionException

class KotlinJsonStorageTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `Kotlin SAM migrations and transactions survive reopen`() {
        val file = directory.resolve("store.json")
        val provider = JsonStorageProvider(file)
        val session = provider.open().toCompletableFuture().join()
        val migration = Migration(1) { it.put("plugin", "ready", byteArrayOf(7)) }
        assertEquals(1, MigrationRunner.apply(session, Schema("plugin", listOf(migration))).toCompletableFuture().join())
        provider.dispose()
        val reopened = JsonStorageProvider(file)
        try {
            val restored = reopened.open().toCompletableFuture().join()
            assertArrayEquals(byteArrayOf(7), restored.get("plugin", "ready").toCompletableFuture().join().get())
        } finally { reopened.dispose() }
    }

    @Test
    fun `nullable Gson fields normalize but malformed nested state fails`() {
        val empty = directory.resolve("empty.json")
        Files.write(empty, "{\"schemas\":null,\"values\":null}".toByteArray())
        val provider = JsonStorageProvider(empty)
        try { assertTrue(provider.open().toCompletableFuture().join().entries("plugin").toCompletableFuture().join().isEmpty()) }
        finally { provider.dispose() }
        val invalid = directory.resolve("invalid.json")
        Files.write(invalid, "{\"values\":{\"plugin\":null}}".toByteArray())
        val malformed = JsonStorageProvider(invalid)
        try { assertThrows(CompletionException::class.java) { malformed.open().toCompletableFuture().join() } }
        finally { malformed.dispose() }
    }
}
