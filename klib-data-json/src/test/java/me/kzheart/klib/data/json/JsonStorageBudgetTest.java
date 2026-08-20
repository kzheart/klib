package me.kzheart.klib.data.json;

import me.kzheart.klib.data.StorageSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonStorageBudgetTest {
    @TempDir Path directory;

    @Test
    void oversizedFileAndDeepJsonAreRejected() throws Exception {
        Path oversized = directory.resolve("oversized.json");
        try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(oversized.toFile(), "rw")) {
            output.setLength(8L * 1024L * 1024L + 1L);
        }
        assertOpenFails(oversized);

        Path deep = directory.resolve("deep.json");
        StringBuilder json = new StringBuilder();
        for (int index = 0; index < 17; index++) json.append("{\"x\":");
        json.append("null");
        for (int index = 0; index < 17; index++) json.append('}');
        Files.write(deep, json.toString().getBytes(StandardCharsets.UTF_8));
        assertOpenFails(deep);
    }

    @Test
    void oversizedNameAndValueAreRejectedWithoutPersistingCandidate() throws Exception {
        Path file = directory.resolve("data.json");
        JsonStorageProvider provider = new JsonStorageProvider(file);
        StorageSession session = provider.open().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            assertThrows(ExecutionException.class, () -> session.put(
                    repeat('n', 129), "key", new byte[]{1})
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> session.put(
                    "namespace", "key", new byte[1024 * 1024 + 1])
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertFalse(session.get("namespace", "key").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).isPresent());
        } finally {
            session.dispose();
            provider.dispose();
        }
    }

    @Test
    void persistedValueBeyondBudgetIsRejectedBeforeBase64Decode() throws Exception {
        Path file = directory.resolve("value.json");
        String encoded = repeat('A', ((1024 * 1024 + 2) / 3) * 4 + 1);
        Files.write(file, ("{\"schemas\":{},\"values\":{\"ns\":{\"key\":\""
                + encoded + "\"}}}").getBytes(StandardCharsets.UTF_8));
        assertOpenFails(file);
    }

    private static void assertOpenFails(Path file) throws Exception {
        JsonStorageProvider provider = new JsonStorageProvider(file);
        try {
            assertThrows(ExecutionException.class,
                    () -> provider.open().toCompletableFuture().get(5, TimeUnit.SECONDS));
        } finally {
            provider.dispose();
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
