package me.kzheart.klib.command;

import me.kzheart.klib.scope.Disposable;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    void offThreadPrimaryActionDoesNotReturnBeforeScheduledWorkCompletes() {
        AtomicBoolean completed = new AtomicBoolean();
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (instance, method, arguments) -> {
            if ("callSyncMethod".equals(method.getName())) {
                @SuppressWarnings("unchecked")
                java.util.concurrent.Callable<Object> action =
                        (java.util.concurrent.Callable<Object>) arguments[1];
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
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
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
