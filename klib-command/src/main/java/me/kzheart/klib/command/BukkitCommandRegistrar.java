package me.kzheart.klib.command;

import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.command.api.CommandSpec;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Objects;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class BukkitCommandRegistrar implements CommandBridge {
    private static final Logger LOGGER = Logger.getLogger(BukkitCommandRegistrar.class.getName());
    private static final long MAIN_THREAD_TIMEOUT_SECONDS = 30L;
    private static final Runnable DEFAULT_CLIENT_SYNC = new Runnable() {
        @Override
        public void run() {
            ServerCommandSync.requestSyncCommands();
        }
    };

    private final CommandMap commandMap;
    private final Map<String, Command> knownCommands;
    private final String fallbackPrefix;
    private final Runnable clientSync;
    private final Consumer<Runnable> mutations;
    private final CommandRegistrationPolicy policy;

    public BukkitCommandRegistrar(
            CommandMap commandMap,
            Map<String, Command> knownCommands,
            String fallbackPrefix
    ) {
        this(commandMap, knownCommands, fallbackPrefix, DEFAULT_CLIENT_SYNC);
    }

    public BukkitCommandRegistrar(CommandMap commandMap, Map<String, Command> knownCommands,
                                  String fallbackPrefix, CommandRegistrationPolicy policy) {
        this(commandMap, knownCommands, fallbackPrefix, DEFAULT_CLIENT_SYNC, ServerCommandSync::mutate, policy);
    }

    /** 注入客户端命令树同步动作，仅供测试观测同步时机。 */
    BukkitCommandRegistrar(
            CommandMap commandMap,
            Map<String, Command> knownCommands,
            String fallbackPrefix,
            Runnable clientSync
    ) {
        this(commandMap, knownCommands, fallbackPrefix, clientSync, ServerCommandSync::mutate);
    }

    BukkitCommandRegistrar(CommandMap commandMap, Map<String, Command> knownCommands, String fallbackPrefix,
                           Runnable clientSync, Consumer<Runnable> mutations) {
        this(commandMap, knownCommands, fallbackPrefix, clientSync, mutations, CommandRegistrationPolicy.REJECT);
    }

    BukkitCommandRegistrar(CommandMap commandMap, Map<String, Command> knownCommands, String fallbackPrefix,
                           Runnable clientSync, Consumer<Runnable> mutations, CommandRegistrationPolicy policy) {
        if (commandMap == null) {
            throw new NullPointerException("commandMap");
        }
        if (knownCommands == null) {
            throw new NullPointerException("knownCommands");
        }
        if (fallbackPrefix == null || fallbackPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("fallbackPrefix must not be blank");
        }
        if (clientSync == null) {
            throw new NullPointerException("clientSync");
        }
        this.commandMap = commandMap;
        this.knownCommands = knownCommands;
        this.fallbackPrefix = fallbackPrefix.trim().toLowerCase(Locale.ROOT);
        this.clientSync = clientSync;
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public static CommandBridge discover(String fallbackPrefix) {
        return discover(fallbackPrefix, CommandRegistrationPolicy.REJECT);
    }

    public static CommandBridge discover(String fallbackPrefix, CommandRegistrationPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        Server server = Bukkit.getServer();
        if (server == null) {
            throw new IllegalStateException("Bukkit server is not available");
        }
        try {
            Method accessor = server.getClass().getMethod("getCommandMap");
            Object value = accessor.invoke(server);
            if (!(value instanceof CommandMap)) {
                throw new IllegalStateException("getCommandMap did not return CommandMap");
            }
            CommandMap commandMap = (CommandMap) value;
            Field field = findField(commandMap.getClass(), "knownCommands");
            field.setAccessible(true);
            Object known = field.get(commandMap);
            if (!(known instanceof Map<?, ?>)) {
                throw new IllegalStateException("knownCommands is not a map");
            }
            @SuppressWarnings("unchecked")
            Map<String, Command> commands = (Map<String, Command>) known;
            BukkitCommandRegistrar fallback = new BukkitCommandRegistrar(
                    commandMap,
                    commands,
                    fallbackPrefix, policy);
            return BrigadierBridge.discover(fallback);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Server does not expose getCommandMap", exception);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access Bukkit command registry", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Cannot obtain Bukkit command map", exception.getCause());
        }
    }

    @Override
    public Disposable register(
            String name,
            CommandSpec spec,
            CommandDispatcher dispatcher
    ) {
        Server server = Bukkit.getServer();
        if (server != null && !server.isPrimaryThread()) {
            throw new IllegalStateException("Bukkit 命令注册必须在服务器主线程执行");
        }
        if (dispatcher == null) {
            throw new NullPointerException("dispatcher");
        }
        DispatchingCommand command = new DispatchingCommand(name, dispatcher);
        if (spec instanceof CommandSpecImpl) {
            CommandNode root = ((CommandSpecImpl) spec).root();
            if (root.permission != null) {
                command.setPermission(root.permission);
            }
            if (!root.description.isEmpty()) {
                command.setDescription(root.description);
            }
            command.setUsage("/" + name);
        }
        final BukkitRegistration registration = new BukkitRegistration(commandMap, knownCommands, command, clientSync, mutations);
        command.active = false;
        mutations.accept(new Runnable() {
            @Override public String toString() { return "register " + fallbackPrefix + ":" + name; }
            @Override public void run() {
                if (registration.disposed) return;
                try {
                    if (policy == CommandRegistrationPolicy.REPLACE_UNQUALIFIED) registration.captureBareLabel(name);
                    if (!commandMap.register(fallbackPrefix, command)) {
                        throw new IllegalStateException("Bukkit rejected command registration: " + name
                            + " (namespaced fallback was rolled back)");
                    }
                } catch (RuntimeException | Error failure) {
                    command.unregister(commandMap);
                    removeByIdentity(knownCommands, command);
                    registration.restorePriorBindings();
                    throw failure;
                }
                command.active = true;
                syncQuietly(clientSync);
            }
        });
        return registration;
    }

    private static void syncQuietly(Runnable clientSync) {
        try { clientSync.run(); }
        catch (RuntimeException failure) { LOGGER.log(Level.WARNING, "命令变更已完成，但客户端命令树刷新失败", failure); }
    }

    /** 按引用移除 knownCommands 中指向该命令的所有键。 */
    static void removeByIdentity(Map<String, Command> knownCommands, Command command) {
        List<String> ownedKeys = new ArrayList<String>();
        for (Entry<String, Command> entry : knownCommands.entrySet()) {
            if (entry.getValue() == command) {
                ownedKeys.add(entry.getKey());
            }
        }
        for (String key : ownedKeys) {
            if (knownCommands.get(key) == command) {
                knownCommands.remove(key);
            }
        }
    }

    /**
     * knownCommands 无并发保护，注销必须在主线程执行；
     * 非主线程调用时调度回主线程，无法调度时显式失败，绝不并发修改。
     */
    static void runOnPrimaryThread(Runnable action) {
        Server server = Bukkit.getServer();
        if (server == null || server.isPrimaryThread()) {
            action.run();
            return;
        }
        try {
            Plugin plugin = JavaPlugin.getProvidingPlugin(BukkitCommandRegistrar.class);
            runOnPrimaryThread(server, plugin, action);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("无法将命令注销调度到主线程", failure);
        }
    }

    static void runOnPrimaryThread(Server server, Plugin plugin, final Runnable action) {
        if (server == null) {
            throw new NullPointerException("server");
        }
        if (action == null) {
            throw new NullPointerException("action");
        }
        if (server.isPrimaryThread()) {
            action.run();
            return;
        }
        if (plugin == null) {
            throw new NullPointerException("plugin");
        }
        Future<Void> completion = server.getScheduler().callSyncMethod(plugin, () -> {
            action.run();
            return null;
        });
        try {
            completion.get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待主线程注销命令时被中断", failure);
        } catch (TimeoutException failure) {
            throw new IllegalStateException("等待主线程注销命令超时", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("主线程注销命令失败", cause);
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException("Cannot find Bukkit field: " + name);
    }

    private static final class DispatchingCommand extends Command {
        private final CommandDispatcher dispatcher;
        private volatile boolean active = true;

        @Override public boolean testPermissionSilent(CommandSender sender) {
            return active && super.testPermissionSilent(sender);
        }

        private DispatchingCommand(String name, CommandDispatcher dispatcher) {
            super(name);
            this.dispatcher = dispatcher;
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            if (!active) return false;
            dispatcher.execute(sender, args);
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!active) return Collections.emptyList();
            return dispatcher.complete(sender, args);
        }
    }

    private static final class BukkitRegistration implements Disposable {
        private final CommandMap commandMap;
        private final Map<String, Command> knownCommands;
        private final Command command;
        private final Runnable clientSync;
        private final Consumer<Runnable> mutations;
        private volatile boolean disposed;
        private final Map<String, Command> priorBindings = new LinkedHashMap<String, Command>();

        private BukkitRegistration(
                CommandMap commandMap,
                Map<String, Command> knownCommands,
                Command command,
                Runnable clientSync, Consumer<Runnable> mutations
        ) {
            this.commandMap = commandMap;
            this.knownCommands = knownCommands;
            this.command = command;
            this.clientSync = clientSync;
            this.mutations = mutations;
        }

        private void captureBareLabel(String label) {
            label = label.toLowerCase(Locale.ROOT);
            if (label.indexOf(':') >= 0) return;
            Command prior = knownCommands.get(label);
            if (prior != null) {
                priorBindings.put(label, prior);
                knownCommands.remove(label);
            }
        }

        private void restorePriorBindings() {
            for (Entry<String, Command> entry : priorBindings.entrySet()) {
                Command prior = entry.getValue();
                if (!prior.isRegistered()) continue;
                if (prior instanceof PluginIdentifiableCommand) {
                    Plugin owner = ((PluginIdentifiableCommand) prior).getPlugin();
                    if (owner == null || !owner.isEnabled()) continue;
                }
                if (prior instanceof DispatchingCommand && !((DispatchingCommand) prior).active) continue;
                // A forwarding facade may inherit HashMap.putIfAbsent without forwarding it.
                // The lease and main-thread ownership make this check + virtual put indivisible.
                if (!knownCommands.containsKey(entry.getKey())) knownCommands.put(entry.getKey(), prior);
            }
            priorBindings.clear();
        }

        @Override
        public synchronized void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            ((DispatchingCommand) command).active = false;
            runOnPrimaryThread(new Runnable() {
                @Override
                public void run() {
                    mutations.accept(new Runnable() {
                        @Override public String toString() { return "unregister " + command.getName(); }
                        @Override public void run() {
                            command.unregister(commandMap);
                            removeByIdentity(knownCommands, command);
                            restorePriorBindings();
                            syncQuietly(clientSync);
                        }
                    });
                }
            });
            disposed = true;
        }
    }
}
