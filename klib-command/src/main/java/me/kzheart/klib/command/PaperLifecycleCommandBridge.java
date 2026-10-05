package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.scope.Disposable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Paper 的生命周期登记器只在 COMMANDS 回调内使用，不保留 registrar。 */
final class PaperLifecycleCommandBridge implements CommandBridge {
    private static final String EVENTS = "io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents";
    private static final String HANDLER = "io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler";
    private static final String BASIC = "io.papermc.paper.command.brigadier.BasicCommand";
    private final Plugin plugin;
    private final CommandMap commandMap;
    private final CommandRegistrationPolicy policy;
    private final Object manager;
    private final Object eventType;
    private final Class<?> handlerType;
    private final Class<?> basicType;
    private final Set<String> declarations = new HashSet<String>();

    private PaperLifecycleCommandBridge(Plugin plugin, CommandMap commandMap, CommandRegistrationPolicy policy,
                                        Object manager, Object eventType, Class<?> handlerType, Class<?> basicType) {
        this.plugin = plugin;
        this.commandMap = commandMap;
        this.policy = policy;
        this.manager = manager;
        this.eventType = eventType;
        this.handlerType = handlerType;
        this.basicType = basicType;
    }

    static CommandBridge discover(Plugin plugin, CommandMap map, CommandRegistrationPolicy policy) {
        try {
            Class<?> events = PublicCommandReflection.type(EVENTS);
            Class<?> handler = PublicCommandReflection.type(HANDLER);
            Class<?> basic = PublicCommandReflection.type(BASIC);
            Object eventType = events.getField("COMMANDS").get(null);
            Object manager = PublicCommandReflection.invoke(plugin, "getLifecycleManager");
            return new PaperLifecycleCommandBridge(plugin, map, policy, manager, eventType, handler, basic);
        } catch (ClassNotFoundException missingOnOlderServer) {
            return null;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot access Paper COMMANDS lifecycle", failure);
        }
    }

    @Override
    public Disposable register(String name, CommandSpec spec, CommandDispatcher dispatcher) {
        BukkitCommandRegistrar.requirePrimaryThread();
        String label = name.toLowerCase(Locale.ROOT);
        if (!declarations.add(label)) throw new IllegalStateException("Command already declared: " + label);
        Binding binding = new Binding(label, spec, dispatcher);
        Object basic = Proxy.newProxyInstance(basicType.getClassLoader(), new Class<?>[]{basicType}, binding);
        Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            if (!"run".equals(method.getName())) throw new UnsupportedOperationException(method.toString());
            if (!binding.active()) return null;
            BukkitCommandRegistrar.requirePrimaryThread();
            try {
                binding.checkCollision();
                Object registrar = PublicCommandReflection.invoke(args[0], "registrar");
                PublicCommandReflection.invoke(registrar, "register", label, binding.description,
                        Collections.emptyList(), basic);
                binding.registered = true;
                return null;
            } catch (RuntimeException | Error failure) {
                binding.dispose();
                throw failure;
            }
        });
        try {
            PublicCommandReflection.invoke(manager, "registerEventHandler", eventType, handler);
            return binding;
        } catch (RuntimeException | Error failure) {
            binding.dispose();
            declarations.remove(label);
            throw failure;
        }
    }

    private final class Binding implements Disposable, InvocationHandler {
        private final String label;
        private final String namespaced;
        private final String description;
        private final CommandDispatcher dispatcher;
        private volatile boolean enabled = true;
        private boolean registered;

        private Binding(String label, CommandSpec spec, CommandDispatcher dispatcher) {
            this.label = label;
            this.namespaced = plugin.getName().toLowerCase(Locale.ROOT) + ":" + label;
            this.description = ((CommandSpecImpl) spec).root().description;
            this.dispatcher = dispatcher;
        }

        private boolean active() { return enabled && plugin.isEnabled(); }
        @Override public void dispose() { enabled = false; }

        private void checkCollision() {
            Command bare = commandMap.getCommand(label);
            Command qualified = commandMap.getCommand(namespaced);
            if (qualified != null && !previouslyOwned(qualified)
                    || bare != null && !previouslyOwned(bare)
                    && (policy == CommandRegistrationPolicy.REJECT || label.indexOf(':') >= 0)) {
                throw new IllegalStateException("Command label already registered: " + label);
            }
        }

        private boolean previouslyOwned(Command command) {
            // Paper 为同一节点每次创建新的 Bukkit wrapper，不能按 Command 引用比较。
            return registered && command instanceof PluginIdentifiableCommand
                    && ((PluginIdentifiableCommand) command).getPlugin() == plugin;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            switch (method.getName()) {
                case "permission": return null;
                // Raw transport stays available so the Klib dispatcher can issue localized permission errors.
                // Client visibility is filtered separately for every node in the player's copied tree.
                case "canUse": return active();
                case "execute":
                    if (active()) {
                        BukkitCommandRegistrar.requirePrimaryThread();
                        dispatcher.execute(sender(args[0]), (String[]) args[1]);
                    }
                    return null;
                case "suggest":
                    if (!active()) return Collections.emptyList();
                    BukkitCommandRegistrar.requirePrimaryThread();
                    return dispatcher.complete(sender(args[0]), (String[]) args[1]);
                default: throw new UnsupportedOperationException(method.toString());
            }
        }
    }

    private static CommandSender sender(Object source) {
        return (CommandSender) PublicCommandReflection.invoke(source, "getSender");
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if ("toString".equals(method.getName())) return "Klib lifecycle command";
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(method.getName())) return proxy == args[0];
        throw new UnsupportedOperationException(method.toString());
    }
}
