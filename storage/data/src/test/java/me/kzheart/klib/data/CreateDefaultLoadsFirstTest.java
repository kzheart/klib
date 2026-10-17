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

/** CREATE_DEFAULT 必须先读取数据，绝不能直接覆盖已有存储。 */
class CreateDefaultLoadsFirstTest {
    @Test
    void modifyLoadsExistingDataInsteadOfSilentlyOverwriting() {
        AtomicInteger loads = new AtomicInteger();
        PlayerDataCache<Integer> cache = cache(loads, CompletableFuture.completedFuture(7));

        Integer result = cache.modify(UUID.randomUUID(), value -> value + 1).toCompletableFuture().join();

        assertEquals(8, result);
        assertEquals(1, loads.get());
    }

    @Test
    void modifyFallsBackToDefaultsOnlyWhenLoadReturnsEmpty() {
        AtomicInteger loads = new AtomicInteger();
        PlayerDataCache<Integer> cache = cache(loads, CompletableFuture.completedFuture(null));

        Integer result = cache.modify(UUID.randomUUID(), value -> value + 1).toCompletableFuture().join();

        assertEquals(1, result);
        assertEquals(1, loads.get());
    }

    @Test
    void mutationIsAppliedOnlyAfterThePendingLoadCompletes() {
        AtomicInteger loads = new AtomicInteger();
        CompletableFuture<Integer> pending = new CompletableFuture<Integer>();
        PlayerDataCache<Integer> cache = cache(loads, pending);

        CompletableFuture<Integer> modified =
                cache.modify(UUID.randomUUID(), value -> value + 1).toCompletableFuture();
        assertFalse(modified.isDone());

        pending.complete(41);
        assertEquals(42, modified.join());
        assertEquals(1, loads.get());
    }

    private static PlayerDataCache<Integer> cache(AtomicInteger loads, CompletionStage<Integer> load) {
        return new PlayerDataCache<Integer>(new PlayerDataRepository<Integer>() {
            @Override
            public CompletionStage<Integer> load(UUID playerId) {
                loads.incrementAndGet();
                return load;
            }

            @Override
            public CompletionStage<Void> save(UUID playerId, Integer value) {
                return CompletableFuture.completedFuture(null);
            }
        }, () -> 0, UnloadedPolicy.CREATE_DEFAULT, 8);
    }
}
