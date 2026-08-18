package me.kzheart.klib.scheduler;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

public interface KScheduler {
    TaskHandle every(Ticks period, Runnable task);

    TaskHandle after(Ticks delay, Runnable task);

    /**
     * 在异步线程中执行提供器，回调通过同步执行器派发。
     * 提供器所在线程不是服务器主线程，不能在其中访问 Bukkit 状态。
     */
    <T> AsyncTask<T> async(Supplier<T> supplier);

    /**
     * 把任务投递到同步执行器（Bukkit 实现即服务器主线程）执行，可从任意线程调用。
     * 任务不会立即运行，最早在下一 tick 执行；返回的句柄随作用域关闭一起取消。
     *
     * <p>默认实现退化为 {@code after(Ticks.of(0), task)}，供没有独立主线程概念的
     * 自定义实现使用；真正区分主线程的实现应覆盖本方法。
     */
    default TaskHandle sync(Runnable task) {
        return after(Ticks.of(0), task);
    }

    /**
     * 返回把命令投递到同步执行器的 {@link Executor}，用于与 JDK
     * {@link java.util.concurrent.CompletionStage} 组合，例如
     * {@code stage.thenAcceptAsync(action, scope.syncExecutor())}。
     *
     * <p>所属作用域关闭后提交的命令会被丢弃或取消，不会在已关闭的作用域上继续执行。
     */
    default Executor syncExecutor() {
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                if (command == null) {
                    throw new NullPointerException("command");
                }
                sync(command);
            }
        };
    }
}
