package me.kzheart.klib.remote;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import me.kzheart.klib.scheduler.AsyncTask;
import me.kzheart.klib.scheduler.KScheduler;
import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scheduler.Ticks;

/** 在不改变底层调度语义的前提下，把当前 Remote operation 传播到异步任务。 */
public final class RemoteScheduler implements KScheduler {
    private final KScheduler delegate;

    public RemoteScheduler(KScheduler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public TaskHandle every(Ticks period, Runnable task) {
        return delegate.every(period, RemoteOperation.wrapCurrent(task));
    }

    @Override public TaskHandle after(Ticks delay, Runnable task) {
        return delegate.after(delay, RemoteOperation.wrapCurrent(task));
    }

    @Override public TaskHandle sync(Runnable task) {
        return delegate.sync(RemoteOperation.wrapCurrent(task));
    }

    @Override public Executor syncExecutor() {
        final Executor executor = delegate.syncExecutor();
        return new Executor() {
            @Override public void execute(Runnable command) {
                executor.execute(RemoteOperation.wrapCurrent(command));
            }
        };
    }

    @Override public <T> AsyncTask<T> async(Supplier<T> supplier) {
        return delegate.async(RemoteOperation.wrapCurrent(supplier));
    }
}
