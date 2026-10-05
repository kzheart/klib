package me.kzheart.klib.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import me.kzheart.klib.scope.Disposable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PaperLifecycleCommandBridgeTest {
    @Test void rawExecutionRetainsChineseColonCaseInsensitiveParsingAndLocalizedFailures() {
        Fixture fixture = new Fixture();
        AtomicReference<String> received = new AtomicReference<String>();
        Arg<String> value = Arguments.string("item");
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("give", node -> node.argument(value, arg -> arg.executes(ctx -> received.set(ctx.get(value)))));
        fixture.bridge.register("demo", spec, dispatcher(spec));
        TestSenders.SenderFixture sender = TestSenders.console();
        BasicCommand basic = fixture.registrar.last;
        basic.execute(sender::sender, new String[]{"GiVe", "物品:一"});
        assertEquals("物品:一", received.get());
        basic.execute(sender::sender, new String[]{"unknown"});
        assertFalse(sender.messages().isEmpty());
        assertTrue(basic.suggest(sender::sender, new String[]{"G"}).contains("give"));
    }

    @Test void rootPermissionsStillUseLocalizedDispatcherMessages() {
        Fixture fixture = new Fixture(); AtomicInteger calls = new AtomicInteger();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.permission("test.admin").executes(ctx -> calls.incrementAndGet());
        fixture.bridge.register("demo", spec, dispatcher(spec));
        TestSenders.SenderFixture denied = TestSenders.console();
        assertTrue(fixture.registrar.last.canUse(denied.sender()));
        fixture.registrar.last.execute(denied::sender, new String[0]);
        assertEquals(0, calls.get());
        assertFalse(denied.messages().isEmpty());
        assertTrue(fixture.registrar.last.suggest(denied::sender, new String[]{""}).isEmpty());
    }

    @Test void disposeImmediatelyDisablesAllRawEntryPointsAndSkipsLifecycleReplay() throws Exception {
        Fixture fixture = new Fixture(); AtomicInteger calls = new AtomicInteger();
        CommandSpecImpl spec = CommandSpecImpl.command("demo"); spec.executes(ctx -> calls.incrementAndGet());
        Disposable handle = fixture.bridge.register("demo", spec, dispatcher(spec));
        BasicCommand basic = fixture.registrar.last;
        Thread closer = new Thread(handle::dispose); closer.start(); closer.join();
        CommandSourceStack source = () -> TestSenders.console().sender();
        basic.execute(source, new String[0]);
        assertEquals(0, calls.get());
        assertFalse(basic.canUse(source.getSender()));
        assertTrue(basic.suggest(source, new String[]{""}).isEmpty());
        fixture.manager.replay();
        assertEquals(1, fixture.registrar.registrations);
    }

    @Test void repeatedLifecycleCallbackUsesNewRegistrarWhileLive() {
        Fixture fixture = new Fixture();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        fixture.bridge.register("demo", spec, dispatcher(spec));
        fixture.freshWrappers = true;
        fixture.manager.replay();
        assertEquals(2, fixture.registrar.registrations);
        fixture.enabled.set(false); fixture.manager.replay();
        assertEquals(2, fixture.registrar.registrations);
        assertFalse(fixture.registrar.last.canUse(TestSenders.console().sender()));
    }

    @Test void duplicateDeclarationAndDefaultCollisionNeverOverwriteExistingCommand() {
        Fixture fixture = new Fixture(); CommandSpecImpl spec = CommandSpecImpl.command("demo");
        fixture.bridge.register("demo", spec, dispatcher(spec));
        assertThrows(IllegalStateException.class, () -> fixture.bridge.register("demo", spec, dispatcher(spec)));
        Fixture occupied = new Fixture(); Command old = new OwnedCommand("demo", fixture.plugin);
        occupied.known.put("demo", old);
        assertThrows(IllegalStateException.class, () -> occupied.bridge.register("demo", spec, dispatcher(spec)));
        assertSame(old, occupied.known.get("demo")); assertEquals(0, occupied.registrar.registrations);
    }

    @Test void explicitReplacementKeepsOtherNamespaceAndDoesNotRestoreOnClose() {
        Fixture fixture = new Fixture(CommandRegistrationPolicy.REPLACE_UNQUALIFIED);
        Command prior = new OwnedCommand("demo", fixture.plugin);
        fixture.known.put("demo", prior); fixture.known.put("other:demo", prior);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        Disposable handle = fixture.bridge.register("demo", spec, dispatcher(spec));
        Command current = fixture.known.get("demo"); handle.dispose();
        assertNotSame(prior, current); assertSame(current, fixture.known.get("demo"));
        assertSame(prior, fixture.known.get("other:demo"));
    }

    @Test void deferredCallbackDoesNotRegisterClosedScope() {
        Fixture fixture = new Fixture(); fixture.manager.immediate = false;
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        fixture.bridge.register("demo", spec, dispatcher(spec)).dispose(); fixture.manager.replay();
        assertEquals(0, fixture.registrar.registrations);
    }

    @Test void failedLifecycleReplayDeactivatesExistingRawBindingPermanently() {
        Fixture fixture = new Fixture(); AtomicInteger calls = new AtomicInteger();
        CommandSpecImpl spec = CommandSpecImpl.command("demo"); spec.executes(ctx -> calls.incrementAndGet());
        fixture.bridge.register("demo", spec, dispatcher(spec));
        BasicCommand basic = fixture.registrar.last;
        fixture.registrar.fail = true;
        assertThrows(IllegalStateException.class, fixture.manager::replay);
        CommandSourceStack source = () -> TestSenders.console().sender();
        assertFalse(basic.canUse(source.getSender()));
        basic.execute(source, new String[0]);
        assertEquals(0, calls.get());
        assertTrue(basic.suggest(source, new String[]{""}).isEmpty());
        fixture.registrar.fail = false; fixture.manager.replay();
        assertEquals(1, fixture.registrar.registrations);
    }

    @Test void explicitReplacementStillRejectsExistingPluginNamespace() {
        Fixture fixture = new Fixture(CommandRegistrationPolicy.REPLACE_UNQUALIFIED);
        Command previous = new OwnedCommand("test:demo", fixture.plugin);
        fixture.known.put("test:demo", previous);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        assertThrows(IllegalStateException.class, () -> fixture.bridge.register("demo", spec, dispatcher(spec)));
        assertSame(previous, fixture.known.get("test:demo"));
        assertEquals(0, fixture.registrar.registrations);
    }

    private static CommandDispatcher dispatcher(CommandSpecImpl spec) {
        return new CommandDispatcher(spec, BukkitPlayerResolver.INSTANCE, PlainRichTextSink.INSTANCE, DefaultCommandMessages.INSTANCE);
    }

    public interface LifecyclePlugin extends Plugin { Object getLifecycleManager(); }
    public static final class Manager {
        final List<LifecycleEventHandler> handlers = new ArrayList<LifecycleEventHandler>();
        Registrar registrar; boolean immediate = true;
        public void registerEventHandler(Object type, LifecycleEventHandler handler) {
            handlers.add(handler); if (immediate) handler.run(new RegistrationEvent(registrar));
        }
        void replay() { for (LifecycleEventHandler handler : handlers) handler.run(new RegistrationEvent(registrar)); }
    }
    public static final class RegistrationEvent {
        private final Registrar registrar;
        RegistrationEvent(Registrar registrar) { this.registrar = registrar; }
        public Registrar registrar() { return registrar; }
    }
    public static final class Registrar {
        Fixture fixture; BasicCommand last; int registrations; boolean fail;
        public Collection<String> register(String label, String description, Collection<String> aliases, BasicCommand command) {
            if (fail) throw new IllegalStateException("registrar rejected registration");
            last = command; registrations++;
            OwnedCommand wrapper = new OwnedCommand(label, fixture.plugin);
            fixture.known.put(label, wrapper); fixture.known.put("test:" + label, wrapper);
            return Arrays.asList(label, "test:" + label);
        }
    }
    static final class Fixture {
        final AtomicBoolean enabled = new AtomicBoolean(true);
        final Manager manager = new Manager(); final Registrar registrar = new Registrar();
        final Map<String, Command> known = new HashMap<String, Command>();
        final Plugin plugin; final CommandMap map; final CommandBridge bridge;
        boolean freshWrappers;
        Fixture() { this(CommandRegistrationPolicy.REJECT); }
        Fixture(CommandRegistrationPolicy policy) {
            manager.registrar = registrar; registrar.fixture = this;
            plugin = (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{LifecyclePlugin.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getName": return "Test";
                    case "isEnabled": return enabled.get();
                    case "getLifecycleManager": return manager;
                    case "getLogger": return Logger.getLogger("test");
                    case "toString": return "Test plugin";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: return null;
                }
            });
            map = (CommandMap) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CommandMap.class},
                    (proxy, method, args) -> {
                        if (!"getCommand".equals(method.getName())) return null;
                        Command command = known.get(args[0]);
                        if (freshWrappers && command instanceof PluginIdentifiableCommand) {
                            return new OwnedCommand(command.getName(), ((PluginIdentifiableCommand) command).getPlugin());
                        }
                        return command;
                    });
            bridge = PaperLifecycleCommandBridge.discover(plugin, map, policy);
        }
    }
    static final class OwnedCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin plugin;
        OwnedCommand(String name, Plugin plugin) { super(name); this.plugin = plugin; }
        @Override public Plugin getPlugin() { return plugin; }
        @Override public boolean execute(CommandSender sender, String label, String[] args) { return false; }
    }
}
