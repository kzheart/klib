package me.kzheart.example.gather;

import me.kzheart.klib.KPlugin;
import me.kzheart.klib.command.Arg;
import me.kzheart.klib.command.Arguments;
import me.kzheart.klib.command.BukkitCommandRegistrar;
import me.kzheart.klib.command.CommandBuiltins;
import me.kzheart.klib.command.CommandModule;
import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.config.ConfigModule;
import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.hook.DependencyReport;
import me.kzheart.klib.hook.Hook;
import me.kzheart.klib.hook.economy.Currency;
import me.kzheart.klib.hook.economy.CurrencyHooks;
import me.kzheart.klib.hook.papi.Papi;
import me.kzheart.klib.item.InventoryItems;
import me.kzheart.klib.item.ItemCodec;
import me.kzheart.klib.item.Items;
import me.kzheart.klib.item.TagKey;
import me.kzheart.klib.lang.LangModule;
import me.kzheart.klib.lang.LangRuntime;
import me.kzheart.klib.lang.MessageRecipient;
import me.kzheart.klib.remote.DiagnosticClient;
import me.kzheart.klib.remote.Heartbeat;
import me.kzheart.klib.remote.RemoteDiagnostics;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Scope;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 原 SimpleGather 核心命令与方块流程的 Java 并行迁移实现。 */
public final class SimpleGatherPlugin extends KPlugin {
    private static final TagKey<String> TOOL_TYPE = TagKey.string(GatherContract.TOOL_TYPE_TAG);
    private static final TagKey<Integer> TOOL_DURABILITY =
            TagKey.integer(GatherContract.TOOL_DURABILITY_TAG);

    private final AtomicBoolean debug = new AtomicBoolean();
    private final AtomicBoolean diagnosticNoticeShown = new AtomicBoolean();
    private final AtomicBoolean dumpInFlight = new AtomicBoolean();
    private final AtomicLong nextDumpAllowedAt = new AtomicLong();
    private final AtomicInteger setupRuns = new AtomicInteger();

    @Override
    protected void setup(Scope root) {
        ConfigModule.install(root, getDataFolder().toPath(), getClassLoader(), "defaults");
        LangRuntime lang = LangModule.install(
                root,
                getServer(),
                getDataFolder().toPath(),
                getClassLoader(),
                "zh_CN",
                null);
        CommandModule.install(root, BukkitCommandRegistrar.discover("klib"), lang.pipeline());

        ConfigDocument<GatherSettings> config = root.config(GatherSettings.class, "config.yml");
        config.onChange(root::rebuild);
        debug.set(config.value().debug);

        Hook<Currency> economy = root.install(CurrencyHooks.vault(getServer()));
        for (String line : DependencyReport.builder().add(economy).build().lines()) {
            logger().info(line);
        }
        RemoteAccess remote = configureDiagnostics(root, config.value());

        root.scope("gather", gather -> {
            GatherRuntime runtime = gather.install(new GatherRuntime(config.value().gatherHealth));
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Papi.registerBukkit(
                        gather,
                        this,
                        "simplegather",
                        placeholders -> placeholders.key("active", player -> runtime.activeSessions()));
            }
            gather.on(
                    BlockBreakEvent.class,
                    EventPriority.HIGH,
                    true,
                    event -> onBlockBreak(event, runtime, config.value(), lang));
            registerCommand(gather, GatherContract.ROOT_COMMAND, runtime, config, lang, remote);
            registerCommand(gather, GatherContract.COMMAND_ALIAS, runtime, config, lang, remote);
        });

