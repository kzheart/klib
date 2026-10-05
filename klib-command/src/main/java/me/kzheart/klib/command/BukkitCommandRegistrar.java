package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.scope.Disposable;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 启动时声明命令；关闭句柄只停用绑定，不修改服务端正在使用的命令树。 */
public final class BukkitCommandRegistrar implements CommandBridge {
    private final CommandMap commandMap;
    private final Map<String, Command> knownCommands;
    private final String fallbackPrefix;
    private final CommandRegistrationPolicy policy;
    private final Plugin plugin;

    public BukkitCommandRegistrar(CommandMap commandMap, Map<String, Command> knownCommands,
                                  String fallbackPrefix) {
        this(commandMap, knownCommands, fallbackPrefix, CommandRegistrationPolicy.REJECT);
    }

    public BukkitCommandRegistrar(CommandMap commandMap, Map<String, Command> knownCommands,
                                  String fallbackPrefix, CommandRegistrationPolicy policy) {
        this(commandMap, knownCommands, fallbackPrefix, policy, null);
    }

    private BukkitCommandRegistrar(CommandMap commandMap, Map<String, Command> knownCommands,
                                   String fallbackPrefix, CommandRegistrationPolicy policy, Plugin plugin) {
        this.commandMap = Objects.requireNonNull(commandMap, "commandMap");
        this.knownCommands = Objects.requireNonNull(knownCommands, "knownCommands");
        if (fallbackPrefix == null || fallbackPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("fallbackPrefix must not be blank");
        }
        this.fallbackPrefix = fallbackPrefix.trim().toLowerCase(Locale.ROOT);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.plugin = plugin;
    }

    public static CommandBridge discover(String fallbackPrefix) {
        return discover(fallbackPrefix, CommandRegistrationPolicy.REJECT);
    }

    public static CommandBridge discover(String fallbackPrefix, CommandRegistrationPolicy policy) {
        return discover(JavaPlugin.getProvidingPlugin(BukkitCommandRegistrar.class), fallbackPrefix, policy);
    }

    /** 已知宿主时显式传入插件，避免依赖库与宿主必须由同一 ClassLoader 提供。 */
    public static CommandBridge discover(Plugin plugin, String fallbackPrefix) {
        return discover(plugin, fallbackPrefix, CommandRegistrationPolicy.REJECT);
    }

    public static CommandBridge discover(Plugin plugin, String fallbackPrefix, CommandRegistrationPolicy policy) {
        requirePrimaryThread();
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(policy, "policy");
        if (fallbackPrefix == null || fallbackPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("fallbackPrefix must not be blank");
        }
        Server server = Objects.requireNonNull(Bukkit.getServer(), "Bukkit server is not available");
        CommandMap map = (CommandMap) PublicCommandReflection.invoke(server, "getCommandMap");
        CommandBridge bridge = PaperLifecycleCommandBridge.discover(plugin, map, policy);
        if (bridge == null) {
            bridge = new BukkitCommandRegistrar(map, knownCommands(map), fallbackPrefix, policy, plugin);
        }
        return BrigadierBridge.discover(bridge, plugin, map);
    }

    @Override
    public Disposable register(String name, CommandSpec spec, CommandDispatcher dispatcher) {
        requirePrimaryThread();
        Objects.requireNonNull(dispatcher, "dispatcher");
        String label = name.toLowerCase(Locale.ROOT);
        String namespaced = fallbackPrefix + ":" + label;
        Command previous = knownCommands.get(label);
        if (knownCommands.containsKey(namespaced) || previous != null
                && (policy == CommandRegistrationPolicy.REJECT || label.indexOf(':') >= 0)) {
            throw new IllegalStateException("Command label already registered: " + label);
        }
        DispatchingCommand command = plugin == null
                ? new DispatchingCommand(name, dispatcher, null)
                : new OwnedDispatchingCommand(name, dispatcher, plugin);
        if (spec instanceof CommandSpecImpl) {
            CommandNode root = ((CommandSpecImpl) spec).root();
            command.setPermission(root.permission);
            command.setDescription(root.description);
        }
        command.setUsage("/" + name);
        // 仅在旧服务端启动注册阶段替换裸标签；关闭时不再回写共享命令树。
        if (previous != null) knownCommands.remove(label);
        try {
            if (!commandMap.register(fallbackPrefix, command)) {
                throw new IllegalStateException("Bukkit rejected command registration: " + name);
            }
            command.active = true;
            return command;
        } catch (RuntimeException | Error failure) {
            command.unregister(commandMap);
            removeByIdentity(knownCommands, command);
            if (previous != null && !knownCommands.containsKey(label)) knownCommands.put(label, previous);
            throw failure;
        }
    }

    static void requirePrimaryThread() {
        Server server = Bukkit.getServer();
        if (server != null && !server.isPrimaryThread()) {
            throw new IllegalStateException("Bukkit 命令操作必须在服务器主线程执行");
        }
    }

    static void removeByIdentity(Map<String, Command> commands, Command command) {
        for (String label : new ArrayList<String>(commands.keySet())) {
            if (commands.get(label) == command) commands.remove(label);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Command> knownCommands(CommandMap map) {
        for (Class<?> type = map.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("knownCommands");
                field.setAccessible(true);
                return (Map<String, Command>) field.get(map);
            } catch (NoSuchFieldException ignored) {
                // 旧 Bukkit 的命令表没有公开 accessor；只在启动注册路径读取此字段。
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot access Bukkit command registry", failure);
            }
        }
        throw new IllegalStateException("Cannot find Bukkit knownCommands");
    }

    private static class DispatchingCommand extends Command implements Disposable {
        private final CommandDispatcher dispatcher;
        private final Plugin plugin;
        private volatile boolean active;

        private DispatchingCommand(String name, CommandDispatcher dispatcher, Plugin plugin) {
            super(name);
            this.dispatcher = dispatcher;
            this.plugin = plugin;
        }

        private boolean active() { return active && (plugin == null || plugin.isEnabled()); }
        @Override public void dispose() { active = false; }
        @Override public boolean testPermissionSilent(CommandSender sender) {
            return active() && super.testPermissionSilent(sender);
        }
        @Override public boolean execute(CommandSender sender, String label, String[] args) {
            if (!active()) return false;
            requirePrimaryThread();
            dispatcher.execute(sender, args);
            return true;
        }
        @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!active()) return Collections.emptyList();
            requirePrimaryThread();
            return dispatcher.complete(sender, args);
        }
    }

    private static final class OwnedDispatchingCommand extends DispatchingCommand implements PluginIdentifiableCommand {
        private final Plugin owner;
        private OwnedDispatchingCommand(String name, CommandDispatcher dispatcher, Plugin owner) {
            super(name, dispatcher, owner);
            this.owner = owner;
        }
        @Override public Plugin getPlugin() { return owner; }
    }

}
