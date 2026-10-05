package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandCapability;
import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrigadierBridgeContractTest {
    @Test
    void customBridgeOwnsItsScopeDisposalContract() {
        AtomicInteger commandMapActive = new AtomicInteger();
        CommandBridge commandMap = new CommandBridge() {
            @Override
            public Disposable register(
                    String name,
                    CommandSpec spec,
                    CommandDispatcher dispatcher
            ) {
                commandMapActive.incrementAndGet();
                return commandMapActive::decrementAndGet;
            }
        };
        RecordingRegistry registry = new RecordingRegistry();
        CommandCapability capability = new CommandCapabilityImpl(
                new BrigadierBridge(commandMap, registry));
        ScopeImpl scope = ScopeImpl.create("test", owner -> {
            owner.registerCapability(CommandCapability.class, capability);
            owner.command("demo", spec -> spec.literal("reload", reload ->
                    reload.executes(context -> {
                    })));
        });

        assertEquals(1, commandMapActive.get());
        assertEquals(1, registry.active.size());

        scope.rebuild();

        assertEquals(1, commandMapActive.get());
        assertEquals(1, registry.active.size());

        scope.close();
        assertEquals(0, commandMapActive.get());
        assertEquals(0, registry.active.size());
    }

    @Test
    void duplicateProjectionIsRejectedBeforeRawRegistration() {
        AtomicInteger commandMapActive = new AtomicInteger();
        CommandBridge commandMap = (name, spec, dispatcher) -> {
            commandMapActive.incrementAndGet();
            return commandMapActive::decrementAndGet;
        };
        RecordingRegistry registry = new RecordingRegistry() {
            @Override
            public Disposable register(String name, BrigadierTree tree) {
                if (active.containsKey(name)) {
                    throw new IllegalStateException("duplicate Brigadier command: " + name);
                }
                return super.register(name, tree);
            }
        };
        BrigadierBridge bridge = new BrigadierBridge(commandMap, registry);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.executes(context -> {
        });

        Disposable first = bridge.register("demo", spec, new CommandDispatcher(spec));
        assertThrows(IllegalStateException.class,
                () -> bridge.register("demo", spec, new CommandDispatcher(spec)));

        assertEquals(1, commandMapActive.get());
        assertEquals(1, registry.active.size());

        first.dispose();
        assertEquals(0, commandMapActive.get());
        assertEquals(0, registry.active.size());
    }

    private static class RecordingRegistry implements BrigadierBridge.Registry {
        final Map<String, BrigadierTree> active = new HashMap<String, BrigadierTree>();

        @Override
        public Disposable register(final String name, BrigadierTree tree) {
            active.put(name, tree);
            return () -> active.remove(name);
        }

    }
}
