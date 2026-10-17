package me.kzheart.klib.scope;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import me.kzheart.klib.scheduler.KScheduler;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.TaskHandle;

/** One result owned by the generation in which it was submitted. */
final class ScopedFuture<S, T> extends CompletableFuture<T> implements Disposable {
    private final AtomicReference<Submission<S, T>> pending =
            new AtomicReference<Submission<S, T>>();

    static <S, T> CompletableFuture<T> submit(
            Scope scope, CompletionStage<S> source, Function<? super S, ? extends T> action
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(action, "action");
        final ScopeGeneration generation;
        try {
            generation = ScopeGeneration.of(scope);
        } catch (IllegalStateException closed) {
            CompletableFuture<T> cancelled = new CompletableFuture<T>();
            cancelled.cancel(false);
            return cancelled;
        }
        final ScopedFuture<S, T> result = new ScopedFuture<S, T>();
        final Submission<S, T> submission = new Submission<S, T>(generation, result, action);
        result.pending.set(submission);
        try {
            submission.scheduler = generation.call(() -> {
                Scope owner = generation.view();
                owner.install(result);
                return owner.requireCapability(SchedulerFactory.class).forScope(owner);
            });
            // The external source retains only this clearable cell, never the scope or user action.
            final AtomicReference<Submission<S, T>> reference = result.pending;
            source.whenComplete((value, failure) -> {
                Submission<S, T> current = reference.get();
                if (current != null) current.accept(value, failure);
            });
        } catch (Throwable rejection) {
            submission.reject(rejection);
        }
        return result;
    }

    @Override
    public boolean complete(T value) {
        Submission<S, T> released = pending.getAndSet(null);
        try { return super.complete(value); }
        finally { if (released != null) released.release(); }
    }

    @Override
    public boolean completeExceptionally(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Submission<S, T> released = pending.getAndSet(null);
        try { return super.completeExceptionally(failure); }
        finally { if (released != null) released.release(); }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        Submission<S, T> released = pending.getAndSet(null);
        try { return super.cancel(false); }
        finally { if (released != null) released.release(); }
    }

    @Override
    public void obtrudeValue(T value) {
        Submission<S, T> released = pending.getAndSet(null);
        try { super.obtrudeValue(value); }
        finally { if (released != null) released.release(); }
    }

    @Override
    public void obtrudeException(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Submission<S, T> released = pending.getAndSet(null);
        try { super.obtrudeException(failure); }
        finally { if (released != null) released.release(); }
    }

    @Override
    public void dispose() {
        cancel(false);
    }

    private static final class Submission<S, T> {
        private final ScopeGeneration generation;
        private final ScopedFuture<S, T> result;
        private final Function<? super S, ? extends T> action;
        private final AtomicReference<TaskHandle> queued = new AtomicReference<TaskHandle>();
        private KScheduler scheduler;
        private S value;

        private Submission(ScopeGeneration generation, ScopedFuture<S, T> result,
                Function<? super S, ? extends T> action) {
            this.generation = generation;
            this.result = result;
            this.action = action;
        }

        private void accept(S value, Throwable failure) {
            if (result.isDone()) return;
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            try {
                generation.call(() -> {
                    if (!result.isDone()) {
                        this.value = value;
                        // A cancelled queue entry also retains only the clearable cell.
                        final AtomicReference<Submission<S, T>> reference = result.pending;
                        TaskHandle handle = scheduler.sync(() -> {
                            Submission<S, T> current = reference.get();
                            if (current != null) current.run();
                        });
                        queued.set(handle);
                        if (handle.isCancelled()) result.cancel(false);
                        if (result.isDone()) release();
                    }
                    return null;
                });
            } catch (Throwable rejection) {
                reject(rejection);
            }
        }

        private void run() {
            boolean accepted = generation.run(() -> {
                if (result.isDone()) return;
                try {
                    result.complete(action.apply(value));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            if (!accepted) result.cancel(false);
        }

        private void reject(Throwable failure) {
            if (generation.isActive()) result.completeExceptionally(failure);
            else result.cancel(false);
        }

        private void release() {
            generation.view().remove(result);
            TaskHandle handle = queued.getAndSet(null);
            if (handle != null) handle.cancel();
        }
    }
}
