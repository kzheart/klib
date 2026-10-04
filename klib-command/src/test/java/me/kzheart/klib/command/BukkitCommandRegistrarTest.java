package me.kzheart.klib.command;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import me.kzheart.klib.scope.Disposable;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitCommandRegistrarTest {
    @Test
    void identityRemovalDoesNotDependOnIteratorRemove() {
        Map<String, Command> backing = new HashMap<String, Command>();
        Map<String, Command> knownCommands = new AbstractMap<String, Command>() {
            @Override
            public Set<Entry<String, Command>> entrySet() {
                return Collections.unmodifiableSet(backing.entrySet());
            }

            @Override
            public Command get(Object key) {
                return backing.get(key);
            }

            @Override
            public Command remove(Object key) {
                return backing.remove(key);
            }
        };
        Command owned = new Command("owned") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        Command other = new Command("other") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        backing.put("owned", owned);
        backing.put("prefix:owned", owned);
        backing.put("other", other);

        BukkitCommandRegistrar.removeByIdentity(knownCommands, owned);

        assertEquals(Collections.singleton("other"), backing.keySet());
    }

    @Test
    void disposeRemovesEveryKnownCommandKeyAndUnregistersCommand() {
        Map<String, Command> knownCommands = new HashMap<String, Command>();
        FakeCommandMap commandMap = new FakeCommandMap(knownCommands);
        BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(
                commandMap,
                knownCommands,
                "klib");
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.executes(context -> {
        });

        Disposable registration = registrar.register("demo", spec, new CommandDispatcher(spec));

        assertEquals(2, knownCommands.size());
        Command command = knownCommands.get("demo");
        assertSame(command, knownCommands.get("klib:demo"));
        assertTrue(command.isRegistered());

        registration.dispose();

        assertTrue(knownCommands.isEmpty());
        assertFalse(command.isRegistered());
        registration.dispose();
        assertTrue(knownCommands.isEmpty());
    }

    @Test
    void conflictedRegistrationFailsAndRollsBackEveryOwnedKey() {
        Map<String, Command> knownCommands = new HashMap<String, Command>();
        Command occupied = new Command("demo") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        knownCommands.put("demo", occupied);
        FakeCommandMap commandMap = new FakeCommandMap(knownCommands, false);
        BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(
                commandMap,
                knownCommands,
                "klib");
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.executes(context -> {
        });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> registrar.register("demo", spec, new CommandDispatcher(spec)));

        assertTrue(failure.getMessage().contains("demo"));
        assertEquals(1, knownCommands.size());
        assertSame(occupied, knownCommands.get("demo"));
        assertFalse(knownCommands.containsKey("klib:demo"));
        assertFalse(commandMap.lastRegistered.isRegistered());
    }

    @Test
    void registrationSyncsRootMetadataOntoBukkitCommand() {
        Map<String, Command> knownCommands = new HashMap<String, Command>();
        FakeCommandMap commandMap = new FakeCommandMap(knownCommands);
        BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(
                commandMap,
                knownCommands,
                "klib");
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.description("演示命令");
        spec.permission("demo.use");
        spec.executes(context -> {
        });

        registrar.register("demo", spec, new CommandDispatcher(spec));

        Command command = knownCommands.get("demo");
        assertEquals("demo.use", command.getPermission());
        assertEquals("演示命令", command.getDescription());
        assertEquals("/demo", command.getUsage());
    }

    @Test
    void registrationAndDisposalBothRefreshClientCommandTree() {
        Map<String, Command> knownCommands = new HashMap<String, Command>();
        FakeCommandMap commandMap = new FakeCommandMap(knownCommands);
        AtomicInteger syncs = new AtomicInteger();
        BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(
                commandMap,
                knownCommands,
                "klib",
                syncs::incrementAndGet);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.executes(context -> {
        });

        Disposable registration = registrar.register("demo", spec, new CommandDispatcher(spec));

        assertEquals(1, syncs.get());

        registration.dispose();
        assertEquals(2, syncs.get());

        // 幂等 dispose 不重复同步
        registration.dispose();
        assertEquals(2, syncs.get());
    }

    @Test
    void failedRegistrationDoesNotRefreshClientCommandTree() {
        Map<String, Command> knownCommands = new HashMap<String, Command>();
        Command occupied = new Command("demo") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        knownCommands.put("demo", occupied);
        FakeCommandMap commandMap = new FakeCommandMap(knownCommands, false);
        AtomicInteger syncs = new AtomicInteger();
        BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(
                commandMap,
                knownCommands,
                "klib",
                syncs::incrementAndGet);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.executes(context -> {
        });

        assertThrows(
                IllegalStateException.class,
                () -> registrar.register("demo", spec, new CommandDispatcher(spec)));

        assertEquals(0, syncs.get());
    }

    @Test
    void closeCancelsAnUnfinishedRegistration() {
        Map<String, Command> known = new HashMap<String, Command>();
        List<Runnable> queue = new ArrayList<Runnable>();
        FakeCommandMap commands = new FakeCommandMap(known);
        BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(commands, known, "test", () -> {}, queue::add);
        CommandSpecImpl spec = CommandSpecImpl.command("pending"); spec.executes(context -> {});
        Disposable registration = registrar.register("pending", spec, new CommandDispatcher(spec));
        assertTrue(known.isEmpty()); registration.dispose();
        for (Runnable task : queue) task.run();
        assertTrue(known.isEmpty()); assertEquals(null, commands.lastRegistered);
    }

    @Test
    void closingImmediatelyRejectsExecutionCompletionAndPermissionBeforeQueuedRemoval() {
        Map<String, Command> known = new HashMap<String, Command>();
        List<Runnable> queue = new ArrayList<Runnable>(); AtomicInteger calls = new AtomicInteger();
        FakeCommandMap commands = new FakeCommandMap(known);
        BukkitCommandRegistrar registrar = new BukkitCommandRegistrar(commands, known, "test", () -> {}, queue::add);
        CommandSpecImpl spec = CommandSpecImpl.command("live"); spec.executes(context -> calls.incrementAndGet());
        Disposable registration = registrar.register("live", spec, new CommandDispatcher(spec));
        queue.remove(0).run(); Command command = known.get("live");
        CommandSender sender = proxy(CommandSender.class, (instance, method, arguments) -> {
            if ("hasPermission".equals(method.getName())) return true;
            return null;
        });
        assertTrue(command.execute(sender, "live", new String[0])); assertEquals(1, calls.get());
        registration.dispose(); assertTrue(known.containsKey("live"));
        assertFalse(command.execute(sender, "live", new String[0])); assertEquals(1, calls.get());
        assertTrue(command.tabComplete(sender, "live", new String[0]).isEmpty());
        assertFalse(command.testPermissionSilent(sender));
        queue.remove(0).run(); assertTrue(known.isEmpty());
    }

    @Test
    void explicitReplaceCapturesACommandInsertedAfterRegistrationWasQueued() {
        Map<String, Command> known = new HashMap<String, Command>(); List<Runnable> queue = new ArrayList<Runnable>();
        FakeCommandMap map = new FakeCommandMap(known);
        BukkitCommandRegistrar registrar = replacing(map, known, "custom", queue);
        Disposable registration = registrar.register("help", spec("help"), new CommandDispatcher(spec("help")));
        Command original = inert("help"); original.register(map);
        known.put("help", original); known.put("bukkit:help", original);
        queue.remove(0).run();
        assertFalse(known.get("help") == original); assertSame(original, known.get("bukkit:help"));
        registration.dispose(); queue.remove(0).run();
        assertSame(original, known.get("help")); assertSame(original, known.get("bukkit:help"));
        assertFalse(known.containsKey("custom:help"));
    }

    @Test
    void failedReplaceRestoresOriginalAndRemovesPartialOwnedKeys() {
        Map<String, Command> known = new HashMap<String, Command>(); List<Runnable> queue = new ArrayList<Runnable>();
        FakeCommandMap map = new FakeCommandMap(known, false);
        Command original = inert("help"); original.register(map); known.put("help", original); known.put("bukkit:help", original);
        BukkitCommandRegistrar registrar = replacing(map, known, "custom", queue);
        registrar.register("help", spec("help"), new CommandDispatcher(spec("help")));
        assertThrows(IllegalStateException.class, () -> queue.remove(0).run());
        assertSame(original, known.get("help")); assertSame(original, known.get("bukkit:help"));
        assertFalse(known.containsKey("custom:help")); assertFalse(map.lastRegistered.isRegistered());
    }

    @Test
    void throwingReplaceRollsBackPartialRootAndNamespaceWrites() {
        Map<String, Command> known = new HashMap<String, Command>(); List<Runnable> queue = new ArrayList<Runnable>();
        CommandMap map = proxy(CommandMap.class, (instance, method, arguments) -> {
            if ("register".equals(method.getName())) {
                Command command = (Command) arguments[1]; command.register((CommandMap) instance);
                known.put(command.getName(), command); known.put("custom:" + command.getName(), command);
                throw new IllegalStateException("partial registration failure");
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Command original = inert("help"); original.register(map); known.put("help", original); known.put("bukkit:help", original);
        BukkitCommandRegistrar registrar = replacing(map, known, "custom", queue);
        registrar.register("help", spec("help"), new CommandDispatcher(spec("help")));
        assertThrows(IllegalStateException.class, () -> queue.remove(0).run());
        assertEquals(2, known.size()); assertSame(original, known.get("help")); assertSame(original, known.get("bukkit:help"));
    }

    @Test
    void laterOwnerIsPreservedWhenTheReplacingBindingCloses() {
        Map<String, Command> known = new HashMap<String, Command>(); List<Runnable> queue = new ArrayList<Runnable>();
        FakeCommandMap map = new FakeCommandMap(known);
        Command original = inert("help"); original.register(map); known.put("help", original);
        Disposable registration = replacing(map, known, "custom", queue).register("help", spec("help"), new CommandDispatcher(spec("help")));
        queue.remove(0).run();
        Command later = inert("later"); later.register(map); known.put("help", later);
        registration.dispose(); queue.remove(0).run();
        assertSame(later, known.get("help")); assertFalse(known.containsKey("custom:help"));
    }

    @Test
    void closingAnOlderOwnerBeforeANewerOwnerDoesNotReviveTheClosedCommand() {
        Map<String, Command> known = new HashMap<String, Command>(); List<Runnable> queue = new ArrayList<Runnable>();
        FakeCommandMap map = new FakeCommandMap(known);
        Command original = inert("help"); original.register(map); known.put("help", original); known.put("bukkit:help", original);
        Disposable first = replacing(map, known, "first", queue).register("help", spec("help"), new CommandDispatcher(spec("help")));
        queue.remove(0).run(); Command older = known.get("help");
        Disposable second = replacing(map, known, "second", queue).register("help", spec("help"), new CommandDispatcher(spec("help")));
        queue.remove(0).run(); Command newer = known.get("help");
        first.dispose(); queue.remove(0).run(); assertSame(newer, known.get("help")); assertFalse(older.isRegistered());
        second.dispose(); queue.remove(0).run(); assertFalse(known.containsKey("help")); assertSame(original, known.get("bukkit:help"));
    }

    @Test
    void aliasesAreIndependentBareBindingsAndCancelledPendingReplaceLeavesTheOriginalUntouched() {
        Map<String, Command> known = new HashMap<String, Command>(); List<Runnable> queue = new ArrayList<Runnable>();
        FakeCommandMap map = new FakeCommandMap(known);
        Command original = inert("help"); original.register(map); known.put("help", original); known.put("帮助", original);
        BukkitCommandRegistrar registrar = replacing(map, known, "custom", queue);
        Disposable root = registrar.register("help", spec("help"), new CommandDispatcher(spec("help")));
        Disposable alias = registrar.register("帮助", spec("帮助"), new CommandDispatcher(spec("帮助")));
        for (Runnable task : new ArrayList<Runnable>(queue)) task.run(); queue.clear();
        assertFalse(known.get("help") == original); assertFalse(known.get("帮助") == original);
        root.dispose(); alias.dispose(); for (Runnable task : queue) task.run(); queue.clear();
        assertSame(original, known.get("help")); assertSame(original, known.get("帮助"));
        Disposable pending = registrar.register("help", spec("help"), new CommandDispatcher(spec("help"))); pending.dispose();
        for (Runnable task : queue) task.run(); assertSame(original, known.get("help"));
    }

    @Test
    void explicitReplaceDoesNotCaptureNamespacedLabels() {
        Map<String, Command> known = new HashMap<String, Command>(); List<Runnable> queue = new ArrayList<Runnable>();
        FakeCommandMap map = new FakeCommandMap(known, false);
        Command original = inert("qualified"); original.register(map); known.put("foreign:entry", original);
        replacing(map, known, "custom", queue).register("foreign:entry", spec("foreign:entry"), new CommandDispatcher(spec("foreign:entry")));
        assertThrows(IllegalStateException.class, () -> queue.remove(0).run());
        assertSame(original, known.get("foreign:entry")); assertEquals(1, known.size());
    }

    @Test
    void restoreSkipsARegisteredCommandWhosePluginWasDisabled() {
        Map<String, Command> known = new HashMap<String, Command>(); List<Runnable> queue = new ArrayList<Runnable>();
        FakeCommandMap map = new FakeCommandMap(known); AtomicBoolean enabled = new AtomicBoolean(true);
        Plugin owner = proxy(Plugin.class, (instance, method, arguments) -> {
            if ("isEnabled".equals(method.getName())) return enabled.get();
            throw new UnsupportedOperationException(method.getName());
        });
        Command original = new OwnedCommand("help", owner); original.register(map); known.put("help", original);
        Disposable registration = replacing(map, known, "custom", queue).register("help", spec("help"), new CommandDispatcher(spec("help")));
        queue.remove(0).run(); enabled.set(false); assertTrue(original.isRegistered());
        registration.dispose(); queue.remove(0).run(); assertFalse(known.containsKey("help"));
    }

    private static final class OwnedCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin plugin;
        private OwnedCommand(String name, Plugin plugin) { super(name); this.plugin = plugin; }
        @Override public Plugin getPlugin() { return plugin; }
        @Override public boolean execute(CommandSender sender, String label, String[] args) { return true; }
    }

    @Test
    void forwardingHashMapFacadeDeletesActualNodesAndRestoresWithoutUsingInheritedPutIfAbsent() {
        ForwardingCommands known = new ForwardingCommands(); List<Runnable> queue = new ArrayList<Runnable>();
        FakeCommandMap map = new FakeCommandMap(known);
        Command original = inert("help"); original.register(map); known.put("help", original); known.put("bukkit:help", original);
        Disposable registration = replacing(map, known, "custom", queue).register("help", spec("help"), new CommandDispatcher(spec("help")));
        queue.remove(0).run(); assertFalse(known.actual.get("help") == original); assertTrue(known.actual.containsKey("custom:help"));
        registration.dispose(); queue.remove(0).run();
        assertSame(original, known.actual.get("help")); assertSame(original, known.actual.get("bukkit:help"));
        assertFalse(known.actual.containsKey("custom:help")); assertEquals(2, known.actual.size());
        Command later = inert("later"); later.register(map);
        Disposable second = replacing(map, known, "custom", queue).register("help", spec("help"), new CommandDispatcher(spec("help")));
        queue.remove(0).run(); known.put("help", later); second.dispose(); queue.remove(0).run();
        assertSame(later, known.actual.get("help")); assertFalse(known.actual.containsKey("custom:help"));
    }

    /** Mirrors a Paper-style facade: visible nodes live elsewhere; HashMap's optimized methods do not delegate. */
    private static final class ForwardingCommands extends HashMap<String, Command> {
        private static final long serialVersionUID = 1L;
        private final transient Map<String, Command> actual = new HashMap<String, Command>();
        @Override public Command get(Object key) { return actual.get(key); }
        @Override public boolean containsKey(Object key) { return actual.containsKey(key); }
        @Override public Command put(String key, Command command) { return actual.put(key, command); }
        @Override public Command remove(Object key) { return actual.remove(key); }
        @Override public Set<Entry<String, Command>> entrySet() { return Collections.unmodifiableMap(actual).entrySet(); }
    }

    private static BukkitCommandRegistrar replacing(CommandMap map, Map<String, Command> known, String prefix, List<Runnable> queue) {
        return new BukkitCommandRegistrar(map, known, prefix, () -> {}, queue::add, CommandRegistrationPolicy.REPLACE_UNQUALIFIED);
    }
    private static CommandSpecImpl spec(String name) {
        CommandSpecImpl spec = CommandSpecImpl.command(name); spec.executes(context -> {}); return spec;
    }
    private static Command inert(String name) {
        return new Command(name) {
            @Override public boolean execute(CommandSender sender, String label, String[] args) { return true; }
        };
    }

    @Test
    void offThreadPrimaryActionDoesNotReturnBeforeScheduledWorkCompletes() {
        AtomicBoolean completed = new AtomicBoolean();
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (instance, method, arguments) -> {
            if ("callSyncMethod".equals(method.getName())) {
                @SuppressWarnings("unchecked")
                Callable<Object> action =
                        (Callable<Object>) arguments[1];
                FutureTask<Object> task = new FutureTask<Object>(() -> {
                    Thread.sleep(50L);
                    return action.call();
                });
                Thread thread = new Thread(task, "command-main-thread-test");
                thread.setDaemon(true);
                thread.start();
                return task;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Server server = proxy(Server.class, (instance, method, arguments) -> {
            if ("isPrimaryThread".equals(method.getName())) {
                return false;
            }
            if ("getScheduler".equals(method.getName())) {
                return scheduler;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Plugin plugin = proxy(Plugin.class, (instance, method, arguments) -> {
            throw new UnsupportedOperationException(method.getName());
        });

        BukkitCommandRegistrar.runOnPrimaryThread(
                server, plugin, () -> completed.set(true));

        assertTrue(completed.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class FakeCommandMap implements CommandMap {
        private final Map<String, Command> commands;
        private final boolean registrationResult;
        private Command lastRegistered;

        private FakeCommandMap(Map<String, Command> commands) {
            this(commands, true);
        }

        private FakeCommandMap(Map<String, Command> commands, boolean registrationResult) {
            this.commands = commands;
            this.registrationResult = registrationResult;
        }

        @Override
        public void registerAll(String fallbackPrefix, List<Command> registered) {
            for (Command command : registered) {
                register(fallbackPrefix, command);
            }
        }

        @Override
        public boolean register(String label, String fallbackPrefix, Command command) {
            lastRegistered = command;
            command.register(this);
            if (registrationResult) {
                commands.put(label, command);
            }
            commands.put(fallbackPrefix + ":" + label, command);
            return registrationResult;
        }

        @Override
        public boolean register(String fallbackPrefix, Command command) {
            return register(command.getName(), fallbackPrefix, command);
        }

        @Override
        public boolean dispatch(CommandSender sender, String commandLine) throws CommandException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearCommands() {
            commands.clear();
        }

        @Override
        public Command getCommand(String name) {
            return commands.get(name);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String commandLine) {
            return new ArrayList<String>();
        }

        @Override
        public List<String> tabComplete(
                CommandSender sender,
                String commandLine,
                Location location
        ) {
            return new ArrayList<String>();
        }
    }
}
