package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.scope.Disposable;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/** 只投影玩家即将接收的树，不触碰服务端执行树或异步构建器。 */
public final class BrigadierBridge implements CommandBridge {
    interface Registry { Disposable register(String name, BrigadierTree tree); }
    private final CommandBridge fallback;
    private final Registry registry;

    BrigadierBridge(CommandBridge fallback, Registry registry) {
        this.fallback = fallback;
        this.registry = registry;
    }

    static CommandBridge discover(CommandBridge fallback, Plugin plugin, CommandMap map) {
        Registry registry = PaperRegistry.discover(plugin, map);
        return registry == null ? fallback : new BrigadierBridge(fallback, registry);
    }

    @Override
    public Disposable register(String name, CommandSpec spec, CommandDispatcher dispatcher) {
        final Disposable projection = registry.register(name, BrigadierTree.from(spec));
        final Disposable raw;
        try {
            raw = fallback.register(name, spec, dispatcher);
        } catch (RuntimeException | Error failure) {
            projection.dispose();
            throw failure;
        }
        return () -> { raw.dispose(); projection.dispose(); };
    }

    static final class PaperRegistry implements Registry, EventExecutor {
        private static final String EVENT = "com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent";
        private static final String LITERAL = "com.mojang.brigadier.builder.LiteralArgumentBuilder";
        private static final String ARGUMENT = "com.mojang.brigadier.builder.RequiredArgumentBuilder";
        private static final String STRING = "com.mojang.brigadier.arguments.StringArgumentType";
        private final Plugin plugin;
        private final CommandMap commands;
        private final Map<String, Projection> trees = new HashMap<String, Projection>();
        private final Object executionMarker;

        PaperRegistry(Plugin plugin, CommandMap commands) {
            this.plugin = plugin;
            this.commands = commands;
            try {
                Class<?> command = PublicCommandReflection.type("com.mojang.brigadier.Command");
                executionMarker = Proxy.newProxyInstance(command.getClassLoader(), new Class<?>[]{command},
                        (proxy, method, args) -> {
                            if ("run".equals(method.getName())) return 1;
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return proxy == args[0];
                            return "Klib client command marker";
                        });
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException("Missing Brigadier API", failure);
            }
        }

        static Registry discover(Plugin plugin, CommandMap map) {
            try {
                Class<?> candidate = PublicCommandReflection.type(EVENT);
                PublicCommandReflection.type(LITERAL);
                // Paper 的公开补丁方法用于替换副本根；旧平台缺失时保留原始客户端树。
                PublicCommandReflection.type("com.mojang.brigadier.tree.CommandNode")
                        .getMethod("removeCommand", String.class);
                if (!Event.class.isAssignableFrom(candidate)) return null;
                @SuppressWarnings("unchecked") Class<? extends Event> event = (Class<? extends Event>) candidate;
                PaperRegistry registry = new PaperRegistry(plugin, map);
                PluginManager manager = Bukkit.getPluginManager();
                manager.registerEvent(event, new Listener() { }, EventPriority.NORMAL, registry, plugin, true);
                return registry;
            } catch (ClassNotFoundException | NoSuchMethodException unavailableOnOlderServer) {
                return null;
            }
        }

        @Override
        public Disposable register(String name, BrigadierTree tree) {
            BukkitCommandRegistrar.requirePrimaryThread();
            String label = name.toLowerCase(Locale.ROOT);
            if (trees.containsKey(label)) throw new IllegalStateException("Command already declared: " + label);
            Projection projection = new Projection(tree);
            trees.put(label, projection);
            return () -> projection.active = false;
        }

        @Override
        public void execute(Listener ignored, Event event) {
            // Paper 同一份事件先异步后同步发出；只在同步回调读取 Bukkit 权限/命令所有权。
            if (event.isAsynchronous()) return;
            BukkitCommandRegistrar.requirePrimaryThread();
            try {
                project((Player) PublicCommandReflection.invoke(event, "getPlayer"),
                        PublicCommandReflection.invoke(event, "getCommandNode"));
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "Unable to project klib client command tree", failure);
            }
        }

        void project(Player player, Object root) {
            BukkitCommandRegistrar.requirePrimaryThread();
            @SuppressWarnings("unchecked") Collection<Object> children =
                    (Collection<Object>) PublicCommandReflection.invoke(root, "getChildren");
            for (Object original : new ArrayList<Object>(children)) {
                String label = (String) PublicCommandReflection.invoke(original, "getName");
                String normalized = label.toLowerCase(Locale.ROOT);
                Projection projection = trees.get(normalized);
                if (projection == null) {
                    String prefix = plugin.getName().toLowerCase(Locale.ROOT) + ":";
                    if (normalized.startsWith(prefix)) projection = trees.get(normalized.substring(prefix.length()));
                }
                if (projection == null || !owned(label)) continue;
                if (!projection.active || !plugin.isEnabled() || !projection.tree.accessible(player)) {
                    PublicCommandReflection.invoke(root, "removeCommand", label);
                    continue;
                }
                Object suggestions = suggestions(original);
                Object builder = PublicCommandReflection.invokeStatic(LITERAL, "literal", label);
                configure(builder, projection.tree, player, suggestions);
                Object replacement = PublicCommandReflection.invoke(builder, "build");
                PublicCommandReflection.invoke(root, "removeCommand", label);
                PublicCommandReflection.invoke(root, "addChild", replacement);
            }
        }

        private boolean owned(String label) {
            Command command = commands.getCommand(label);
            return command instanceof PluginIdentifiableCommand
                    && ((PluginIdentifiableCommand) command).getPlugin() == plugin;
        }

        private Object suggestions(Object root) {
            Object redirected = PublicCommandReflection.invoke(root, "getRedirect");
            Object raw = redirected == null ? root : redirected;
            Object args = PublicCommandReflection.invoke(raw, "getChild", "args");
            return args == null ? null : PublicCommandReflection.invoke(args, "getCustomSuggestions");
        }

        private void configure(Object builder, BrigadierTree tree, Player player, Object suggestions) {
            // requires 在这个阶段不会重新过滤：必须先按玩家逐节点筛选，不能只挂 Predicate。
            PublicCommandReflection.invoke(builder, "executes", executionMarker);
            for (BrigadierTree child : tree.children()) {
                if (!child.accessible(player)) continue;
                Object next;
                if (child.kind() == BrigadierTree.Kind.LITERAL) {
                    next = PublicCommandReflection.invokeStatic(LITERAL, "literal", child.token());
                } else {
                    Object argument = PublicCommandReflection.invokeStatic(STRING,
                            child.greedy() ? "greedyString" : "word");
                    next = PublicCommandReflection.invokeStatic(ARGUMENT, "argument", child.token(), argument);
                    if (suggestions != null) PublicCommandReflection.invoke(next, "suggests", suggestions);
                }
                configure(next, child, player, suggestions);
                PublicCommandReflection.invoke(builder, "then", next);
            }
        }

        private static final class Projection {
            private final BrigadierTree tree;
            private volatile boolean active = true;
            private Projection(BrigadierTree tree) { this.tree = tree; }
        }
    }
}
