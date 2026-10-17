package me.kzheart.klib.data;

import me.kzheart.klib.data.cache.PlayerDataCache;
import me.kzheart.klib.data.cache.PlayerDataRepository;
import me.kzheart.klib.data.cache.UnloadedPolicy;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlayerQuitDuringLoadTest {
    @Test
    void quitJoinsLoadThenSavesAndEvicts() {
        UUID playerId = UUID.randomUUID();
        CompletableFuture<String> load = new CompletableFuture<String>();
        AtomicInteger saves = new AtomicInteger();
        PlayerDataRepository<String> repository = new PlayerDataRepository<String>() {
            @Override
            public CompletionStage<String> load(UUID ignored) {
                return load;
            }

            @Override
            public CompletionStage<Void> save(UUID ignored, String value) {
                assertEquals("loaded", value);
                saves.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        };
        PlayerDataCache<String> cache = new PlayerDataCache<String>(repository, () -> "default", UnloadedPolicy.LOAD_ASYNC, 16);

        CompletionStage<String> login = cache.login(playerId);
        CompletionStage<Void> quit = cache.quit(playerId);
        load.complete("loaded");

        assertEquals("loaded", login.toCompletableFuture().join());
        quit.toCompletableFuture().join();
        assertEquals(1, saves.get());
        assertEquals(0, cache.loadedCount());
        assertFalse(cache.findLoaded(playerId).isPresent());
    }
}
