package me.kzheart.klib.command;

import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.command.api.CommandSpec;

public interface CommandBridge {
    /**
     * 在插件启动阶段声明命令，并返回由所属作用域持有的逻辑停用句柄。
     *
     * <p>安装和声明必须在服务端主线程完成。支持公开命令生命周期 API 的 Paper
     * 使用生命周期注册原始参数入口；其他 Bukkit/Paper 使用启动期 CommandMap 注册。
     * 配置重载不应重新声明命令，不承诺运行时新增根命令。</p>
     *
     * <p>{@link Disposable#dispose()} 可从其他线程调用，并立即禁止该绑定的执行、
     * 补全与权限检查。物理节点可能保留到服务端生命周期重建或重启；关闭不保证
     * 即时物理注销，也不保证恢复此前被覆盖的标签。</p>
     */
    Disposable register(String name, CommandSpec spec, CommandDispatcher dispatcher);
}
