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
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SQLiteMigrationRollbackTest {
    @TempDir
    Path directory;

    @Test
    void failedMigrationRollsBackWritesAndVersion() throws Exception {
        StorageProvider provider = new SQLiteStorageProvider(directory.resolve("rollback.sqlite"));
        StorageSession session = provider.open().toCompletableFuture().get();
        try {
            Schema schema = new Schema("broken", Arrays.asList(new Migration(1, context -> {
                context.put("migration", "partial", "bad".getBytes(StandardCharsets.UTF_8));
                throw new IllegalStateException("boom");
            })));

            assertThrows(ExecutionException.class,
                    () -> MigrationRunner.apply(session, schema).toCompletableFuture().get());
            assertFalse(session.get("migration", "partial").toCompletableFuture().get().isPresent());
            assertEquals(0, session.transaction(context -> context.schemaVersion("broken"))
                    .toCompletableFuture().get());
        } finally {
            session.dispose();
            provider.dispose();
        }
    }
}
