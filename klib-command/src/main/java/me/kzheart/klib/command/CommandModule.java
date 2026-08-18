package me.kzheart.klib.command;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.command.api.CommandCapability;
import me.kzheart.klib.lang.MessagePipeline;
import me.kzheart.klib.scope.Scope;

/**
 * 命令模块入口：向作用域安装 {@link CommandCapability}，之后即可用
 * {@code scope.command(name, spec -> ...)} 注册命令树。
 *
 * <p>能力随作用域生命周期存在：作用域关闭或重建时，已注册命令会自动从 Bukkit 注销。
 * 需要与插件语言文件共用消息时，传入 {@link MessagePipeline}；需要自定义玩家解析、
 * 富文本输出或消息目录时，使用带 {@link PlayerResolver}、{@link RichTextSink} 与
 * {@link CommandMessages} 的重载。
 *
 * <p>安装必须在服务器主线程完成。
 */
public final class CommandModule {
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
        if (scope == null) {
            throw new NullPointerException("scope");
        }
        CommandCapability capability = new CommandCapabilityImpl(
                bridge,
                players,
                output,
                messages,
                scope.findCapability(KLogger.class).orElse(null));
        return scope.registerCapability(CommandCapability.class, capability);
    }
}
