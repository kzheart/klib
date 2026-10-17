package me.kzheart.klib.data;

import me.kzheart.klib.data.cache.PlayerDataCache;
import me.kzheart.klib.data.cache.PlayerDataRepository;
import me.kzheart.klib.data.cache.UnloadedPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlayerQuitDuringFlushTest {
    @Test
    void quitWaitsForFlushThenPersistsLatestVersionBeforeEviction() {
        List<Integer> savedValues = new ArrayList<Integer>();
        List<CompletableFuture<Void>> pendingSaves = new ArrayList<CompletableFuture<Void>>();
        PlayerDataRepository<Integer> repository = new PlayerDataRepository<Integer>() {
            @Override
            public CompletionStage<Integer> load(UUID playerId) {
                return CompletableFuture.completedFuture(0);
            }

            @Override
            public CompletionStage<Void> save(UUID playerId, Integer value) {
                savedValues.add(value);
                CompletableFuture<Void> pending = new CompletableFuture<Void>();
                pendingSaves.add(pending);
                return pending;
            }
        };
        PlayerDataCache<Integer> cache = new PlayerDataCache<Integer>(repository, () -> 0, UnloadedPolicy.CREATE_DEFAULT, 8);
        UUID playerId = UUID.randomUUID();

        cache.modify(playerId, value -> 1).toCompletableFuture().join();
        CompletionStage<Void> flush = cache.flushDirty();
        cache.modify(playerId, value -> 2).toCompletableFuture().join();
        CompletionStage<Void> quit = cache.quit(playerId);

        assertEquals(1, savedValues.size());
        assertEquals(1, savedValues.get(0));
        assertFalse(quit.toCompletableFuture().isDone());

        pendingSaves.get(0).complete(null);
        flush.toCompletableFuture().join();
        assertEquals(2, savedValues.size());
        assertEquals(2, savedValues.get(1));
        assertFalse(quit.toCompletableFuture().isDone());

        pendingSaves.get(1).complete(null);
        quit.toCompletableFuture().join();
        assertEquals(0, cache.loadedCount());
    }
}
