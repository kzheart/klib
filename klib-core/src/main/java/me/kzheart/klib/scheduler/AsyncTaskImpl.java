package me.kzheart.klib.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.kzheart.klib.scope.Scope;

public final class AsyncTaskImpl<T> implements AsyncTask<T> {
    private final Object lock = new Object();
    private final ExecutorService asyncExecutor;
    private final Executor syncExecutor;
    private final Supplier<T> supplier;
    private final Scope owner;
    private final List<Consumer<? super T>> callbacks = new ArrayList<Consumer<? super T>>();
    private final List<Consumer<? super Throwable>> errorCallbacks =
            new ArrayList<Consumer<? super Throwable>>();

    private Future<?> future;
    private T result;
    private Throwable error;
    private boolean started;
    private boolean completed;
    private boolean failed;
    private boolean cancelled;
    private int pendingDispatches;

    public AsyncTaskImpl(ExecutorService asyncExecutor, Executor syncExecutor, Supplier<T> supplier) {
        this(asyncExecutor, syncExecutor, supplier, null);
    }

    public AsyncTaskImpl(
            ExecutorService asyncExecutor,
            Executor syncExecutor,
            Supplier<T> supplier,
            Scope owner
    ) {
        if (asyncExecutor == null) {
            throw new NullPointerException("asyncExecutor");
        }
        if (syncExecutor == null) {
            throw new NullPointerException("syncExecutor");
        }
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        this.asyncExecutor = asyncExecutor;
        this.syncExecutor = syncExecutor;
        this.supplier = supplier;
        this.owner = owner;
    }

    public void start() {
        RuntimeException rejection;
        synchronized (lock) {
            if (started) {
                throw new IllegalStateException("async task has already been started");
            }
            started = true;
            if (cancelled) {
                return;
            }
            try {
                future = asyncExecutor.submit(this::runSupplier);
                return;
            } catch (RuntimeException exception) {
                completed = true;
                failed = true;
                error = exception;
                callbacks.clear();
                errorCallbacks.clear();
                rejection = exception;
            }
        }
        detachFromOwner();
        throw rejection;
    }

    @Override
    public AsyncTask<T> thenSync(Consumer<? super T> callback) {
        if (callback == null) {
            throw new NullPointerException("callback");
        }

        boolean dispatch;
        synchronized (lock) {
            if (cancelled) {
                return this;
            }
            if (failed) {
                return this;
            }
            dispatch = completed;
            if (!dispatch) {
                callbacks.add(callback);
            }
        }
        if (dispatch) {
            dispatch(callback);
        }
        return this;
    }

    @Override
    public AsyncTask<T> onError(Consumer<? super Throwable> callback) {
        if (callback == null) {
            throw new NullPointerException("callback");
        }

        boolean dispatch;
        synchronized (lock) {
            if (cancelled) {
                return this;
            }
            if (completed && !failed) {
                return this;
            }
            dispatch = failed;
            if (!dispatch) {
                errorCallbacks.add(callback);
            }
        }
        if (dispatch) {
            dispatchError(callback);
        }
        return this;
    }

    @Override
    public boolean cancel() {
        Future<?> submitted;
        synchronized (lock) {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            callbacks.clear();
            errorCallbacks.clear();
            submitted = future;
        }
        if (submitted != null) {
            submitted.cancel(false);
        }
        detachFromOwner();
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
            return completed || cancelled;
        }
    }

    @Override
    public boolean isFailed() {
        synchronized (lock) {
            return failed;
        }
    }

    private void runSupplier() {
        T supplied;
        try {
            supplied = supplier.get();
        } catch (RuntimeException failure) {
            completeExceptionally(failure);
            throw failure;
        } catch (Error failure) {
            completeExceptionally(failure);
            throw failure;
        }

        List<Consumer<? super T>> pending;
        synchronized (lock) {
            completed = true;
            if (cancelled) {
                callbacks.clear();
                errorCallbacks.clear();
                return;
            }
            result = supplied;
            pending = new ArrayList<Consumer<? super T>>(callbacks);
            callbacks.clear();
            errorCallbacks.clear();
        }
        for (Consumer<? super T> callback : pending) {
            dispatch(callback);
        }
        detachWhenIdle();
    }

    private void completeExceptionally(Throwable failure) {
        List<Consumer<? super Throwable>> pending;
        synchronized (lock) {
            completed = true;
            failed = true;
            error = failure;
            callbacks.clear();
            if (cancelled) {
                errorCallbacks.clear();
                return;
            }
            pending = new ArrayList<Consumer<? super Throwable>>(errorCallbacks);
            errorCallbacks.clear();
        }
        for (Consumer<? super Throwable> callback : pending) {
            dispatchError(callback);
        }
        detachWhenIdle();
    }

    private void dispatch(final Consumer<? super T> callback) {
        synchronized (lock) {
            pendingDispatches++;
        }
        try {
            syncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (lock) {
                            if (cancelled) {
                                return;
                            }
                        }
                        if (owner != null && owner.isClosed()) {
                            cancel();
                            return;
                        }
                        callback.accept(result);
                    } finally {
                        dispatchFinished();
                    }
                }
            });
        } catch (RuntimeException exception) {
            dispatchFinished();
            cancel();
        }
    }

    private void dispatchError(final Consumer<? super Throwable> callback) {
        synchronized (lock) {
            pendingDispatches++;
        }
        try {
            syncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (lock) {
                            if (cancelled) {
                                return;
                            }
                        }
                        if (owner != null && owner.isClosed()) {
                            cancel();
                            return;
                        }
                        callback.accept(error);
                    } finally {
                        dispatchFinished();
                    }
                }
            });
        } catch (RuntimeException exception) {
            dispatchFinished();
            cancel();
        }
    }

    private void dispatchFinished() {
        synchronized (lock) {
            pendingDispatches--;
        }
        detachWhenIdle();
    }

    /**
     * 仅当已派发的回调均不再排队时才从所属作用域分离，
     * 从而让正在关闭的作用域仍能取消待执行回调。
     */
    private void detachWhenIdle() {
        synchronized (lock) {
            if (pendingDispatches > 0) {
                return;
            }
        }
        detachFromOwner();
    }

    private void detachFromOwner() {
        if (owner != null) {
            owner.remove(this);
        }
    }
}
