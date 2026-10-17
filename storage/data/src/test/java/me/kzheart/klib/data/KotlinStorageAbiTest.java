package me.kzheart.klib.data;

import me.kzheart.klib.data.cache.DataCodec;
import me.kzheart.klib.data.cache.KeyValuePlayerDataRepository;
import me.kzheart.klib.data.cache.PlayerDataRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class KotlinStorageAbiTest {
    @Test
    void javaCallersKeepStaticMigrationEntryAndCheckedSam() throws Exception {
        assertTrue(Modifier.isStatic(MigrationRunner.class.getMethod("apply", StorageSession.class, Schema.class).getModifiers()));
        assertEquals(Arrays.asList(Exception.class), Arrays.asList(
                Migration.MigrationAction.class.getMethod("apply", TransactionContext.class).getExceptionTypes()));
        assertTrue(TransactionContext.class.getMethod("compareAndSetSchemaVersion", String.class, int.class, int.class).isDefault());
        Migration migration = new Migration(3, context -> { throw new Exception("checked"); });
        assertEquals(3, new Schema("java", Collections.singletonList(migration)).version());
    }

    @Test
    void invariantGenericJavaImplementationsCompileAndRoundTrip() {
        MemorySession session = new MemorySession();
        DataCodec<String> codec = new DataCodec<String>() {
            @Override public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
            @Override public String decode(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
        };
        PlayerDataRepository<String> repository = new KeyValuePlayerDataRepository<>(session, "players", codec, () -> "default");
        UUID player = UUID.randomUUID();
        assertEquals("default", repository.load(player).toCompletableFuture().join());
        repository.save(player, "java").toCompletableFuture().join();
        assertEquals("java", repository.load(player).toCompletableFuture().join());
    }

    private static final class MemorySession implements StorageSession {
        private byte[] value;
        @Override public CompletionStage<Optional<byte[]>> get(String namespace, String key) {
            return CompletableFuture.completedFuture(Optional.ofNullable(value));
        }
        @Override public CompletionStage<Void> put(String namespace, String key, byte[] value) {
            this.value = value;
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> delete(String namespace, String key) {
            value = null;
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Map<String, byte[]>> entries(String namespace) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }
        @Override public <T> CompletionStage<T> transaction(StorageTransaction<T> transaction) {
            throw new UnsupportedOperationException();
        }
        @Override public void dispose() { }
    }
}
