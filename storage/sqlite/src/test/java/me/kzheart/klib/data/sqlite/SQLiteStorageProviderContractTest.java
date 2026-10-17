package me.kzheart.klib.data.sqlite;

import me.kzheart.klib.data.Migration;
import me.kzheart.klib.data.MigrationRunner;
import me.kzheart.klib.data.Schema;
import me.kzheart.klib.data.StorageProvider;
import me.kzheart.klib.data.StorageSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteStorageProviderContractTest {
    @TempDir
    Path directory;

    @Test
    void honorsKvAndMigrationContract() throws Exception {
        StorageProvider provider = new SQLiteStorageProvider(directory.resolve("contract.sqlite"));
        StorageSession session = provider.open().toCompletableFuture().get(5L, TimeUnit.SECONDS);
        try {
            String callerThread = Thread.currentThread().getName();
            AtomicReference<String> storageThread = new AtomicReference<String>();
            session.transaction(context -> {
                storageThread.set(Thread.currentThread().getName());
                return null;
            }).toCompletableFuture().get();
            assertFalse(callerThread.equals(storageThread.get()));

            byte[] original = bytes("first");
            session.put("players", "alice", original).toCompletableFuture().get();
            Arrays.fill(original, (byte) 0);
            assertArrayEquals(bytes("first"), session.get("players", "alice")
                    .toCompletableFuture().get().get());

            session.put("players", "alice", bytes("updated")).toCompletableFuture().get();
            session.put("players", "bob", bytes("second")).toCompletableFuture().get();
            Map<String, byte[]> entries = session.entries("players").toCompletableFuture().get();
            assertEquals(2, entries.size());
            assertArrayEquals(bytes("updated"), entries.get("alice"));

            Schema schema = new Schema("contract", Arrays.asList(
                    new Migration(1, context -> context.put("meta", "one", bytes("1"))),
                    new Migration(2, context -> context.put("meta", "two", bytes("2")))
            ));
            assertEquals(2, MigrationRunner.apply(session, schema).toCompletableFuture().get());
            assertEquals(2, MigrationRunner.apply(session, schema).toCompletableFuture().get());
            assertTrue(session.get("meta", "two").toCompletableFuture().get().isPresent());

            session.delete("players", "alice").toCompletableFuture().get();
            assertFalse(session.get("players", "alice").toCompletableFuture().get().isPresent());
        } finally {
            session.dispose();
            provider.dispose();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
