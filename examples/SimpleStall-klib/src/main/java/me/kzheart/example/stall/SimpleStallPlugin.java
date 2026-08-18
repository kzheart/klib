package me.kzheart.example.stall;

import me.kzheart.klib.KPlugin;
import me.kzheart.klib.command.Arg;
import me.kzheart.klib.command.Arguments;
import me.kzheart.klib.command.BukkitCommandRegistrar;
import me.kzheart.klib.command.BukkitPlayerResolver;
import me.kzheart.klib.command.CommandBuiltins;
import me.kzheart.klib.command.CommandModule;
import me.kzheart.klib.command.DefaultCommandMessages;
import me.kzheart.klib.command.SpigotRichTextSink;
import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.config.ConfigModule;
import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.item.Items;
import me.kzheart.klib.scheduler.AsyncTasks;
import me.kzheart.klib.script.KetherScriptEngine;
import me.kzheart.klib.script.ScriptContext;
import me.kzheart.klib.script.StatementRegistry;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.ui.MenuCompiler;
import me.kzheart.klib.ui.MenuTemplate;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 原 SimpleStall 插件的 Java 8 并行迁移外壳。 */
public final class SimpleStallPlugin extends KPlugin {
    private final AtomicBoolean debug = new AtomicBoolean();
    private final AtomicInteger setupRuns = new AtomicInteger();
    private final StatementRegistry statements = new StatementRegistry();
    /** 引擎与语句注册表按插件实例创建一次；每条命令新建引擎会重复安装内置语句并浪费初始化。 */
    private volatile KetherScriptEngine scriptEngine;

    @Override
    protected void setup(final Scope root) {
        ConfigModule.install(root, getDataFolder().toPath(), getClassLoader(), "defaults");
        CommandModule.install(
                root,
                BukkitCommandRegistrar.discover("klib"),
                BukkitPlayerResolver.INSTANCE,
                SpigotRichTextSink.INSTANCE,
                DefaultCommandMessages.INSTANCE);

        ConfigDocument<StallSettings> config = root.config(StallSettings.class, "config.yml");
        config.onChange(root::rebuild);
        debug.set(config.value().debug);

        StallPersistence persistence = root.install(new StallPersistence(
                getDataFolder().toPath().resolve("stall-data.json"), logger()));
        StallRuntime runtime = root.install(new StallRuntime(new StallLedger(), persistence));
        StallMenus menus = root.install(new StallMenus(
                this, root, runtime, config.value(), logger()));
        if (scriptEngine == null) {
            // 续接执行器每次都向当前根作用域取，重载后仍指向有效的主线程调度器。
            scriptEngine = new KetherScriptEngine(statements, null, new Executor() {
                @Override
                public void execute(Runnable command) {
                    root.syncExecutor().execute(command);
                }
            });
        }
        registerCommand(root, StallContract.ROOT_COMMAND, config, runtime, menus);
        registerCommand(root, StallContract.COMMAND_ALIAS, config, runtime, menus);

        int run = setupRuns.incrementAndGet();
        logger().info(run == 1
                ? "[klib-m3] ready"
                : "[klib-m3] rebuild=" + (run - 1) + " resources-ok");
    }

    private void registerCommand(
            final Scope root,
            String name,
            final ConfigDocument<StallSettings> config,
            final StallRuntime runtime,
            final StallMenus menus
    ) {
        root.command(name, command -> configureCommand(command, root, config, runtime, menus));
    }

    private void configureCommand(
            CommandSpec command,
            final Scope root,
            ConfigDocument<StallSettings> config,
            StallRuntime runtime,
            StallMenus menus
    ) {
        command.description("SimpleStall M3 migration command")
                .permission(StallContract.USE_PERMISSION);
        CommandBuiltins.standardAsync(
                StallContract.ADMIN_PERMISSION,
                config::reloadAsync,
                debug::get,
                debug::set).install(command);
        command.literal("manage", child -> child.playerOnly().executes(context ->
                menus.openManage((Player) context.sender())));

        Arg<String> seller = Arguments.string("seller");
        command.literal("shop", child -> child.playerOnly().argument(
                seller,
                target -> target.executes(context -> menus.openShop(
                        (Player) context.sender(), context.get(seller)))));

        command.literal("stall", child -> {
            child.literal("start", start -> start.playerOnly().executes(context ->
                    context.sender().sendMessage("开始摆摊，剩余时间: 10 分钟")));
            child.literal("stop", stop -> stop.playerOnly().executes(context ->
                    context.sender().sendMessage("停止摆摊，剩余时间: 10 分钟")));
        });

        command.literal("fixture", fixture -> fixture.permission(StallContract.ADMIN_PERMISSION)
                .playerOnly().literal("seed", seed -> seed.executes(context -> {
                    Player player = (Player) context.sender();
                    StallListing listing = menus.seedFixture(player);
                    player.sendMessage("M3 测试商品已创建: " + listing.id());
                    logger().info("[klib-m3] fixture-ready id=" + listing.id());
                })));
        command.literal("probe", probe -> probe.permission(StallContract.ADMIN_PERMISSION)
                .playerOnly().executes(context -> {
                    Player player = (Player) context.sender();
                    BigDecimal balance = runtime.ledger().balance(player.getUniqueId());
                    player.sendMessage("M3 listings=" + runtime.size()
                            + " balance=" + balance.toPlainString());
                }));
        command.literal("m5", probe -> probe.permission(StallContract.ADMIN_PERMISSION)
                .playerOnly().executes(context -> {
                    Items.of(Material.STONE).build();
                    MenuCompiler.compile(MenuTemplate.builder("M5", 1).build());
                    // 命令处理器在主线程执行，绝不能对脚本阶段 join()；
                    // 用 AsyncTasks.thenSync 把结果切回主线程后再反馈玩家。
                    AsyncTasks.thenSync(
                            scriptEngine.eval(
                                    "literal m5-script-ok", ScriptContext.builder().build()),
                            root,
                            script -> {
                                context.sender().sendMessage("M5 全栈检查完成: " + script);
                                logger().info("[klib-m5] full-stack-ok script=" + script);
                            },
                            failure -> logger().error("[klib-m5] full-stack-failed", failure));
                }));
    }
}
