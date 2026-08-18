package me.kzheart.klib.data.cache;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.scope.Disposable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 带脏版本跟踪的单飞异步玩家数据缓存。
 * 仓库实现仍负责确保数据库 I/O 不在服务器线程上运行。
 *
 * <p>{@code T} 最好是不可变对象：缓存会在持锁时取得值引用的快照，并在同一把锁下调用
 * {@link PlayerDataRepository#save}，因此同步编码的仓库（例如
 * {@link KeyValuePlayerDataRepository}）即使面对可变值也能观察到一致快照。
 * 若仓库将值的读取延迟到其他线程，则除非 {@code T} 不可变，否则不得假定其保持不变。
 */
public final class PlayerDataCache<T> implements Disposable {
    private static final long DISPOSE_TIMEOUT_SECONDS = 30;

    private final Object lock = new Object();
    private final PlayerDataRepository<T> repository;
    private final Supplier<T> defaults;
    private final UnloadedPolicy unloadedPolicy;
    private final int flushBatchSize;
    private final KLogger logger;
    private final Map<UUID, Entry<T>> entries = new LinkedHashMap<UUID, Entry<T>>();
    private final Set<CompletableFuture<Void>> inFlightSaves = new HashSet<CompletableFuture<Void>>();
    private final Set<CompletableFuture<?>> inFlightOperations = new HashSet<CompletableFuture<?>>();
    private UUID flushCursor;
    private boolean disposed;

    /** 不写出日志的构造方式；生产环境建议使用带 {@link KLogger} 的重载。 */
    public PlayerDataCache(
            PlayerDataRepository<T> repository,
            Supplier<T> defaults,
            UnloadedPolicy unloadedPolicy,
            int flushBatchSize
    ) {
        this(repository, defaults, unloadedPolicy, flushBatchSize, null);
    }

    /**
     * 推荐的构造方式：把批量保存失败和关闭时未落盘的数据写入服务端控制台。
     *
     * @param logger 服主可见的日志通道，为 {@code null} 时保持静默
     */
    public PlayerDataCache(
            PlayerDataRepository<T> repository,
            Supplier<T> defaults,
            UnloadedPolicy unloadedPolicy,
            int flushBatchSize,
            KLogger logger
    ) {
        if (repository == null) {
            throw new NullPointerException("repository");
        }
        if (defaults == null) {
            throw new NullPointerException("defaults");
        }
        if (unloadedPolicy == null) {
            throw new NullPointerException("unloadedPolicy");
        }
        if (flushBatchSize <= 0) {
            throw new IllegalArgumentException("flushBatchSize must be positive");
        }
        this.repository = repository;
        this.defaults = defaults;
        this.unloadedPolicy = unloadedPolicy;
        this.flushBatchSize = flushBatchSize;
        this.logger = logger;
    }

    /** 启动或加入该玩家唯一一个正在进行的加载。 */
    public CompletionStage<T> login(UUID playerId) {
        if (playerId == null) {
            throw new NullPointerException("playerId");
        }
        return login(playerId, false);
    }

    private CompletionStage<T> login(UUID playerId, boolean acceptedBeforeClose) {
        Entry<T> created;
        synchronized (lock) {
            if (!acceptedBeforeClose) {
                requireOpen();
            }
            Entry<T> existing = entries.get(playerId);
            if (existing != null) {
                if (!existing.quitting) {
                    return existing.loaded;
                }
                return existing.quit.thenCompose(ignored -> login(playerId));
            }
            created = new Entry<T>();
            entries.put(playerId, created);
            inFlightOperations.add(created.loaded);
        }

        CompletionStage<T> load;
        try {
            load = repository.load(playerId);
        } catch (RuntimeException error) {
            finishLoad(playerId, created, null, error);
            return created.loaded;
        }
        if (load == null) {
            finishLoad(playerId, created, null,
                    new IllegalStateException("repository.load returned null stage for player " + playerId));
            return created.loaded;
        }
        load.whenComplete((value, error) -> finishLoad(playerId, created, value, unwrap(error)));
        return created.loaded;
    }

    public Optional<T> findLoaded(UUID playerId) {
        synchronized (lock) {
            Entry<T> entry = entries.get(playerId);
            return entry == null || !entry.isLoaded() || entry.quitting
                    ? Optional.empty()
                    : Optional.of(entry.value);
        }
    }

    /** 应用修改并将已加载的值标记为脏。 */
    public CompletionStage<T> modify(UUID playerId, UnaryOperator<T> mutation) {
        if (playerId == null) {
            throw new NullPointerException("playerId");
        }
        if (mutation == null) {
            throw new NullPointerException("mutation");
        }
        CompletableFuture<T> result = null;
        synchronized (lock) {
            requireOpen();
            Entry<T> entry = entries.get(playerId);
            if (entry != null && entry.isLoaded() && !entry.quitting) {
                return completed(mutate(entry, mutation));
            }
            if (entry != null && entry.quitting) {
                return failed(new IllegalStateException("player is quitting: " + playerId));
            }
            if (unloadedPolicy == UnloadedPolicy.FAIL_FAST) {
                return failed(new IllegalStateException("player data is not loaded: " + playerId));
            }
            result = new CompletableFuture<T>();
            inFlightOperations.add(result);
        }
        // LOAD_ASYNC 和 CREATE_DEFAULT 都会先加载已存值，确保对未加载数据的修改
        // 不会静默覆盖现有数据；修改仅在加载完成后应用。
        final CompletableFuture<T> tracked = result;
        final CompletionStage<T> load;
        try {
            // 该修改在缓存锁开放时已被接纳。即使 close() 紧接着赢得竞争，
            // 仍必须允许其前置加载完成。
            load = login(playerId, true);
        } catch (RuntimeException failure) {
            completeOperation(tracked, null, failure);
            return tracked;
        }
        load.whenComplete((ignored, loadError) -> {
            T value = null;
            Throwable failure = unwrap(loadError);
            if (failure == null) {
                try {
                    synchronized (lock) {
                        Entry<T> loaded = entries.get(playerId);
                        if (loaded == null || !loaded.isLoaded() || loaded.quitting) {
                            throw new IllegalStateException("player data became unavailable: " + playerId);
                        }
                        value = mutate(loaded, mutation);
                    }
                } catch (Throwable mutationFailure) {
                    failure = mutationFailure;
                }
            }
            completeOperation(tracked, value, failure);
        });
        return tracked;
    }

    /** 轮转遍历条目，每次最多保存配置的批次大小；失败条目仍保持为脏。 */
    public CompletionStage<Void> flushDirty() {
        List<Save<T>> batch = new ArrayList<Save<T>>();
        synchronized (lock) {
            List<UUID> keys = new ArrayList<UUID>(entries.keySet());
            if (!keys.isEmpty()) {
                int start = 0;
                if (flushCursor != null) {
                    int index = keys.indexOf(flushCursor);
                    if (index >= 0) {
                        start = index + 1;
                    }
                }
                for (int offset = 0; offset < keys.size() && batch.size() < flushBatchSize; offset++) {
                    UUID key = keys.get((start + offset) % keys.size());
                    Entry<T> entry = entries.get(key);
                    if (entry.dirty && entry.isLoaded() && !entry.saving && !entry.quitting) {
                        entry.saving = true;
                        batch.add(new Save<T>(key, entry, entry.value, entry.version, false));
                    }
                }
                if (!batch.isEmpty()) {
                    flushCursor = batch.get(batch.size() - 1).playerId;
                }
            }
        }
        if (batch.isEmpty()) {
            return completed(null);
        }
        List<CompletableFuture<Void>> saves = new ArrayList<CompletableFuture<Void>>(batch.size());
        for (Save<T> save : batch) {
            saves.add(save(save));
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(saves.toArray(new CompletableFuture<?>[0]));
        if (logger == null) {
            return all;
        }
        final int batchSize = batch.size();
        return all.whenComplete((ignored, error) -> {
            if (error != null) {
                logger.error("玩家数据批量保存失败（本批 " + batchSize
                        + " 名玩家），数据仍标记为脏并会在下次刷新时重试", unwrap(error));
            }
        });
    }

    /** 保存当前值并将其逐出；若正在加载，则先等待加载完成。 */
    public CompletionStage<Void> quit(UUID playerId) {
        if (playerId == null) {
            throw new NullPointerException("playerId");
        }
        Save<T> save = null;
        CompletableFuture<Void> result;
        synchronized (lock) {
            Entry<T> entry = entries.get(playerId);
            if (entry == null) {
                return completed(null);
            }
            if (entry.quitting) {
                return entry.quit;
            }
            entry.quitting = true;
            result = entry.quit;
            if (entry.isLoaded() && !entry.saving) {
                entry.saving = true;
                save = new Save<T>(playerId, entry, entry.value, entry.version, true);
            }
        }
        if (save != null) {
            save(save);
        }
        return result;
    }

    public int loadedCount() {
        synchronized (lock) {
            int count = 0;
            for (Entry<T> entry : entries.values()) {
                if (entry.isLoaded() && !entry.quitting) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * 拒绝新工作，并持续刷新，直到不存在已接纳的加载/修改、脏条目或进行中的保存。
     * 返回的阶段会在首次持久化保存错误时失败。
     */
    public CompletionStage<Void> close() {
        synchronized (lock) {
            disposed = true;
        }
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        drain(result);
        return result;
    }

    /** 阻塞至 {@link #close()} 完成，确保关闭时不会丢失脏数据。 */
    @Override
    public void dispose() {
        try {
            close().toCompletableFuture().get(DISPOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            warnUnsaved("关闭时被中断");
            throw new IllegalStateException("Interrupted while flushing player data on dispose", interrupted);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (logger != null) {
                logger.error("关闭时保存玩家数据失败，仍有 " + unsavedCount() + " 名玩家的数据未落盘", cause);
            }
            throw new IllegalStateException("Failed to flush player data on dispose", cause);
        } catch (TimeoutException timeout) {
            warnUnsaved("关闭超过 " + DISPOSE_TIMEOUT_SECONDS + " 秒仍未完成");
            throw new IllegalStateException("Timed out flushing player data on dispose", timeout);
        }
    }

    private void warnUnsaved(String reason) {
        if (logger != null) {
            logger.warn("玩家数据落盘未完成（" + reason + "），仍有 " + unsavedCount() + " 名玩家的数据未写入存储");
        }
    }

    /** 统计仍处于脏或保存中的条目，用于关闭阶段告知服主可能丢失的数据量。 */
    private int unsavedCount() {
        synchronized (lock) {
            int count = 0;
            for (Entry<T> entry : entries.values()) {
                if (entry.saving || (entry.dirty && entry.isLoaded())) {
                    count++;
                }
            }
            return count;
        }
    }

    private void drain(CompletableFuture<Void> result) {
        flushDirty().whenComplete((ignored, flushError) -> {
            if (flushError != null) {
                result.completeExceptionally(unwrap(flushError));
                return;
            }
            CompletableFuture<?>[] pending;
            boolean idle;
            synchronized (lock) {
                pending = new CompletableFuture<?>[inFlightSaves.size() + inFlightOperations.size()];
                int index = 0;
                for (CompletableFuture<Void> save : inFlightSaves) {
                    pending[index++] = save;
                }
                for (CompletableFuture<?> operation : inFlightOperations) {
                    pending[index++] = operation;
                }
                idle = pending.length == 0 && !hasPendingWork();
            }
            if (idle) {
                result.complete(null);
                return;
            }
            // 既有加载、修改或保存可能独立失败。待其结束后再次检查缓存：
            // 脏数据保存失败会在下一次刷新中重试，该次刷新失败则由上方逻辑报告。
            CompletableFuture.allOf(pending).whenComplete((unused, pendingError) -> drain(result));
        });
    }

    private boolean hasPendingWork() {
        for (Entry<T> entry : entries.values()) {
            if (entry.saving || (entry.isLoaded() && entry.dirty && !entry.quitting)) {
                return true;
            }
        }
        return false;
    }

    private void finishLoad(UUID playerId, Entry<T> entry, T value, Throwable error) {
        if (error == null && value == null) {
            // 空的加载结果绝不能让条目永远停留在等待状态。
            if (unloadedPolicy == UnloadedPolicy.CREATE_DEFAULT) {
                try {
                    value = defaults.get();
                } catch (RuntimeException failure) {
                    error = failure;
                }
            }
            if (error == null && value == null) {
                error = new IllegalStateException("repository returned null data for player " + playerId);
            }
        }
        Save<T> quitSave = null;
        synchronized (lock) {
            if (entries.get(playerId) != entry) {
                return;
            }
            inFlightOperations.remove(entry.loaded);
            if (error != null) {
                entries.remove(playerId);
                entry.loaded.completeExceptionally(error);
                if (entry.quitting) {
                    entry.quit.completeExceptionally(error);
                }
                return;
            }
            entry.value = value;
            entry.loaded.complete(entry.value);
            if (entry.quitting) {
                entry.saving = true;
                quitSave = new Save<T>(playerId, entry, entry.value, entry.version, true);
            }
        }
        if (quitSave != null) {
            save(quitSave);
        }
    }

    private CompletableFuture<Void> save(Save<T> save) {
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        synchronized (lock) {
            inFlightSaves.add(result);
        }
        CompletionStage<Void> operation;
        try {
            // 在锁内调用，使同步编码的仓库能在任何并发修改发生前取得值快照。
            synchronized (lock) {
                operation = repository.save(save.playerId, save.value);
            }
        } catch (RuntimeException error) {
            Save<T> next = finishSave(save, error);
            if (next != null) {
                save(next);
            }
            completeSave(result, error);
            return result;
        }
        if (operation == null) {
            IllegalStateException error = new IllegalStateException(
                    "repository.save returned null stage for player " + save.playerId);
            Save<T> next = finishSave(save, error);
            if (next != null) {
                save(next);
            }
            completeSave(result, error);
            return result;
        }
        operation.whenComplete((ignored, error) -> {
            Throwable failure = unwrap(error);
            Save<T> next = finishSave(save, failure);
            if (next != null) {
                save(next);
            }
            completeSave(result, failure);
        });
        return result;
    }

    private void completeSave(CompletableFuture<Void> result, Throwable failure) {
        synchronized (lock) {
            inFlightSaves.remove(result);
        }
        if (failure == null) {
            result.complete(null);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private void completeOperation(CompletableFuture<T> result, T value, Throwable failure) {
        synchronized (lock) {
            inFlightOperations.remove(result);
        }
        if (failure == null) {
            result.complete(value);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private Save<T> finishSave(Save<T> save, Throwable error) {
        synchronized (lock) {
            Entry<T> current = entries.get(save.playerId);
            if (current != save.entry) {
                return null;
            }
            current.saving = false;
            if (error == null) {
                if (current.version == save.version) {
                    current.dirty = false;
                }
                if (save.evict) {
                    entries.remove(save.playerId);
                    current.quit.complete(null);
                    return null;
                }
                if (current.quitting) {
                    if (current.version == save.version) {
                        entries.remove(save.playerId);
                        current.quit.complete(null);
                        return null;
                    }
                    current.saving = true;
                    return new Save<T>(save.playerId, current, current.value, current.version, true);
                }
            } else if (save.evict) {
                current.quitting = false;
                current.dirty = true;
                // 替换失败的退出 Future，使后续 quit() 仍有机会成功。
                current.quit.completeExceptionally(error);
                current.quit = new CompletableFuture<Void>();
            } else if (current.quitting) {
                current.saving = true;
                return new Save<T>(save.playerId, current, current.value, current.version, true);
            }
            return null;
        }
    }

    private T mutate(Entry<T> entry, UnaryOperator<T> mutation) {
        T value = requireValue(mutation.apply(entry.value));
        entry.value = value;
        entry.version++;
        entry.dirty = true;
        return value;
    }

    private void requireOpen() {
        if (disposed) {
            throw new IllegalStateException("cache is disposed");
        }
    }

    private static <T> T requireValue(T value) {
        if (value == null) {
            throw new IllegalArgumentException("player data value must not be null");
        }
        return value;
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletionStage<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(error);
        return future;
    }

    private static final class Entry<T> {
        private final CompletableFuture<T> loaded = new CompletableFuture<T>();
        private CompletableFuture<Void> quit = new CompletableFuture<Void>();
        private T value;
        private long version;
        private boolean dirty;
        private boolean saving;
        private boolean quitting;

        private boolean isLoaded() {
            return loaded.isDone() && !loaded.isCompletedExceptionally();
        }
    }

    private static final class Save<T> {
        private final UUID playerId;
        private final Entry<T> entry;
        private final T value;
        private final long version;
        private final boolean evict;

        private Save(UUID playerId, Entry<T> entry, T value, long version, boolean evict) {
            this.playerId = playerId;
            this.entry = entry;
            this.value = value;
            this.version = version;
            this.evict = evict;
        }
    }
}
