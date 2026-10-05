package me.kzheart.klib.command;

import java.util.Locale;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.KPlugin;
import me.kzheart.klib.command.api.CommandCapability;
import me.kzheart.klib.command.api.CommandErrorHandler;
import me.kzheart.klib.lang.MessagePipeline;
import me.kzheart.klib.scope.Scope;

/**
 * 命令模块入口：向作用域安装 {@link CommandCapability}，之后即可用
 * {@code scope.command(name, spec -> ...)} 在插件启动阶段声明命令树。
 *
 * <p>使用 {@link KPlugin} 时，在 {@code setup()} 中安装与声明；该入口由插件
 * {@code onEnable} 阶段调用。支持公开生命周期 API 的 Paper 使用命令生命周期
 * 注册原始参数执行入口；其他 Bukkit/Paper 保留启动期 CommandMap 注册。</p>
 *
 * <p>配置重载只更新语言及业务状态，不重建命令。作用域关闭立即禁止已注册绑定的
 * 执行、补全与权限检查；物理节点可能保留到服务端生命周期重建或重启，不承诺
 * 运行时新增根、即时物理注销或恢复被覆盖的标签。同一插件不得将相同标签交给
 * Klib 与其他注册器共同管理。</p>
 *
 * <p>需要与插件语言文件共用消息时，传入 {@link MessagePipeline}；需要自定义
 * 玩家解析、富文本输出或消息目录时，使用带 {@link PlayerResolver}、
 * {@link RichTextSink} 与 {@link CommandMessages} 的重载。</p>
 *
 * <p>安装与声明必须在服务器主线程完成。宿主能力发现不改变公共 Java 8 边界。</p>
 */
public final class CommandModule {
    public static CommandCapability install(KPlugin plugin) {
        return install(plugin.context().scope(), BukkitCommandRegistrar.discover(plugin, plugin.getName().toLowerCase(Locale.ROOT)));
    }

    public static CommandCapability install(KPlugin plugin, CommandErrorHandler errors) {
        return install(plugin.context().scope(), BukkitCommandRegistrar.discover(plugin, plugin.getName().toLowerCase(Locale.ROOT)), errors);
    }

    private CommandModule() {
    }

    public static CommandCapability install(Scope scope, CommandBridge bridge) {
        return install(
                scope,
                bridge,
                BukkitPlayerResolver.INSTANCE,
                SpigotRichTextSink.INSTANCE,
                DefaultCommandMessages.INSTANCE);
    }

    /** 未知业务异常不会自动回显 exception.getMessage()；反馈内容由 errors 决定。 */
    public static CommandCapability install(Scope scope, CommandBridge bridge, CommandErrorHandler errors) {
        return install(scope, bridge, BukkitPlayerResolver.INSTANCE, SpigotRichTextSink.INSTANCE, DefaultCommandMessages.INSTANCE, errors);
    }

    public static CommandCapability install(Scope scope, CommandBridge bridge, MessagePipeline messages, CommandErrorHandler errors) {
        if (messages == null) throw new NullPointerException("messages");
        return install(scope, bridge, BukkitPlayerResolver.INSTANCE, SpigotRichTextSink.INSTANCE, new MessagePipelineCommandMessages(messages), errors);
    }

    /** 安装使用插件同一条可重新加载语言管线的命令。 */
    public static CommandCapability install(
            Scope scope,
            CommandBridge bridge,
            MessagePipeline messages
    ) {
        if (messages == null) {
            throw new NullPointerException("messages");
        }
        return install(
                scope,
                bridge,
                BukkitPlayerResolver.INSTANCE,
                SpigotRichTextSink.INSTANCE,
                new MessagePipelineCommandMessages(messages));
    }

    public static CommandCapability install(
            Scope scope,
            CommandBridge bridge,
            PlayerResolver players,
            RichTextSink output,
            CommandMessages messages
    ) {
        return installWithErrors(scope, bridge, players, output, messages, null);
    }

    public static CommandCapability install(Scope scope, CommandBridge bridge, PlayerResolver players,
                                            RichTextSink output, CommandMessages messages, CommandErrorHandler errors) {
        if (errors == null) throw new NullPointerException("errors");
        return installWithErrors(scope, bridge, players, output, messages, errors);
    }

    private static CommandCapability installWithErrors(Scope scope, CommandBridge bridge, PlayerResolver players,
                                                        RichTextSink output, CommandMessages messages, CommandErrorHandler errors) {
        if (scope == null) {
            throw new NullPointerException("scope");
        }
        CommandCapability capability = new CommandCapabilityImpl(
                bridge,
                players,
                output,
                messages,
                scope.findCapability(KLogger.class).orElse(null), errors);
        return scope.registerCapability(CommandCapability.class, capability);
    }
}
