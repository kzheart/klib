package me.kzheart.klib.scheduler;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.diagnostic.DiagnosticSource;
import me.kzheart.klib.scope.Scope;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

public final class BukkitSchedulerAdapter implements KScheduler, DiagnosticSource {
    private final Plugin plugin;
    private final Scope scope;
    private final BukkitScheduler scheduler;
    private final KLogger logger;
    private final BukkitAsyncExecutor asyncExecutor = new BukkitAsyncExecutor();
    private final Executor syncExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            scheduler.runTask(plugin, command);
        }
    };
    // 对外暴露的同步执行器额外跳过已关闭作用域，避免关闭后的回调继续触碰服务器状态。
    private final Executor scopedSyncExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command, "command");
            if (scope.isClosed()) {
                return;
            }
            scheduler.runTask(plugin, observed(command));
        }
    };

    public BukkitSchedulerAdapter(
            Plugin plugin,
            Scope scope,
            BukkitScheduler scheduler,
            KLogger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public TaskHandle every(Ticks period, Runnable task) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(task, "task");
        if (period.value() < 1L) {
            throw new IllegalArgumentException("period must be at least one tick");
        }
        BukkitTaskHandle handle = scope.install(new BukkitTaskHandle(scope));
        Runnable observedTask = observed(task);
        try {
            BukkitTask scheduled = scheduler.runTaskTimer(
                    plugin,
                    recurring(observedTask, handle),
                    period.value(),
                    period.value());
            handle.attach(scheduled);
            return handle;
        } catch (RuntimeException failure) {
            handle.cancel();
            throw failure;
        }
    }

    @Override
    public TaskHandle after(Ticks delay, final Runnable task) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(task, "task");
        final BukkitTaskHandle handle = scope.install(new BukkitTaskHandle(scope));
        try {
            BukkitTask scheduled = scheduler.runTaskLater(plugin, new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!handle.isCancelled()) {
                            observed(task).run();
                        }
                    } finally {
                        handle.markDone();
                    }
                }
            }, delay.value());
            handle.attach(scheduled);
            return handle;
        } catch (RuntimeException failure) {
            handle.cancel();
            throw failure;
        }
    }

    /**
     * 在服务器主线程的下一 tick 执行任务，可从异步线程调用。
     */
    @Override
    public TaskHandle sync(Runnable task) {
        return after(Ticks.of(0L), task);
    }

    @Override
    public Executor syncExecutor() {
        return scopedSyncExecutor;
    }

    @Override
    public <T> AsyncTask<T> async(Supplier<T> supplier) {
        AsyncTaskImpl<T> task = scope.install(
                new AsyncTaskImpl<T>(asyncExecutor, syncExecutor, observed(supplier), scope));
        try {
            task.start();
            return task;
        } catch (RuntimeException failure) {
            task.cancel();
            throw failure;
        }
    }

    private static Runnable recurring(final Runnable task, final BukkitTaskHandle handle) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    if (!handle.isCancelled()) {
                        task.run();
                    }
                } catch (RuntimeException failure) {
                    handle.cancel();
                    throw failure;
                } catch (Error failure) {
                    handle.cancel();
                    throw failure;
                }
            }
        };
    }

    private Runnable observed(final Runnable task) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (RuntimeException failure) {
                    logger.error("任务执行失败: " + scope.name(), failure);
                    throw failure;
                } catch (Error failure) {
                    logger.error("任务执行失败: " + scope.name(), failure);
                    throw failure;
                }
            }
        };
    }

    private <T> Supplier<T> observed(final Supplier<T> supplier) {
        return new Supplier<T>() {
            @Override
            public T get() {
                try {
                    return supplier.get();
                } catch (RuntimeException failure) {
                    logger.error("异步任务执行失败: " + scope.name(), failure);
                    throw failure;
                } catch (Error failure) {
                    logger.error("异步任务执行失败: " + scope.name(), failure);
                    throw failure;
                }
            }
        };
    }

    @Override
    public String diagnosticName() {
        return "scheduler";
    }

    @Override
    public java.util.Map<String, ?> diagnosticSnapshot() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("backend", "bukkit");
        result.put("scope", scope.name());
        result.put("scope_closed", scope.isClosed());
        result.put("async_shutdown", asyncExecutor.isShutdown());
        return result;
    }

    private final class BukkitAsyncExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            Objects.requireNonNull(unit, "unit");
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command, "command");
            if (shutdown) {
                throw new RejectedExecutionException("Bukkit async executor is shut down");
            }
            scheduler.runTaskAsynchronously(plugin, command);
        }
    }

    private static final class BukkitTaskHandle implements TaskHandle {
        private final Object lock = new Object();
        private final Scope owner;
        private BukkitTask task;
        private boolean cancelled;
        private boolean done;

        private BukkitTaskHandle(Scope owner) {
            this.owner = owner;
        }

        private void attach(BukkitTask scheduled) {
            synchronized (lock) {
                task = scheduled;
                if (cancelled) {
                    scheduled.cancel();
                }
            }
        }

        private void markDone() {
            synchronized (lock) {
                done = true;
            }
            owner.remove(this);
        }

        @Override
        public boolean cancel() {
            BukkitTask scheduled;
            synchronized (lock) {
                if (cancelled || done) {
                    return false;
                }
                cancelled = true;
                scheduled = task;
            }
            if (scheduled != null) {
                scheduled.cancel();
            }
            owner.remove(this);
            return true;
        }

        @Override
        public boolean isCancelled() {
            synchronized (lock) {
                return cancelled;
            }
        }

        @Override
        public boolean isDone() {
            synchronized (lock) {
                return done || cancelled;
            }
        }
    }
}
