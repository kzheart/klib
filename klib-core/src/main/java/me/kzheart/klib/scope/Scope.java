package me.kzheart.klib.scope;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.kzheart.klib.command.api.CommandCapability;
import me.kzheart.klib.command.api.CommandRegistration;
import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.event.KEventDispatcher;
import me.kzheart.klib.scheduler.AsyncTask;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.capability.ConfigCapability;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;

/**
 * 持有资源，并按注册顺序的逆序释放资源。
 */
public interface Scope extends Disposable {

    String name();

    boolean isClosed();

    <T extends Disposable> T install(T resource);

    /**
     * 分离先前安装的资源，但不释放它，从而避免已终止的资源在长生命周期作用域中累积。
     * 当资源不存在或作用域生命周期正在变化时不执行任何操作。
     */
    void remove(Disposable resource);

    Scope scope(String name, Consumer<? super Scope> configure);

    /**
     * 在当前作用域注册一项能力。同一作用域内同一类型只能注册一次，重复注册抛出
     * {@link IllegalStateException}；子作用域可以注册同类型能力以遮蔽父作用域的实现。
     */
    <T> T registerCapability(Class<T> type, T capability);

    /** 自当前作用域向父作用域逐级查找能力，未找到时返回空。 */
    <T> Optional<T> findCapability(Class<T> type);

    /**
     * 与 {@link #findCapability} 相同的查找规则，但未找到时抛出 {@link IllegalStateException}。
     * 该异常表示对应模块尚未安装到本作用域或其任一父作用域。
     */
    <T> T requireCapability(Class<T> type);

    default <E extends Event> Disposable on(
            Class<E> eventType,
            Consumer<? super E> handler
    ) {
        return requireCapability(KEventDispatcher.class).on(this, eventType, handler);
    }

    default <E extends Event> Disposable on(
            Class<E> eventType,
            EventPriority priority,
            boolean ignoreCancelled,
            Consumer<? super E> handler
    ) {
        return requireCapability(KEventDispatcher.class).on(
                this,
                eventType,
                priority,
                ignoreCancelled,
                handler);
    }

    default TaskHandle every(Ticks period, Runnable task) {
        return requireCapability(SchedulerFactory.class).forScope(this).every(period, task);
    }

    default TaskHandle after(Ticks delay, Runnable task) {
        return requireCapability(SchedulerFactory.class).forScope(this).after(delay, task);
    }

    /**
     * 在异步线程执行提供器；结果需通过 {@link AsyncTask#thenSync} 回到主线程后才能访问 Bukkit 状态。
     */
    default <T> AsyncTask<T> async(Supplier<T> supplier) {
        return requireCapability(SchedulerFactory.class).forScope(this).async(supplier);
    }

    /**
     * 把任务投递到服务器主线程执行，可从任意线程调用。
     * 用于在异步回调（包括 JDK {@link java.util.concurrent.CompletionStage} 的回调）中安全访问 Bukkit API。
     */
    default TaskHandle sync(Runnable task) {
        return requireCapability(SchedulerFactory.class).forScope(this).sync(task);
    }

    /**
     * 返回投递到主线程的 {@link Executor}，用于与 JDK {@link java.util.concurrent.CompletionStage}
     * 组合，例如 {@code stage.thenAcceptAsync(action, scope.syncExecutor())}。
     * 作用域关闭后提交的命令不再执行。
     */
    default Executor syncExecutor() {
        return requireCapability(SchedulerFactory.class).forScope(this).syncExecutor();
    }

    default CommandRegistration command(
            String name,
            Consumer<? super CommandSpec> configure
    ) {
        CommandCapability capability = findCapability(CommandCapability.class).orElseThrow(
                () -> new IllegalStateException(
                        "Scope.command requires the klib-command module"));
        return capability.register(this, name, configure);
    }

    default <T> ConfigDocument<T> config(Class<T> type, String path) {
        ConfigCapability capability = findCapability(ConfigCapability.class).orElseThrow(
                () -> new IllegalStateException(
                        "Scope.config requires the klib-config module"));
        return capability.load(this, type, path);
    }

    /**
     * 逆序释放当前资源，再重新执行创建根作用域时的初始化逻辑。整个过程在生命周期锁内串行执行，
     * 因此重建期间其他线程的注册与查找操作会等待。
     */
    void rebuild();

    /** 等价于 {@link #close()}，使作用域可以作为父作用域的一项 {@link Disposable} 资源被释放。 */
    @Override
    void dispose();

    /**
     * 释放作用域及其子作用域。已关闭的作用域再次调用无副作用；但在关闭或重建尚未完成时调用会抛出
     * {@link IllegalStateException}，例如在自身释放回调里再次关闭同一作用域。
     */
    void close();
}
