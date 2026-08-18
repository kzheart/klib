package me.kzheart.klib.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import me.kzheart.klib.scope.Disposable;

/**
 * 将 Remote 事件非阻塞地写入有界磁盘队列，并在后台按优先级批量投递。
 *
 * <p>Incident 总是先于普通日志投递。默认磁盘预算为 16 MiB，事件在队列中最多保留
 * 24 小时。调用线程不执行磁盘或网络 I/O，队列、序列化和传输故障也不会抛回调用方。</p>
 */
public final class RemoteDelivery implements Consumer<RemoteEvent>, Disposable {
    /** 默认磁盘队列预算：16 MiB。 */
    public static final long DEFAULT_MAX_DISK_BYTES = 16L * 1024L * 1024L;
    /** 默认队列保留时间：24 小时。 */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24L);
    private static final long PAUSED_RETRY_MILLIS = TimeUnit.MINUTES.toMillis(5L);

    private final RemoteClient client;
    private final int maxPendingEvents;
    private final int batchSize;
    private final int maxBatchBytes;
    private final int maxEventBytes;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final long settingsRefreshMillis;
    private final ReentrantLock pendingLock = new ReentrantLock();
    private final Deque<Pending> pendingIncidents = new ArrayDeque<Pending>();
    private final Deque<Pending> pendingLogs = new ArrayDeque<Pending>();
    private final Semaphore wake = new Semaphore(0);
    private final AtomicLong droppedEvents = new AtomicLong();
    private final RemoteDiskQueue disk;
    private final Thread worker;
    private volatile boolean closed;

    private RemoteDelivery(Builder builder) {
        client = builder.client;
        maxPendingEvents = builder.maxPendingEvents;
        batchSize = builder.batchSize;
        maxBatchBytes = builder.maxBatchBytes;
        maxEventBytes = builder.maxEventBytes;
        initialBackoffMillis = builder.initialBackoffMillis;
        maxBackoffMillis = builder.maxBackoffMillis;
        settingsRefreshMillis = builder.settingsRefreshMillis;
        RemoteDiskQueue opened;
        try {
            opened = new RemoteDiskQueue(builder.directory, builder.maxDiskBytes,
                    builder.maxDiskEvents, builder.maxEventBytes, builder.ttlMillis,
                    builder.client.queueIdentity());
        } catch (Throwable failure) {
            opened = null;
        }
        disk = opened;
        if (disk == null) {
            closed = true;
            worker = null;
        } else {
            worker = new Thread(this::run, "klib-remote-delivery");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /** 创建使用给定客户端和专属队列目录的交付器配置。 */
    public static Builder builder(RemoteClient client, Path queueDirectory) {
        return new Builder(client, queueDirectory);
    }

    /**
     * 尽力提交事件且永不抛出。该方法只进入有界内存邮箱；需要确认原子落盘时使用
     * {@link #submit(RemoteEvent)} 返回的阶段。
     */
    @Override public void accept(RemoteEvent event) {
        submit(event);
    }

    /**
     * 非阻塞提交事件。返回阶段在事件完成原子落盘后得到 {@code true}；容量淘汰、关闭、
     * 无效事件或本地持久化失败时得到 {@code false}。该方法不会以异常完成。
     */
    public CompletionStage<Boolean> submit(RemoteEvent event) {
        CompletableFuture<Boolean> persisted = new CompletableFuture<Boolean>();
        try {
            if (closed || event == null) {
                persisted.complete(Boolean.FALSE);
                droppedEvents.incrementAndGet();
                return persisted;
            }
            Map<String, Object> values = event.toMap();
            Object rawType = values.get("type");
            Object rawEventId = values.get("event_id");
            String type = rawType == null ? "" : String.valueOf(rawType);
            String eventId = Texts.requireText(
                    rawEventId == null ? null : String.valueOf(rawEventId), "event_id");
            byte[] encoded = DiagnosticJson.write(values, maxEventBytes)
                    .getBytes(StandardCharsets.UTF_8);
            Pending pending = new Pending("incident".equals(type), eventId,
                    System.currentTimeMillis(), encoded, persisted);
            if (!offer(pending)) {
                persisted.complete(Boolean.FALSE);
                droppedEvents.incrementAndGet();
            } else {
                wake.release();
            }
        } catch (Throwable failure) {
            persisted.complete(Boolean.FALSE);
            droppedEvents.incrementAndGet();
        }
        return persisted;
    }

    /** 返回内存邮箱与磁盘队列中尚未终结的事件近似数量。 */
    public long queuedEvents() {
        long pending = pendingSize();
        try {
            return pending + (disk == null ? 0L : disk.size());
        } catch (Throwable ignored) {
            return pending;
        }
    }

    /** 返回因本地容量、期限、关闭或持久化故障而丢弃的事件数。 */
    public long droppedEvents() {
        return droppedEvents.get();
    }

    /** 返回交付器是否仍接受新事件。 */
    public boolean isActive() {
        return !closed;
    }

    /** 停止后台交付；不会等待网络请求结束。 */
    @Override public void dispose() {
        closed = true;
        wake.release();
        if (worker != null) worker.interrupt();
        failPending();
    }

    private boolean offer(Pending value) {
        if (!pendingLock.tryLock()) return false;
        try {
            if (closed) return false;
            int size = pendingIncidents.size() + pendingLogs.size();
            if (size >= maxPendingEvents) {
                Pending evicted;
                if (value.incident) {
                    evicted = pendingLogs.pollFirst();
                    if (evicted == null) evicted = pendingIncidents.pollFirst();
                } else {
                    evicted = pendingLogs.pollFirst();
                    if (evicted == null) return false;
                }
                evicted.persisted.complete(Boolean.FALSE);
                droppedEvents.incrementAndGet();
            }
            (value.incident ? pendingIncidents : pendingLogs).addLast(value);
            return true;
        } finally {
            pendingLock.unlock();
        }
    }

    private void run() {
        long nextAttemptAt = 0L;
        long nextSettingsAt = 0L;
        int failures = 0;
        RemoteBatchEnvelope currentEnvelope = null;
        try {
            while (!closed) {
                try {
                    if (currentEnvelope == null) currentEnvelope = client.snapshotEnvelope();
                    persistPending(currentEnvelope);
                    droppedEvents.addAndGet(disk.purgeExpired(System.currentTimeMillis()));
                    long now = System.currentTimeMillis();
                    if (now >= nextSettingsAt) {
                        try {
                            RemoteSettings previousSettings = client.settings();
                            boolean previouslyAccepting = previousSettings != null
                                    && previousSettings.acceptingEvents()
                                    && !previousSettings.policy().paused();
                            client.refreshPolicy();
                            failures = 0;
                            nextSettingsAt = safeAdd(now, settingsRefreshMillis);
                            RemoteSettings refreshed = client.settings();
                            if (!previouslyAccepting && refreshed != null
                                    && refreshed.acceptingEvents()
                                    && !refreshed.policy().paused()) {
                                nextAttemptAt = 0L;
                            }
                        } catch (RemoteHttpException failure) {
                            if (failure.status() == 401) {
                                closed = true;
                                continue;
                            }
                            failures++;
                            nextSettingsAt = safeAdd(now,
                                    retryDelay(failures, failure.retryAfterMillis()));
                            waitFor(Math.min(1000L, Math.max(1L, nextSettingsAt - now)));
                            continue;
                        } catch (IOException failure) {
                            failures++;
                            nextSettingsAt = safeAdd(now, retryDelay(failures, -1L));
                            waitFor(Math.min(1000L, Math.max(1L, nextSettingsAt - now)));
                            continue;
                        }
                    }
                    RemoteSettings currentSettings = client.settings();
                    if (currentSettings == null || !currentSettings.acceptingEvents()
                            || currentSettings.policy().paused()) {
                        waitFor(Math.min(1000L, Math.max(1L, nextSettingsAt - now)));
                        continue;
                    }
                    if (now < nextAttemptAt) {
                        waitFor(Math.min(1000L, nextAttemptAt - now));
                        continue;
                    }
                    RemoteSettings.Limits limits = currentSettings.limits();
                    droppedEvents.addAndGet(disk.discardOversized(
                            Math.min(maxEventBytes, limits.maxEventBytes())));
                    List<RemoteDiskQueue.Entry> batch = disk.batch(
                            Math.min(batchSize, limits.maxBatchEvents()),
                            Math.min(maxBatchBytes, safeInt(limits.maxDecompressedBytes())));
                    if (batch.isEmpty()) {
                        waitFor(1000L);
                        continue;
                    }
                    batch = fitBatch(batch, limits);
                    if (batch.isEmpty()) continue;
                    DeliveryOutcome outcome = deliver(batch);
                    if (outcome == DeliveryOutcome.COMPLETE) {
                        failures = 0;
                        nextAttemptAt = 0L;
                    } else {
                        failures++;
                        nextAttemptAt = safeAdd(now, retryDelay(failures, -1L));
                    }
                } catch (RemoteHttpException failure) {
                    if (failure.status() == 401) {
                        closed = true;
                    } else if (failure.status() == 403) {
                        failures++;
                        long retryAfter = Math.max(PAUSED_RETRY_MILLIS,
                                failure.retryAfterMillis());
                        nextAttemptAt = safeAdd(System.currentTimeMillis(), retryAfter);
                    } else {
                        failures++;
                        nextAttemptAt = safeAdd(System.currentTimeMillis(),
                                retryDelay(failures, failure.retryAfterMillis()));
                    }
                } catch (Throwable failure) {
                    failures++;
                    nextAttemptAt = safeAdd(System.currentTimeMillis(),
                            retryDelay(failures, -1L));
                }
            }
        } finally {
            failPending();
            disk.close();
        }
    }

    private void persistPending(RemoteBatchEnvelope envelope) {
        Pending value;
        while ((value = pollPending()) != null) {
            RemoteSettings current = client.settings();
            int eventLimit = current == null ? maxEventBytes
                    : Math.min(maxEventBytes, current.limits().maxEventBytes());
            if (value.data.length > eventLimit) {
                droppedEvents.incrementAndGet();
                value.persisted.complete(Boolean.FALSE);
                continue;
            }
            RemoteDiskQueue.StoreResult result = disk.store(
                    value.incident, value.createdAtMillis, value.eventId, envelope, value.data);
            if (result.evicted() > 0) droppedEvents.addAndGet(result.evicted());
            if (!result.stored()) droppedEvents.incrementAndGet();
            value.persisted.complete(Boolean.valueOf(result.stored()));
        }
    }

    private List<RemoteDiskQueue.Entry> fitBatch(List<RemoteDiskQueue.Entry> candidate,
            RemoteSettings.Limits limits) throws IOException {
        List<RemoteDiskQueue.Entry> fitted = new ArrayList<RemoteDiskQueue.Entry>(candidate);
        while (!fitted.isEmpty()) {
            List<byte[]> bodies = new ArrayList<byte[]>(fitted.size());
            for (RemoteDiskQueue.Entry entry : fitted) bodies.add(entry.data());
            if (client.batchFits(fitted.get(0).envelope(), bodies, limits)) return fitted;
            if (fitted.size() > 1) {
                fitted.remove(fitted.size() - 1);
                continue;
            }
            if (disk.remove(fitted.get(0))) droppedEvents.incrementAndGet();
            fitted.clear();
        }
        return fitted;
    }

    private DeliveryOutcome deliver(List<RemoteDiskQueue.Entry> batch) throws Exception {
        List<byte[]> bodies = new ArrayList<byte[]>(batch.size());
        List<String> eventIds = new ArrayList<String>(batch.size());
        for (RemoteDiskQueue.Entry entry : batch) {
            bodies.add(entry.data());
            eventIds.add(entry.eventId());
        }
        RemoteBatchResult receipt = client.sendEncodedBatch(
                batch.get(0).envelope(), bodies, eventIds);
        boolean removedAll = true;
        for (RemoteBatchResult.EventResult result : receipt.results()) {
            if (!disk.remove(batch.get(result.index()))) removedAll = false;
        }
        return removedAll ? DeliveryOutcome.COMPLETE : DeliveryOutcome.PARTIAL;
    }

    private Pending pollPending() {
        pendingLock.lock();
        try {
            Pending value = pendingIncidents.pollFirst();
            return value == null ? pendingLogs.pollFirst() : value;
        } finally {
            pendingLock.unlock();
        }
    }

    private long pendingSize() {
        if (!pendingLock.tryLock()) return 0L;
        try {
            return pendingIncidents.size() + pendingLogs.size();
        } finally {
            pendingLock.unlock();
        }
    }

    private void failPending() {
        pendingLock.lock();
        try {
            Pending value;
            while ((value = pendingIncidents.pollFirst()) != null) {
                value.persisted.complete(Boolean.FALSE);
                droppedEvents.incrementAndGet();
            }
            while ((value = pendingLogs.pollFirst()) != null) {
                value.persisted.complete(Boolean.FALSE);
                droppedEvents.incrementAndGet();
            }
        } finally {
            pendingLock.unlock();
        }
    }

    private void waitFor(long millis) {
        try {
            wake.tryAcquire(Math.max(1L, millis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private long retryDelay(int failures, long retryAfterMillis) {
        int shift = Math.min(30, Math.max(0, failures - 1));
        long base = initialBackoffMillis;
        if (base > maxBackoffMillis >> shift) base = maxBackoffMillis;
        else base = Math.min(maxBackoffMillis, base << shift);
        long lower = base / 2L;
        long jitter = base <= 1L ? base
                : lower + ThreadLocalRandom.current().nextLong(base - lower + 1L);
        return retryAfterMillis < 0L ? jitter : Math.max(jitter, retryAfterMillis);
    }

    private static long safeAdd(long value, long addition) {
        return addition > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + addition;
    }

    private static int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1L, value);
    }

    private enum DeliveryOutcome { COMPLETE, PARTIAL }

    private static final class Pending {
        private final boolean incident;
        private final String eventId;
        private final long createdAtMillis;
        private final byte[] data;
        private final CompletableFuture<Boolean> persisted;

        private Pending(boolean incident, String eventId, long createdAtMillis,
                byte[] data, CompletableFuture<Boolean> persisted) {
            this.incident = incident;
            this.eventId = eventId;
            this.createdAtMillis = createdAtMillis;
            this.data = data;
            this.persisted = persisted;
        }
    }

    /** RemoteDelivery 的有界队列与重试配置。 */
    public static final class Builder {
        private final RemoteClient client;
        private final Path directory;
        private long maxDiskBytes = DEFAULT_MAX_DISK_BYTES;
        private long ttlMillis = DEFAULT_TTL.toMillis();
        private int maxPendingEvents = 256;
        private int maxDiskEvents = 4096;
        private int batchSize = 100;
        private int maxEventBytes = 64 * 1024;
        private int maxBatchBytes = 1024 * 1024;
        private long initialBackoffMillis = 1000L;
        private long maxBackoffMillis = 60_000L;
        private long settingsRefreshMillis = 60_000L;

        private Builder(RemoteClient client, Path directory) {
            this.client = Objects.requireNonNull(client, "client");
            this.directory = Objects.requireNonNull(directory, "queueDirectory");
        }

        /** 设置磁盘队列的固定字节预算。 */
        public Builder maxDiskBytes(long value) {
            if (value < 1024L) throw new IllegalArgumentException("maxDiskBytes is too small");
            maxDiskBytes = value;
            return this;
        }

        /** 设置事件在磁盘中的最长保留时间。 */
        public Builder ttl(Duration value) {
            ttlMillis = positiveMillis(value, "ttl");
            return this;
        }

        /** 设置调用线程与工作线程之间的有界邮箱事件数。 */
        public Builder maxPendingEvents(int value) {
            if (value < 1) throw new IllegalArgumentException("maxPendingEvents must be positive");
            maxPendingEvents = value;
            return this;
        }

        /** 设置磁盘队列的固定事件数上限。 */
        public Builder maxDiskEvents(int value) {
            if (value < 1) throw new IllegalArgumentException("maxDiskEvents must be positive");
            maxDiskEvents = value;
            return this;
        }

        /** 设置单次请求的最大事件数。 */
        public Builder batchSize(int value) {
            if (value < 1) throw new IllegalArgumentException("batchSize must be positive");
            batchSize = value;
            return this;
        }

        /** 设置单个完整事件的最大 UTF-8 字节数；超限事件会整体拒绝而非截断。 */
        public Builder maxEventBytes(int value) {
            if (value < 1) throw new IllegalArgumentException("maxEventBytes must be positive");
            maxEventBytes = value;
            return this;
        }

        /** 设置一个 batch 中事件正文的总 UTF-8 字节预算。 */
        public Builder maxBatchBytes(int value) {
            if (value < 1) throw new IllegalArgumentException("maxBatchBytes must be positive");
            maxBatchBytes = value;
            return this;
        }

        /** 设置第一次失败后的指数退避基数。 */
        public Builder initialBackoff(Duration value) {
            initialBackoffMillis = positiveMillis(value, "initialBackoff");
            return this;
        }

        /** 设置指数退避上限；HTTP 429 的 Retry-After 仍可超过该值。 */
        public Builder maxBackoff(Duration value) {
            maxBackoffMillis = positiveMillis(value, "maxBackoff");
            return this;
        }

        /** 设置后台刷新公开运行策略与服务端预算的周期。 */
        public Builder settingsRefreshInterval(Duration value) {
            settingsRefreshMillis = positiveMillis(value, "settingsRefreshInterval");
            return this;
        }

        /** 构建并立即启动后台交付器；队列目录不可安全打开时返回关闭状态的交付器。 */
        public RemoteDelivery build() {
            if (maxEventBytes > maxBatchBytes) {
                throw new IllegalStateException("maxEventBytes must not exceed maxBatchBytes");
            }
            if (initialBackoffMillis > maxBackoffMillis) {
                throw new IllegalStateException("initialBackoff must not exceed maxBackoff");
            }
            return new RemoteDelivery(this);
        }

        private static long positiveMillis(Duration value, String name) {
            Objects.requireNonNull(value, name);
            long millis;
            try {
                millis = value.toMillis();
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException(name + " is too large", failure);
            }
            if (millis < 1L) throw new IllegalArgumentException(name + " must be positive");
            return millis;
        }
    }
}
