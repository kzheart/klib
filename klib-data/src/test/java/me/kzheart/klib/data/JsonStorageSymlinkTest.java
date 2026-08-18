package me.kzheart.klib.data;

import me.kzheart.klib.data.json.JsonStorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class JsonStorageSymlinkTest {
    @TempDir
    Path directory;

    @Test
    void predictableLegacyTemporarySymlinkIsNeverFollowed() throws Exception {
        Path file = directory.resolve("data.json");
        Path victim = directory.resolve("victim.txt");
        byte[] original = "do-not-touch".getBytes(StandardCharsets.UTF_8);
        Files.write(victim, original);
        Files.createSymbolicLink(directory.resolve("data.json.tmp"), victim);

        JsonStorageProvider provider = new JsonStorageProvider(file);
        StorageSession session = provider.open().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            session.put("players", "alice", new byte[]{1})
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertArrayEquals(original, Files.readAllBytes(victim));
        } finally {
            session.dispose();
            provider.dispose();
        }
    }
}
