package me.kzheart.klib.data.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonOpenFailureTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void invalidJsonDoesNotLeakExecutorThread() throws Exception {
        Path file = temporaryDirectory.resolve("invalid.json");
        Files.write(file, "{broken".getBytes(StandardCharsets.UTF_8));
        int threadsBefore = jsonStorageThreads();
        JsonStorageProvider provider = new JsonStorageProvider(file);

        assertThrows(ExecutionException.class, () -> provider.open().toCompletableFuture().get());

        long deadline = System.nanoTime() + 2_000_000_000L;
        while (jsonStorageThreads() != threadsBefore && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(threadsBefore, jsonStorageThreads());
    }

    private static int jsonStorageThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && "klib-json-storage".equals(thread.getName())) {
                count++;
            }
        }
        return count;
    }
}