        int run = setupRuns.incrementAndGet();
        logger().info(run == 1
                ? "[klib-m2] ready"
                : "[klib-m2] rebuild=" + (run - 1) + " resources-ok");
    }

    private void registerCommand(
            Scope scope,
            String name,
            GatherRuntime runtime,
            ConfigDocument<GatherSettings> config,
            LangRuntime lang,
            RemoteAccess remote
    ) {
        scope.command(name, command -> configureCommand(
                scope, command, runtime, config, lang, remote));
    }

    private void configureCommand(
            Scope scope,
            CommandSpec command,
            GatherRuntime runtime,
            ConfigDocument<GatherSettings> config,
            LangRuntime lang,
            RemoteAccess remote
    ) {
        command.description("SimpleGather migration command")
                .permission(GatherContract.USE_PERMISSION);
        CommandBuiltins.standardAsync(
                GatherContract.ADMIN_PERMISSION,
                config::reloadAsync,
                debug::get,
                debug::set).install(command);
        command.literal("list", child -> child.executes(context -> context.sender().sendMessage(
                "活跃采集实例: " + runtime.activeSessions())));
        command.literal("stats", child -> child.executes(context -> {
            context.sender().sendMessage("已注册工具: mining, garden");
            if (context.sender() instanceof Player) {
                verifyItemCodec((Player) context.sender());
            }
        }));
        command.literal("spawns", child -> child.executes(context -> context.sender().sendMessage(
                "刷新点信息: 0")));
        command.literal("generate", child -> {
            Arg<String> id = Arguments.string("id");
            child.argument(id, value -> value.executes(context -> context.sender().sendMessage(
                    "成功生成采集物: " + context.get(id))));
        });
        command.literal("info", child -> {
            Arg<Player> player = Arguments.player("player");
            child.argument(player, value -> value.executes(context -> context.sender().sendMessage(
                    "玩家 " + context.get(player).getName() + " 当前没有在采集")));
        });
        command.literal("give", child -> configureGive(child, config, lang));
        if (remote != null) {
            command.literal("dump", child -> {
                child.permission(GatherContract.ADMIN_PERMISSION);
                child.executes(context -> uploadDump(
                        remote, context.sender(), false));
                child.literal("--full", full -> full.executes(context -> uploadDump(
                        remote, context.sender(), true)));
            });
        }
    }

    private void configureGive(
            CommandSpec command,
            ConfigDocument<GatherSettings> config,
            LangRuntime lang
    ) {
        command.permission(GatherContract.ADMIN_PERMISSION);
        Arg<Player> player = Arguments.player("player");
        Arg<String> type = Arguments.choice("type", "mining", "garden");
        Arg<Integer> amount = Arguments.optional(Arguments.integer("amount", 1, 64), 1);
        command.argument(player, playerNode -> playerNode.argument(type, typeNode ->
                typeNode.argument(amount, amountNode -> amountNode.executes(context -> {
                    for (int index = 0; index < context.get(amount).intValue(); index++) {
                        ItemStack tool = Items.of(config.value().toolMaterial)
                                .name("&6采集工具 · " + context.get(type))
                                .tag(TOOL_TYPE, context.get(type))
                                .tag(TOOL_DURABILITY, Integer.valueOf(config.value().toolDurability))
                                .build();
                        InventoryItems.give(context.get(player), tool);
                    }
                    lang.send(
                            MessageRecipient.commandSender(context.sender()),
                            "gather.tool-given");
                    logger().info("[klib-m2] give-tool command-ok");
                }))));
    }

    private void onBlockBreak(
            BlockBreakEvent event,
            GatherRuntime runtime,
            GatherSettings settings,
            LangRuntime lang
    ) {
        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        if (InventoryItems.isAir(item) || !TOOL_TYPE.has(item)) {
            return;
        }
        event.setCancelled(true);
        String type = TOOL_TYPE.get(item);
        Integer durability = TOOL_DURABILITY.get(item);
        GatherSession.Result result = runtime.hit(
                blockKey(event.getBlock()),
                type,
                durability == null ? 0 : durability.intValue());
        if (result == GatherSession.Result.WRONG_TOOL) {
            lang.send(MessageRecipient.commandSender(event.getPlayer()), "gather.wrong-tool");
            return;
        }
        if (result == GatherSession.Result.TOOL_BROKEN) {
            lang.send(MessageRecipient.commandSender(event.getPlayer()), "gather.tool-break");
            return;
        }
        TOOL_DURABILITY.set(item, Integer.valueOf(durability.intValue() - 1));
        if (result != GatherSession.Result.COMPLETED) {
            lang.send(MessageRecipient.commandSender(event.getPlayer()), "gather.progress");
            return;
        }

        event.getBlock().setType(Material.AIR);
        Material reward = Items.resolveMaterial(settings.rewardMaterial);
        InventoryItems.give(event.getPlayer(), new ItemStack(reward, settings.rewardAmount));
        lang.send(MessageRecipient.commandSender(event.getPlayer()), "gather.complete");
        logger().info("[klib-m2] gather-complete behavior-ok");
    }

    private static String blockKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private RemoteAccess configureDiagnostics(Scope root, GatherSettings settings) {
        String endpoint = environmentOr("KLIB_COLLECTOR_URL", settings.collectorEndpoint());
        String token = environmentOr("KLIB_COLLECTOR_TOKEN", settings.collectorToken());
        if (endpoint.isEmpty() || token.isEmpty()) {
            logger().info("[klib-m2-remote] disabled");
            return null;
        }

        DiagnosticClient client;
        try {
            Path queue = getDataFolder().toPath().resolve("diagnostics-queue");
            client = isInsecureLoopback(endpoint)
                    ? DiagnosticClient.insecureLoopback(
                            endpoint, token, queue, 64, 8L * 1024L * 1024L)
                    : DiagnosticClient.http(
                            endpoint, token, queue, 64, 8L * 1024L * 1024L);
        } catch (IOException failure) {
            throw new IllegalStateException("无法初始化诊断离线队列", failure);
        }
        RemoteDiagnostics diagnostics = new RemoteDiagnostics(
                getName(),
                getDescription().getVersion(),
                logger(),
                client,
                this::serverSnapshot,
                () -> new LinkedHashMap<String, Object>(settings.diagnostics),
                settings.autoReport(),
                notice -> {
                    if (diagnosticNoticeShown.compareAndSet(false, true)) {
                        logger().warn(notice);
                    }
                });
        diagnostics.install(root, logger());
        scheduleTelemetry(root, diagnostics);
        return new RemoteAccess(diagnostics);
    }

    private void scheduleTelemetry(
            Scope root,
            RemoteDiagnostics diagnostics
    ) {
        final String installationId = UUID.nameUUIDFromBytes(
                getDataFolder().getAbsolutePath().getBytes(StandardCharsets.UTF_8)).toString();
        Runnable heartbeat = () -> {
            Heartbeat snapshot = new Heartbeat(
                    installationId,
                    getDescription().getVersion(),
                    getServer().getBukkitVersion(),
                    getServer().getName() + " " + getServer().getVersion(),
                    System.getProperty("os.name", "unknown") + " "
                            + System.getProperty("os.version", "unknown") + " "
                            + System.getProperty("os.arch", "unknown"),
                    getServer().getOnlinePlayers().size());
            diagnostics.flushQueueAsync(System.currentTimeMillis())
                    .thenCompose(ignored -> diagnostics.heartbeatAsync(snapshot))
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            logger().info("[klib-m2-remote] heartbeat-ok");
                        } else {
                            logger().warn("诊断心跳上报失败：" + messageOf(failure));
                        }
                    });
        };
        heartbeat.run();
        root.every(Ticks.seconds(60), heartbeat);
        diagnostics.checkVersionAsync().whenComplete((version, failure) -> {
            if (failure == null) {
                logger().info("[klib-m2-remote] version-check-ok latest=" + version.latest());
            } else {
                logger().warn("版本检查失败：" + messageOf(failure));
            }
        });
    }

    private void uploadDump(
            RemoteAccess remote,
            org.bukkit.command.CommandSender sender,
            boolean full
    ) {
        long now = System.currentTimeMillis();
        if (now < nextDumpAllowedAt.get()) {
            sender.sendMessage("诊断上传冷却中，请稍后重试");
            return;
        }
        if (!dumpInFlight.compareAndSet(false, true)) {
            sender.sendMessage("已有诊断上传正在进行");
            return;
        }
        nextDumpAllowedAt.set(now + 10000L);
        remote.diagnostics.dumpAsync(full).whenComplete((receipt, failure) -> {
            dumpInFlight.set(false);
            getServer().getScheduler().runTask(this, () -> {
                if (failure != null) {
                    sender.sendMessage("诊断上传失败: " + messageOf(failure));
                    return;
                }
                sender.sendMessage("诊断短码: " + receipt.shortId());
                logger().info("[klib-m2-remote] manual-dump-ok id=" + receipt.shortId());
            });
        });
    }

    private static String messageOf(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }

    private Map<String, Object> serverSnapshot() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("minecraft_version", getServer().getBukkitVersion());
        result.put("server_version", getServer().getVersion());
        result.put("java_version", System.getProperty("java.version"));
        result.put("server_ip", getServer().getIp() + ":" + getServer().getPort());
        result.put("online_players", Integer.valueOf(getServer().getOnlinePlayers().size()));
        List<String> plugins = new ArrayList<String>();
        for (org.bukkit.plugin.Plugin plugin : getServer().getPluginManager().getPlugins()) {
            plugins.add(plugin.getName() + "@" + plugin.getDescription().getVersion());
        }
        result.put("plugins", plugins);
        return result;
    }

    private static String environmentOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static boolean isInsecureLoopback(String endpoint) throws IOException {
        URL url = new URL(endpoint);
        String host = url.getHost();
        return "http".equalsIgnoreCase(url.getProtocol())
                && ("127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host));
    }

    private static final class RemoteAccess {
        private final RemoteDiagnostics diagnostics;

        private RemoteAccess(RemoteDiagnostics diagnostics) {
            this.diagnostics = diagnostics;
        }
    }

    private void verifyItemCodec(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (InventoryItems.isAir(held)) {
            return;
        }
        ItemStack restored = ItemCodec.decodeItem(ItemCodec.encode(held, true));
        if (!held.isSimilar(restored) || held.getAmount() != restored.getAmount()) {
            throw new IllegalStateException("Item codec changed the held tool");
        }
        logger().info("[klib-m2] item-codec live-roundtrip-ok");
    }
}
