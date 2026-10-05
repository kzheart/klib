package me.kzheart.klib.command;

import me.kzheart.klib.scope.Disposable;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BukkitCommandRegistrarTest {
    @Test void closeImmediatelyDisablesButKeepsPhysicalBindings() throws Exception {
        Map<String, Command> known = new HashMap<String, Command>();
        FakeCommandMap map = new FakeCommandMap(known);
        AtomicInteger calls = new AtomicInteger();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.executes(context -> calls.incrementAndGet());
        Disposable handle = new BukkitCommandRegistrar(map, known, "test")
                .register("demo", spec, new CommandDispatcher(spec));
        Command command = known.get("demo");
        assertFalse(command instanceof PluginIdentifiableCommand, "manual registration has no plugin owner");
        CommandSender sender = TestSenders.console().sender();
        assertTrue(command.execute(sender, "demo", new String[0]));
        assertEquals(1, calls.get());
        Thread worker = new Thread(handle::dispose);
        worker.start(); worker.join();
        assertFalse(command.execute(sender, "demo", new String[0]));
        assertFalse(command.testPermissionSilent(sender));
        assertTrue(command.tabComplete(sender, "demo", new String[]{""}).isEmpty());
        assertSame(command, known.get("demo"));
        assertSame(command, known.get("test:demo"));
        assertTrue(command.isRegistered());
        handle.dispose();
    }

    @Test void defaultRejectDoesNotChangeEitherExistingBinding() {
        Map<String, Command> known = new HashMap<String, Command>();
        FakeCommandMap map = new FakeCommandMap(known);
        Command existing = command("demo"); known.put("demo", existing);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        assertThrows(IllegalStateException.class, () -> new BukkitCommandRegistrar(map, known, "test")
                .register("demo", spec, new CommandDispatcher(spec)));
        assertSame(existing, known.get("demo"));
        assertFalse(known.containsKey("test:demo"));
    }

    @Test void replacementOnlyChangesBareLabelAndNeverRestoresOnClose() {
        Map<String, Command> known = new HashMap<String, Command>();
        FakeCommandMap map = new FakeCommandMap(known);
        Command previous = command("demo"); known.put("demo", previous); known.put("other:demo", previous);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        Disposable handle = new BukkitCommandRegistrar(map, known, "test", CommandRegistrationPolicy.REPLACE_UNQUALIFIED)
                .register("demo", spec, new CommandDispatcher(spec));
        Command replacement = known.get("demo");
        assertNotSame(previous, replacement);
        assertSame(previous, known.get("other:demo"));
        handle.dispose();
        assertSame(replacement, known.get("demo"));
        assertSame(previous, known.get("other:demo"));
    }

    @Test void replacementCannotOverwriteNamespacedLabels() {
        Map<String, Command> known = new HashMap<String, Command>();
        FakeCommandMap map = new FakeCommandMap(known);
        Command previous = command("other:demo"); known.put("other:demo", previous);
        CommandSpecImpl spec = CommandSpecImpl.command("other:demo");
        assertThrows(IllegalStateException.class, () -> new BukkitCommandRegistrar(map, known, "test", CommandRegistrationPolicy.REPLACE_UNQUALIFIED)
                .register("other:demo", spec, new CommandDispatcher(spec)));
        assertSame(previous, known.get("other:demo"));
    }

    @Test void failedStartupRegistrationRollsBackPartialWrites() {
        Map<String, Command> known = new HashMap<String, Command>();
        FakeCommandMap map = new FakeCommandMap(known, false);
        Command previous = command("demo"); known.put("demo", previous);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        assertThrows(IllegalStateException.class, () -> new BukkitCommandRegistrar(map, known, "test", CommandRegistrationPolicy.REPLACE_UNQUALIFIED)
                .register("demo", spec, new CommandDispatcher(spec)));
        assertSame(previous, known.get("demo"));
        assertFalse(known.containsKey("test:demo"));
        assertFalse(map.lastRegistered.isRegistered());
    }

    private static Command command(String name) {
        return new Command(name) { @Override public boolean execute(CommandSender sender, String label, String[] args) { return true; } };
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
