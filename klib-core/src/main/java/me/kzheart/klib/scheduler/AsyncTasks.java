package me.kzheart.klib.scheduler;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
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
 * {@link Scope#submitSync} 显式切回主线程后再访问 Bukkit 状态。
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
     * <p>等待源阶段与主线程排队都绑定当前作用域构建代。关闭或重建会取消返回结果，
     * 两类回调都不再执行；源阶段不会被取消，已发生的外部写入不会回滚。
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
        return scope.submitSync(stage.handle(Outcome<T>::new), outcome -> {
            if (outcome.failure == null) {
                action.accept(outcome.value);
            } else {
                if (onError != null) {
                    try {
                        onError.accept(unwrap(outcome.failure));
                    } catch (Throwable callbackFailure) {
                        if (callbackFailure != outcome.failure) {
                            outcome.failure.addSuppressed(callbackFailure);
                        }
                    }
                }
                if (outcome.failure instanceof CompletionException) {
                    throw (CompletionException) outcome.failure;
                }
                throw new CompletionException(outcome.failure);
            }
            return outcome.value;
        });
    }

    /**
     * 成功结果通过主线程完成；源阶段失败、关闭取消与提交拒绝不保证在主线程完成。
     * 后续非 Async 回调的线程仍由 JDK 决定，访问 Bukkit 时应再次使用 thenSync。
     * 关闭或重建会取消返回结果，不取消源阶段。
     */
    public static <T> CompletionStage<T> onSync(CompletionStage<T> stage, Scope scope) {
        require(stage, scope);
        return scope.submitSync(stage, Function.<T>identity());
    }

    private static final class Outcome<T> {
        private final T value;
        private final Throwable failure;

        private Outcome(T value, Throwable failure) {
            this.value = value;
            this.failure = failure;
        }
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
