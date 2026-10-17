package me.kzheart.klib.data;

import me.kzheart.klib.data.cache.PlayerDataCache;
import me.kzheart.klib.data.cache.PlayerDataRepository;
import me.kzheart.klib.data.cache.UnloadedPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 关闭时必须持久化全部脏条目，而不只是一个批次。 */
@Timeout(5)
class PlayerDataCacheDisposeTest {
    @Test
    void disposeFlushesAllDirtyEntriesAcrossBatches() {
        Map<UUID, Integer> saved = new HashMap<UUID, Integer>();
        PlayerDataRepository<Integer> repository = new PlayerDataRepository<Integer>() {
            @Override
            public CompletionStage<Integer> load(UUID playerId) {
                return CompletableFuture.completedFuture(0);
            }

            @Override
            public CompletionStage<Void> save(UUID playerId, Integer value) {
                saved.put(playerId, value);
                return CompletableFuture.completedFuture(null);
            }
        };
        PlayerDataCache<Integer> cache = new PlayerDataCache<Integer>(repository, () -> 0, UnloadedPolicy.LOAD_ASYNC, 2);
        List<UUID> players = new ArrayList<UUID>();
        for (int index = 0; index < 5; index++) {
            UUID playerId = UUID.randomUUID();
            players.add(playerId);
            cache.login(playerId).toCompletableFuture().join();
            int expected = index + 1;
            cache.modify(playerId, value -> expected).toCompletableFuture().join();
        }

        cache.dispose();

        assertEquals(5, saved.size());
        for (int index = 0; index < players.size(); index++) {
            assertEquals(index + 1, saved.get(players.get(index)));
        }
        assertThrows(IllegalStateException.class, () -> cache.modify(players.get(0), value -> value));
    }

    @Test
    void closeWaitsForInFlightSavesAndReflushesAdvancedVersions() {
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
        PlayerDataCache<Integer> cache = new PlayerDataCache<Integer>(repository, () -> 0, UnloadedPolicy.LOAD_ASYNC, 8);
        UUID playerId = UUID.randomUUID();
        cache.login(playerId).toCompletableFuture().join();
        cache.modify(playerId, value -> 1).toCompletableFuture().join();
        cache.flushDirty();
        // 第一次保存仍在进行时推进版本。
        cache.modify(playerId, value -> 2).toCompletableFuture().join();

        CompletableFuture<Void> close = cache.close().toCompletableFuture();
        assertFalse(close.isDone());

        pendingSaves.get(0).complete(null);
        assertEquals(2, savedValues.size());
        assertFalse(close.isDone());

        pendingSaves.get(1).complete(null);
        close.join();
        assertEquals(2, savedValues.get(1));
    }

    @Test
    void closeFailsInsteadOfHangingWhenSavesKeepFailing() {
        PlayerDataRepository<Integer> repository = new PlayerDataRepository<Integer>() {
            @Override
            public CompletionStage<Integer> load(UUID playerId) {
                return CompletableFuture.completedFuture(0);
            }

            @Override
            public CompletionStage<Void> save(UUID playerId, Integer value) {
                CompletableFuture<Void> failed = new CompletableFuture<Void>();
                failed.completeExceptionally(new IllegalStateException("database offline"));
                return failed;
            }
        };
        PlayerDataCache<Integer> cache = new PlayerDataCache<Integer>(repository, () -> 0, UnloadedPolicy.LOAD_ASYNC, 8);
        UUID playerId = UUID.randomUUID();
        cache.login(playerId).toCompletableFuture().join();
        cache.modify(playerId, value -> 1).toCompletableFuture().join();

        CompletableFuture<Void> close = cache.close().toCompletableFuture();
        assertTrue(close.isCompletedExceptionally());
    }

    @Test
    void closeWaitsForPendingLoadAndTheAcceptedMutation() {
        CompletableFuture<Integer> pendingLoad = new CompletableFuture<Integer>();
        Map<UUID, Integer> saved = new HashMap<UUID, Integer>();
        PlayerDataRepository<Integer> repository = new PlayerDataRepository<Integer>() {
            @Override
            public CompletionStage<Integer> load(UUID playerId) {
                return pendingLoad;
            }

            @Override
            public CompletionStage<Void> save(UUID playerId, Integer value) {
                saved.put(playerId, value);
                return CompletableFuture.completedFuture(null);
            }
        };
        PlayerDataCache<Integer> cache = new PlayerDataCache<Integer>(
                repository, () -> 0, UnloadedPolicy.LOAD_ASYNC, 8);
        UUID playerId = UUID.randomUUID();

        CompletableFuture<Integer> mutation = cache.modify(playerId, value -> value + 1)
                .toCompletableFuture();
        CompletableFuture<Void> close = cache.close().toCompletableFuture();
        assertFalse(close.isDone());

        pendingLoad.complete(41);

        assertEquals(42, mutation.join());
        close.join();
        assertEquals(42, saved.get(playerId));
    }

    @Test
    void interruptedDisposeReportsFailureInsteadOfReturningSuccessfully() throws Exception {
        CompletableFuture<Void> pendingSave = new CompletableFuture<Void>();
        CountDownLatch saveStarted = new CountDownLatch(1);
        PlayerDataRepository<Integer> repository = new PlayerDataRepository<Integer>() {
            @Override
            public CompletionStage<Integer> load(UUID playerId) {
                return CompletableFuture.completedFuture(0);
            }

            @Override
            public CompletionStage<Void> save(UUID playerId, Integer value) {
                saveStarted.countDown();
                return pendingSave;
            }
        };
        PlayerDataCache<Integer> cache = new PlayerDataCache<Integer>(
                repository, () -> 0, UnloadedPolicy.LOAD_ASYNC, 8);
        UUID playerId = UUID.randomUUID();
        cache.login(playerId).toCompletableFuture().join();
        cache.modify(playerId, value -> 1).toCompletableFuture().join();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread disposer = new Thread(() -> {
            try {
                cache.dispose();
            } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "player-data-dispose-test");

        disposer.start();
        assertTrue(saveStarted.await(1, TimeUnit.SECONDS));
        disposer.interrupt();
        disposer.join(1000);

        assertFalse(disposer.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException);
        assertTrue(interrupted.get());
        pendingSave.complete(null);
    }
}
