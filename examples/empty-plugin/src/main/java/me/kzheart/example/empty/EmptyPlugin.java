package me.kzheart.example.empty;

import me.kzheart.klib.KPlugin;
import me.kzheart.klib.command.BukkitCommandRegistrar;
import me.kzheart.klib.command.BukkitPlayerResolver;
import me.kzheart.klib.command.CommandBuiltins;
import me.kzheart.klib.command.CommandMessageKeys;
import me.kzheart.klib.command.CommandMessages;
import me.kzheart.klib.command.CommandModule;
import me.kzheart.klib.command.DefaultCommandMessages;
import me.kzheart.klib.command.SpigotRichTextSink;
import me.kzheart.klib.config.ConfigModule;
import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.lang.LangModule;
import me.kzheart.klib.lang.LangRuntime;
import me.kzheart.klib.lang.MessageRecipient;
import me.kzheart.klib.lang.MessageRoute;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 覆盖完整 M1 Scope 接口面的可执行冒烟测试插件。 */
public final class EmptyPlugin extends KPlugin {
    static final String ROOT_COMMAND = "klibm1";
    static final String READY_MARKER = "[klib-m1] ready";
    private final AtomicBoolean debug = new AtomicBoolean();
    private final AtomicInteger setupRuns = new AtomicInteger();

    @Override
    protected void setup(Scope root) {
        root.install(new LifecycleProbe());
        ConfigModule.install(root, getDataFolder().toPath(), getClassLoader(), "defaults");
        LangRuntime lang = LangModule.install(
                root,
                getServer(),
                getDataFolder().toPath(),
                getClassLoader(),
                "zh_CN",
                null);
        CommandModule.install(
                root,
                BukkitCommandRegistrar.discover("klib"),
                BukkitPlayerResolver.INSTANCE,
                SpigotRichTextSink.INSTANCE,
                commandMessages());

        ConfigDocument<EmptyPluginSettings> config = root.config(
                EmptyPluginSettings.class,
                "config.yml");
        config.onChange(root::rebuild);

        root.scope("runtime", runtime -> installRuntime(runtime, config, lang));
        root.command(ROOT_COMMAND, command -> {
            command.description("klib M1 smoke command");
            CommandBuiltins.standard(config::reload, debug::get, debug::set).install(command);
            command.literal("status", status -> status.executes(context -> lang.send(
                    MessageRecipient.commandSender(context.sender()),
                    MessageRoute.CHAT,
                    "empty.status",
                    Collections.<String, Object>singletonMap(
                            "debug",
                            Boolean.valueOf(debug.get())))));
            for (int index = 1; index <= 4; index++) {
                final int probe = index;
                command.literal("probe" + index, child -> {
                    child.description("M1 help interaction probe " + probe);
                    child.executes(context -> logger().info(
                            "[klib-m1] probe=" + probe + " command-ok"));
                });
            }
        });

        getLogger().info(lifecycleMarker(setupRuns.incrementAndGet()));
    }

    static CommandMessages commandMessages() {
        return (sender, key, placeholders) -> {
            if (CommandMessageKeys.HELP_HEADER.equals(key)) {
                return me.kzheart.klib.lang.RichText.plain(
                        "命令帮助 " + placeholders.get("page") + "/" + placeholders.get("pages"));
            }
            if (CommandMessageKeys.BUILTIN_RELOAD_SUCCESS.equals(key)) {
                return me.kzheart.klib.lang.RichText.plain("配置已重新加载");
            }
            return DefaultCommandMessages.INSTANCE.resolve(sender, key, placeholders);
        };
    }

    static String lifecycleMarker(int setupRun) {
        if (setupRun < 1) {
            throw new IllegalArgumentException("setupRun must be positive");
        }
        return setupRun == 1
                ? READY_MARKER
                : "[klib-m1] rebuild=" + (setupRun - 1) + " resources-ok";
    }

    private void installRuntime(
            Scope runtime,
            ConfigDocument<EmptyPluginSettings> config,
            LangRuntime lang
    ) {
        runtime.on(PlayerJoinEvent.class, event -> lang.send(
                MessageRecipient.commandSender(event.getPlayer()),
                MessageRoute.CHAT,
                "empty.join",
                Collections.<String, Object>singletonMap(
                        "player",
                        event.getPlayer().getName())));
        runtime.every(Ticks.of(config.value().heartbeatTicks), () -> {
            if (debug.get()) {
                logger().info("empty-plugin heartbeat");
            }
        });
        runtime.after(Ticks.of(1L), () -> logger().info("[klib-m1] runtime-ready"));
        runtime.async(() -> "async-ready")
                .thenSync(value -> logger().info("[klib-m1] " + value));
    }

    static final class LifecycleProbe implements Disposable {
        private boolean disposed;

        @Override
        public void dispose() {
            disposed = true;
        }

        boolean isDisposed() {
            return disposed;
        }
    }
}
