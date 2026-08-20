package me.kzheart.klib.data.json;

import me.kzheart.klib.data.StorageSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JsonStorageReadOnlyTest {
    @TempDir
    Path directory;

    @Test
    void readsDoNotRewriteTheDatabaseFile() throws Exception {
        Path file = directory.resolve("data.json");
        JsonStorageProvider provider = new JsonStorageProvider(file);
        StorageSession session = provider.open().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            session.put("players", "alice", "value".getBytes(StandardCharsets.UTF_8))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            FileTime marker = FileTime.fromMillis(1_000_000L);
            Files.setLastModifiedTime(file, marker);

            session.get("players", "alice").toCompletableFuture().get(5, TimeUnit.SECONDS);
            session.entries("players").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(marker, Files.getLastModifiedTime(file));
        } finally {
            session.dispose();
            provider.dispose();
        }
    }

    @Test
    void diagnosticSnapshotDoesNotReadDatabaseOrExposeAbsolutePath() {
        JsonStorageProvider provider = new JsonStorageProvider(directory.resolve("state.json"));

        java.util.Map<String, ?> snapshot = provider.diagnosticSnapshot();

        assertEquals("json", snapshot.get("backend"));
        assertEquals("state.json", snapshot.get("file"));
        assertFalse((Boolean) snapshot.get("loaded"));
        provider.dispose();
    }
}
