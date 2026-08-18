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

class PlayerDataCachePolicyTest {
    @Test
    void loginIsSingleFlightAndLoadPolicyJoinsIt() {
        AtomicInteger loads = new AtomicInteger();
        CompletableFuture<Integer> pending = new CompletableFuture<Integer>();
        PlayerDataCache<Integer> cache = cache(UnloadedPolicy.LOAD_ASYNC, loads, pending);
        UUID playerId = UUID.randomUUID();

        CompletionStage<Integer> first = cache.login(playerId);
        CompletionStage<Integer> second = cache.modify(playerId, value -> value + 1);
        assertEquals(1, loads.get());
        pending.complete(4);
        assertEquals(4, first.toCompletableFuture().join());
        assertEquals(5, second.toCompletableFuture().join());
    }

    @Test
    void failFastAndCreateDefaultHaveDistinctBehavior() {
        UUID playerId = UUID.randomUUID();
        PlayerDataCache<Integer> failFast = cache(UnloadedPolicy.FAIL_FAST, new AtomicInteger(), new CompletableFuture<Integer>());
        assertThrows(CompletionException.class,
                () -> failFast.modify(playerId, value -> value + 1).toCompletableFuture().join());

        AtomicInteger loads = new AtomicInteger();
        PlayerDataCache<Integer> defaults = cache(UnloadedPolicy.CREATE_DEFAULT, loads,
                CompletableFuture.completedFuture(null));
        assertEquals(1, defaults.modify(playerId, value -> value + 1).toCompletableFuture().join());
        assertEquals(1, loads.get());
    }

    private static PlayerDataCache<Integer> cache(
            UnloadedPolicy policy,
            AtomicInteger loads,
            CompletionStage<Integer> load
    ) {
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
        }, () -> 0, policy, 8);
    }
}
