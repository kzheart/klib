package me.kzheart.klib.config;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Logger;
import me.kzheart.klib.scope.Disposable;

/** 每个目录使用一个监视服务和线程，并在多个订阅者之间复用。 */
final class FileWatchHandle {
    private static final Logger LOGGER = Logger.getLogger(FileWatchHandle.class.getName());
    /** 合并突发变更的时间窗口（编辑器每次保存通常会产生多个事件）。 */
    private static final long DEBOUNCE_MILLIS = 75L;
    private static final Object WATCHERS_LOCK = new Object();
    private static final Map<Path, DirectoryWatcher> WATCHERS =
            new HashMap<Path, DirectoryWatcher>();

    private FileWatchHandle() {
    }

    static Disposable start(Path directory, Predicate<Path> filter, Runnable listener) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(listener, "listener");
        Path normalized = directory.toAbsolutePath().normalize();
        synchronized (WATCHERS_LOCK) {
            DirectoryWatcher watcher = WATCHERS.get(normalized);
            if (watcher == null) {
                watcher = DirectoryWatcher.open(normalized);
                WATCHERS.put(normalized, watcher);
            }
            return watcher.subscribe(filter, listener);
        }
    }

    private static void release(DirectoryWatcher watcher) {
        synchronized (WATCHERS_LOCK) {
            if (WATCHERS.get(watcher.directory) == watcher && watcher.isIdle()) {
                WATCHERS.remove(watcher.directory);
                watcher.close();
            }
        }
    }

    private static final class DirectoryWatcher {
        private final Path directory;
        private final WatchService watchService;
        private final Thread thread;
        private final CopyOnWriteArrayList<Subscription> subscriptions =
                new CopyOnWriteArrayList<Subscription>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private DirectoryWatcher(Path directory, WatchService watchService) {
            this.directory = directory;
            this.watchService = watchService;
            this.thread = new Thread(this::watchLoop, "klib-config-watch-" + directory.getFileName());
            this.thread.setDaemon(true);
        }

        private static DirectoryWatcher open(Path directory) {
            try {
                Files.createDirectories(directory);
                WatchService watchService = FileSystems.getDefault().newWatchService();
                DirectoryWatcher watcher = new DirectoryWatcher(directory, watchService);
                watcher.register();
                watcher.thread.start();
                return watcher;
            } catch (IOException failure) {
                throw new ConfigException(directory + ": cannot start config watcher", failure);
            }
        }

        private Disposable subscribe(Predicate<Path> filter, Runnable listener) {
            Subscription subscription = new Subscription(this, filter, listener);
            subscriptions.add(subscription);
            return subscription;
        }

        private boolean isIdle() {
            return subscriptions.isEmpty();
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                watchService.close();
            } catch (IOException ignored) {
                // 关闭操作尽力而为；下方还会通过中断线程进行兜底。
            }
            thread.interrupt();
        }

        private void watchLoop() {
            while (!closed.get()) {
                final WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ClosedWatchServiceException ignored) {
                    return;
                }

                Set<Path> changed = new LinkedHashSet<Path>();
                boolean overflow = drain(key, changed);
                if (!resetOrRecover(key)) {
                    return;
                }
                if (!debounce(changed)) {
                    overflow = true;
                }
                notifySubscribers(changed, overflow);
            }
        }

        /** 将防抖窗口内到达的后续事件合并为一次通知。 */
        private boolean debounce(Set<Path> changed) {
            boolean healthy = true;
            while (true) {
                final WatchKey extra;
                try {
                    extra = watchService.poll(DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return healthy;
                } catch (ClosedWatchServiceException ignored) {
                    return healthy;
                }
                if (extra == null) {
                    return healthy;
                }
                if (drain(extra, changed)) {
                    healthy = false;
                }
                if (!resetOrRecover(extra)) {
                    return false;
                }
            }
        }

        /** 当事件溢出导致所有订阅者都必须重新加载时返回 true。 */
        private boolean drain(WatchKey key, Set<Path> changed) {
            boolean overflow = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    overflow = true;
                    continue;
                }
                Object context = event.context();
                if (context instanceof Path) {
                    changed.add(directory.resolve((Path) context));
                }
            }
            return overflow;
        }

        private void notifySubscribers(Set<Path> changed, boolean overflow) {
            if (closed.get() || (changed.isEmpty() && !overflow)) {
                return;
            }
            for (Subscription subscription : subscriptions) {
                if (overflow || subscription.matchesAny(changed)) {
                    try {
                        subscription.listener.run();
                    } catch (RuntimeException ignored) {
                        // 可重新加载的持有者保留最近一次有效快照，并对外公开本次失败。
                    }
                }
            }
        }

        /** 监视键失效时重新注册目录，而不是静默停止。 */
        private boolean resetOrRecover(WatchKey key) {
            if (key.reset()) {
                return true;
            }
            if (closed.get()) {
                return false;
            }
            LOGGER.warning(directory + ": config watch key became invalid; re-registering directory");
            try {
                Files.createDirectories(directory);
                register();
                return true;
            } catch (IOException | ClosedWatchServiceException failure) {
                LOGGER.severe(directory + ": config watcher stopped, automatic reloads are disabled: "
                        + failure);
                return false;
            }
        }

        private void register() throws IOException {
            directory.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        }
    }

    private static final class Subscription implements Disposable {
        private final DirectoryWatcher watcher;
        private final Predicate<Path> filter;
        private final Runnable listener;
        private final AtomicBoolean disposed = new AtomicBoolean();

        private Subscription(DirectoryWatcher watcher, Predicate<Path> filter, Runnable listener) {
            this.watcher = watcher;
            this.filter = filter;
            this.listener = listener;
        }

        private boolean matchesAny(Set<Path> changed) {
            if (disposed.get()) {
                return false;
            }
            for (Path path : changed) {
                if (filter.test(path)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void dispose() {
            if (!disposed.compareAndSet(false, true)) {
                return;
            }
            watcher.subscriptions.remove(this);
            FileWatchHandle.release(watcher);
        }
    }
}
