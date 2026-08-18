package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.scope.Disposable;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BrigadierBridge implements CommandBridge {
    private static final Logger LOGGER = Logger.getLogger(BrigadierBridge.class.getName());

    interface Registry {
        Disposable register(String name, BrigadierTree tree);

        void refresh();
    }

    private final CommandBridge fallback;
    private final Registry registry;

    BrigadierBridge(CommandBridge fallback, Registry registry) {
        if (fallback == null) {
            throw new NullPointerException("fallback");
        }
        if (registry == null) {
            throw new NullPointerException("registry");
        }
        this.fallback = fallback;
        this.registry = registry;
    }

    static CommandBridge discover(CommandBridge fallback) {
        Registry registry = PaperRegistry.discover();
        return registry == null ? fallback : new BrigadierBridge(fallback, registry);
    }

    @Override
    public Disposable register(
            String name,
            CommandSpec spec,
            CommandDispatcher dispatcher
    ) {
        final Disposable brigadier;
        try {
            brigadier = registry.register(name, BrigadierTree.from(spec));
        } catch (IllegalStateException duplicate) {
            // Brigadier 投影重名时降级：仅走 CommandMap fallback 注册。
            LOGGER.log(Level.WARNING,
                    "Brigadier 命令重名，降级为仅 CommandMap 注册: " + name,
                    duplicate);
            return fallback.register(name, spec, dispatcher);
        }
        // 分两段 try：fallback 失败必须回滚 brigadier，refresh 失败只降级为警告，
        // 避免已成功的 CommandMap Disposable 被丢弃造成注册泄漏。
        final Disposable commandMap;
        try {
            commandMap = fallback.register(name, spec, dispatcher);
        } catch (RuntimeException failure) {
            brigadier.dispose();
            throw failure;
        }
        refreshQuietly();
        return new Disposable() {
            private boolean disposed;

            @Override
            public synchronized void dispose() {
                if (disposed) {
                    return;
                }
                disposed = true;
                brigadier.dispose();
                commandMap.dispose();
                refreshQuietly();
            }
        };
    }

    private void refreshQuietly() {
        try {
            registry.refresh();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "刷新客户端命令树失败", failure);
        }
    }

    private static final class PaperRegistry implements Registry, EventExecutor {
        private static final String EVENT_CLASS =
                "com.destroystokyo.paper.event.brigadier.CommandRegisteredEvent";
        private static final String ARGUMENT_BUILDER_CLASS =
                "com.mojang.brigadier.builder.ArgumentBuilder";
        private static final String LITERAL_BUILDER_CLASS =
                "com.mojang.brigadier.builder.LiteralArgumentBuilder";
        private static final String REQUIRED_BUILDER_CLASS =
                "com.mojang.brigadier.builder.RequiredArgumentBuilder";
        private static final String STRING_ARGUMENT_CLASS =
                "com.mojang.brigadier.arguments.StringArgumentType";

        private final Plugin plugin;
        private final PluginManager pluginManager;
        private final Class<? extends Event> eventClass;
        private final Listener listener = new Listener() {
        };
        private final Map<String, BrigadierTree> trees = new HashMap<String, BrigadierTree>();
        private boolean listening;

        private PaperRegistry(
                Plugin plugin,
                PluginManager pluginManager,
                Class<? extends Event> eventClass
        ) {
            this.plugin = plugin;
            this.pluginManager = pluginManager;
            this.eventClass = eventClass;
        }

        static Registry discover() {
            try {
                if (!isAtLeastOneNineteen(Bukkit.getBukkitVersion())) {
                    return null;
                }
                Class<?> candidate = Class.forName(EVENT_CLASS, false,
                        BrigadierBridge.class.getClassLoader());
                Class.forName(LITERAL_BUILDER_CLASS, false,
                        BrigadierBridge.class.getClassLoader());
                if (!Event.class.isAssignableFrom(candidate)) {
                    return null;
                }
                @SuppressWarnings("unchecked")
                Class<? extends Event> typed = (Class<? extends Event>) candidate;
                Plugin plugin = JavaPlugin.getProvidingPlugin(BrigadierBridge.class);
                return new PaperRegistry(plugin, Bukkit.getPluginManager(), typed);
            } catch (RuntimeException ignored) {
                return null;
            } catch (LinkageError ignored) {
                return null;
            } catch (ClassNotFoundException ignored) {
                return null;
            }
        }

        @Override
        public synchronized Disposable register(final String name, BrigadierTree tree) {
            final String normalized = normalize(name);
            if (trees.containsKey(normalized)) {
                throw new IllegalStateException("duplicate Brigadier command: " + normalized);
            }
            if (!listening) {
                pluginManager.registerEvent(
                        eventClass,
                        listener,
                        EventPriority.NORMAL,
                        this,
                        plugin,
                        true);
                listening = true;
            }
            trees.put(normalized, tree);
            return new Disposable() {
                private boolean disposed;

                @Override
                public synchronized void dispose() {
                    if (disposed) {
                        return;
                    }
                    disposed = true;
                    remove(normalized);
                }
            };
        }

        private synchronized void remove(String name) {
            trees.remove(name);
            if (trees.isEmpty() && listening) {
                HandlerList.unregisterAll(listener);
                listening = false;
            }
        }

        @Override
        public void refresh() {
            ServerCommandSync.trySyncCommands();
        }

        @Override
        public void execute(Listener ignored, Event event) {
            try {
                // 查找按 normalize 后的名字，构建时用事件原始 label，
                // 保证 namespaced 别名（如 klib:cmd）投影到正确的根 literal。
                String rawLabel = (String) invoke(event, "getCommandLabel");
                BrigadierTree tree;
                synchronized (this) {
                    tree = trees.get(normalize(rawLabel));
                }
                if (tree == null || !isKlibCommand(invoke(event, "getCommand"))) {
                    return;
                }
                Object delegate = invoke(event, "getBrigadierCommand");
                Object built = build(rawLabel, tree, delegate);
                invoke(event, "setLiteral", built);
                invoke(event, "setRawCommand", Boolean.TRUE);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Unable to project klib command tree to Paper Brigadier",
                        failure);
            }
        }

        private Object build(String label, BrigadierTree tree, Object delegate) {
            Object builder = literal(label);
            configure(builder, tree, delegate);
            return invoke(builder, "build");
        }

        private void configure(Object builder, BrigadierTree tree, Object delegate) {
            invoke(builder, "requires", requirement(tree));
            invoke(builder, "executes", delegate);
            for (BrigadierTree child : tree.children()) {
                Object childBuilder = child.kind() == BrigadierTree.Kind.LITERAL
                        ? literal(child.token())
                        : argument(child);
                configure(childBuilder, child, delegate);
                if (child.kind() == BrigadierTree.Kind.ARGUMENT) {
                    invoke(childBuilder, "suggests", delegate);
                }
                invoke(builder, "then", childBuilder);
            }
        }

        private Object literal(String name) {
            return invokeStatic(LITERAL_BUILDER_CLASS, "literal", name);
        }

        private Object argument(BrigadierTree tree) {
            Object argumentType = invokeStatic(
                    STRING_ARGUMENT_CLASS,
                    tree.greedy() ? "greedyString" : "word");
            return invokeStatic(REQUIRED_BUILDER_CLASS, "argument", tree.token(), argumentType);
        }

        private Predicate<Object> requirement(final BrigadierTree tree) {
            return new Predicate<Object>() {
                @Override
                public boolean test(Object source) {
                    Object value = invoke(source, "getBukkitSender");
                    if (!(value instanceof CommandSender)) {
                        return false;
                    }
                    CommandSender sender = (CommandSender) value;
                    return (tree.permission() == null
                            || sender.hasPermission(tree.permission()))
                            && (!tree.playerOnly() || sender instanceof Player);
                }
            };
        }

        private static boolean isKlibCommand(Object command) {
            return command != null && command.getClass().getName().equals(
                    BukkitCommandRegistrar.class.getName() + "$DispatchingCommand");
        }

        private static boolean isAtLeastOneNineteen(String version) {
            if (version == null) {
                return false;
            }
            String[] parts = version.split("\\.");
            try {
                int major = Integer.parseInt(parts[0].replaceAll("[^0-9].*$", ""));
                int minor = parts.length < 2
                        ? 0
                        : Integer.parseInt(parts[1].replaceAll("[^0-9].*$", ""));
                return major > 1 || major == 1 && minor >= 19;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        private static String normalize(String name) {
            String normalized = name.toLowerCase(Locale.ROOT);
            int namespace = normalized.indexOf(':');
            return namespace < 0 ? normalized : normalized.substring(namespace + 1);
        }

        private static Object invoke(Object target, String name, Object... arguments) {
            Method method = findMethod(target.getClass(), name, false, arguments);
            try {
                return method.invoke(target, arguments);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Cannot invoke " + name, exception);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException("Cannot invoke " + name, exception.getCause());
            }
        }

        private static Object invokeStatic(String type, String name, Object... arguments) {
            try {
                Class<?> owner = Class.forName(type, true, BrigadierBridge.class.getClassLoader());
                Method method = findMethod(owner, name, true, arguments);
                return method.invoke(null, arguments);
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException("Missing Paper Brigadier type: " + type, exception);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Cannot invoke " + name, exception);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException("Cannot invoke " + name, exception.getCause());
            }
        }

        private static Method findMethod(
                Class<?> type,
                String name,
                boolean requireStatic,
                Object[] arguments
        ) {
            for (Method method : type.getMethods()) {
                if (!method.getName().equals(name)
                        || Modifier.isStatic(method.getModifiers()) != requireStatic
                        || method.getParameterTypes().length != arguments.length) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                boolean matches = true;
                for (int index = 0; index < parameters.length; index++) {
                    if (arguments[index] != null
                            && !box(parameters[index]).isInstance(arguments[index])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    return method;
                }
            }
            throw new IllegalStateException("Missing compatible method: " + type.getName()
                    + "." + name);
        }

        private static Class<?> box(Class<?> type) {
            if (type == boolean.class) {
                return Boolean.class;
            }
            return type;
        }
    }
}
