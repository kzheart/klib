package me.kzheart.klib.command;

import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.command.api.CommandSpec;

public interface CommandBridge {
    /**
     * 注册命令并返回可注销句柄。
     *
     * <p>线程约束：注册必须在服务端主线程调用。返回的 {@link Disposable#dispose()}
     * 可以从其他线程调用，但返回时注销必须已经完成；实现需要编组到主线程并等待。</p>
     */
    Disposable register(String name, CommandSpec spec, CommandDispatcher dispatcher);
}
