package me.kzheart.klib.scheduler;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import me.kzheart.klib.scope.Scope;

/**
 * 把 JDK {@link CompletionStage} 桥接回服务器主线程的工具。
 *
 * <p>{@code klib-data}、{@code klib-script} 和 {@code klib-remote} 等模块返回的
 * {@link CompletionStage}，其 {@code thenApply}、{@code thenAccept} 等非 {@code Async}
 * 回调运行在完成该阶段的线程（例如 JDBC、文件 I/O 或诊断线程），不是服务器主线程。
 * 在这些回调里直接调用 Bukkit API 会导致并发问题甚至崩溃。使用本类或
 * {@link Scope#syncExecutor()} 显式切回主线程后再访问 Bukkit 状态。
 */
public final class AsyncTasks {

    private AsyncTasks() {
    }

    /**
     * 在主线程消费阶段结果。阶段失败时不调用 {@code action}。
     *
     * <p>返回的阶段保留原有结果与失败信息，可继续链式组合；但后续非 {@code Async}
     * 回调仍运行在主线程之外，需要再次桥接。
     */
    public static <T> CompletionStage<T> thenSync(
            CompletionStage<T> stage,
            Scope scope,
            Consumer<? super T> action
    ) {
        return thenSync(stage, scope, action, null);
    }

    /**
     * 在主线程消费阶段结果或失败。成功时只调用 {@code action}，失败时只调用
     * {@code onError}（若为 {@code null} 则忽略失败）。传给 {@code onError} 的异常
     * 已经过 {@link #unwrap(Throwable)} 解包。
     *
     * <p>作用域关闭后两类回调都不会执行。
     */
    public static <T> CompletionStage<T> thenSync(
            CompletionStage<T> stage,
            final Scope scope,
            final Consumer<? super T> action,
            final Consumer<? super Throwable> onError
    ) {
        require(stage, scope);
        if (action == null) {
            throw new NullPointerException("action");
        }
        return stage.whenCompleteAsync(new BiConsumer<T, Throwable>() {
            @Override
            public void accept(T value, Throwable failure) {
                if (scope.isClosed()) {
                    return;
                }
                if (failure != null) {
                    if (onError != null) {
                        onError.accept(unwrap(failure));
                    }
                    return;
                }
                action.accept(value);
            }
        }, scope.syncExecutor());
    }

    /**
     * 返回一个在主线程完成的等价阶段，使其后续的非 {@code Async} 回调也运行在主线程。
     * 适合需要多步组合、不想每步都传执行器的场景。
     */
    public static <T> CompletionStage<T> onSync(CompletionStage<T> stage, Scope scope) {
        require(stage, scope);
        return stage.thenApplyAsync(Function.<T>identity(), scope.syncExecutor());
    }

    /**
     * 解开 {@link CompletionException} 与 {@link ExecutionException} 的包装，返回原始失败原因。
     */
    public static Throwable unwrap(Throwable failure) {
        if (failure == null) {
            throw new NullPointerException("failure");
        }
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static void require(CompletionStage<?> stage, Scope scope) {
        if (stage == null) {
            throw new NullPointerException("stage");
        }
        if (scope == null) {
            throw new NullPointerException("scope");
        }
    }
}
