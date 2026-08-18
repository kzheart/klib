package me.kzheart.klib.scheduler;

import java.util.function.Consumer;

/**
 * 由 {@code Scope.async} 创建的异步任务，回调统一在同步执行器（Bukkit 实现即服务器主线程）上派发。
 *
 * <p>注意区别于 JDK {@link java.util.concurrent.CompletionStage}：其
 * {@code thenAccept} 等非 {@code Async} 回调运行在完成阶段的线程，不会自动切回主线程。
 * 处理 {@code klib-data}、{@code klib-script}、{@code klib-remote} 返回的
 * {@code CompletionStage} 时，请使用 {@link AsyncTasks} 或
 * {@link me.kzheart.klib.scope.Scope#syncExecutor()} 显式桥接。
 */
public interface AsyncTask<T> extends TaskHandle {
    /**
     * 注册一个在同步执行器上接收结果的回调。
     * 任务失败或取消时不调用该回调。
     */
    AsyncTask<T> thenSync(Consumer<? super T> callback);

    /**
     * 注册一个在同步执行器上接收失败信息的回调。
     * 即使任务已经失败，之后注册的回调仍会执行。
     * 任务正常完成或取消时不调用该回调。
     */
    AsyncTask<T> onError(Consumer<? super Throwable> callback);

    /**
     * 返回提供器是否因异常而终止。失败的任务也会让
     * {@link #isDone()} 返回 {@code true}。
     */
    boolean isFailed();
}
