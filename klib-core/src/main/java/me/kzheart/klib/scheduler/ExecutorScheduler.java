package me.kzheart.klib.scheduler;

import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.diagnostic.DiagnosticSource;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class ExecutorScheduler implements KScheduler, DiagnosticSource {
    private final Scope scope;
    private final ScheduledExecutorService timerExecutor;
    private final ExecutorService asyncExecutor;
    private final Executor syncExecutor;
    // 对外暴露的同步执行器额外跳过已关闭作用域，语义与 AsyncTask 回调一致。
    private final Executor scopedSyncExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            if (command == null) {
                throw new NullPointerException("command");
            }
            if (scope.isClosed()) {
                return;
            }
            syncExecutor.execute(command);
        }
    };

    public ExecutorScheduler(
            Scope scope,
            ScheduledExecutorService timerExecutor,
            ExecutorService asyncExecutor,
            Executor syncExecutor
    ) {
        if (scope == null) {
            throw new NullPointerException("scope");
        }
        if (timerExecutor == null) {
            throw new NullPointerException("timerExecutor");
        }
        if (asyncExecutor == null) {
            throw new NullPointerException("asyncExecutor");
        }
        if (syncExecutor == null) {
            throw new NullPointerException("syncExecutor");
        }
        this.scope = scope;
        this.timerExecutor = timerExecutor;
        this.asyncExecutor = asyncExecutor;
        this.syncExecutor = syncExecutor;
    }

    @Override
    public TaskHandle every(Ticks period, final Runnable task) {
        requireTask(period, task);
        if (period.value() == 0L) {
            throw new IllegalArgumentException("period must be at least one tick");
        }

        final ScheduledTaskHandle handle = scope.install(new ScheduledTaskHandle(scope));
        try {
            Future<?> future = timerExecutor.scheduleAtFixedRate(new Runnable() {
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
            }, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
            handle.attach(future);
            return handle;
        } catch (RuntimeException failure) {
            handle.cancel();
            throw failure;
        }
    }

    @Override
    public TaskHandle after(Ticks delay, final Runnable task) {
        requireTask(delay, task);
        final ScheduledTaskHandle handle = scope.install(new ScheduledTaskHandle(scope));
        try {
            Future<?> future = timerExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!handle.isCancelled()) {
                            task.run();
                        }
                    } finally {
                        handle.markDone();
                    }
                }
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
            handle.attach(future);
            return handle;
        } catch (RuntimeException failure) {
            handle.cancel();
            throw failure;
        }
    }

    /**
     * 把任务投递到构造时传入的同步执行器，而不是定时器线程。
     */
    @Override
    public TaskHandle sync(final Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        final ScheduledTaskHandle handle = scope.install(new ScheduledTaskHandle(scope));
        try {
            syncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!handle.isCancelled()) {
                            task.run();
                        }
                    } finally {
                        handle.markDone();
                    }
                }
            });
            return handle;
        } catch (RuntimeException failure) {
            handle.cancel();
            throw failure;
        }
    }

    @Override
    public Executor syncExecutor() {
        return scopedSyncExecutor;
    }

    @Override
    public <T> AsyncTask<T> async(Supplier<T> supplier) {
        AsyncTaskImpl<T> task = scope.install(
                new AsyncTaskImpl<T>(asyncExecutor, syncExecutor, supplier, scope));
        try {
            task.start();
            return task;
        } catch (RuntimeException failure) {
            task.cancel();
            throw failure;
        }
    }

    private static void requireTask(Ticks ticks, Runnable task) {
        if (ticks == null) {
            throw new NullPointerException("ticks");
        }
        if (task == null) {
            throw new NullPointerException("task");
        }
    }

    @Override
    public String diagnosticName() {
        return "scheduler";
    }

    @Override
    public java.util.Map<String, ?> diagnosticSnapshot() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("backend", "executor");
        result.put("scope", scope.name());
        result.put("scope_closed", scope.isClosed());
        result.put("timer_shutdown", timerExecutor.isShutdown());
        result.put("async_shutdown", asyncExecutor.isShutdown());
        return result;
    }

    private static final class ScheduledTaskHandle implements TaskHandle {
        private final Object lock = new Object();
        private final Scope owner;
        private Future<?> future;
        private boolean cancelled;
        private boolean done;

        private ScheduledTaskHandle(Scope owner) {
            this.owner = owner;
        }

        private void attach(Future<?> future) {
            synchronized (lock) {
                this.future = future;
                if (cancelled) {
                    future.cancel(false);
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
            Future<?> scheduled;
            synchronized (lock) {
                if (cancelled) {
                    return false;
                }
                cancelled = true;
                scheduled = future;
            }
            if (scheduled != null) {
                scheduled.cancel(false);
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
                return cancelled || done || future != null && future.isDone();
            }
        }
    }
}
