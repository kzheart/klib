package me.kzheart.klib.data;

import me.kzheart.klib.data.cache.PlayerDataCache;
import me.kzheart.klib.data.cache.PlayerDataRepository;
import me.kzheart.klib.data.cache.UnloadedPolicy;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirtyFlushRetryTest {
    @Test
    void failedFlushRemainsDirtyAndRetriesLater() {
        AtomicInteger attempts = new AtomicInteger();
        PlayerDataRepository<Integer> repository = new PlayerDataRepository<Integer>() {
            @Override
            public CompletionStage<Integer> load(UUID playerId) {
                return CompletableFuture.completedFuture(0);
            }

            @Override
            public CompletionStage<Void> save(UUID playerId, Integer value) {
                if (attempts.incrementAndGet() == 1) {
                    CompletableFuture<Void> failed = new CompletableFuture<Void>();
                    failed.completeExceptionally(new IllegalStateException("temporary outage"));
                    return failed;
                }
                assertEquals(1, value);
                return CompletableFuture.completedFuture(null);
            }
        };
        PlayerDataCache<Integer> cache = new PlayerDataCache<Integer>(repository, () -> 0, UnloadedPolicy.CREATE_DEFAULT, 8);
        UUID playerId = UUID.randomUUID();

        cache.modify(playerId, value -> value + 1).toCompletableFuture().join();
        assertThrows(CompletionException.class, () -> cache.flushDirty().toCompletableFuture().join());
        cache.flushDirty().toCompletableFuture().join();

        assertEquals(2, attempts.get());
    }
}
