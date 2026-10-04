package me.kzheart.klib.command;

import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.command.api.CommandSpec;

public interface CommandBridge {
    /**
     * 注册命令并返回可注销句柄。
     *
     * <p>线程约束：注册必须在服务端主线程调用。返回的 {@link Disposable#dispose()}
     * 可以从其他线程调用。关闭必须立即禁止执行；Paper 异步构建器存在时，
     * 实际节点注销在服务端主线程安全窗口中完成，不阻塞主线程等待构建器。</p>
     */
    Disposable register(String name, CommandSpec spec, CommandDispatcher dispatcher);
}
