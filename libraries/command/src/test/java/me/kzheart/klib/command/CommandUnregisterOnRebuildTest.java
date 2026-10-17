package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandCapability;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandUnregisterOnRebuildTest {
    @Test
    void rebuildUnregistersOldCommandBeforeRegisteringReplacement() {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger unregisters = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        CommandBridge bridge = (name, spec, dispatcher) -> {
            registrations.incrementAndGet();
            active.incrementAndGet();
            return () -> {
                unregisters.incrementAndGet();
                active.decrementAndGet();
            };
        };
        CommandCapabilityImpl capability = new CommandCapabilityImpl(bridge);
        ScopeImpl scope = ScopeImpl.create("test", owner -> {
            owner.registerCapability(CommandCapability.class, capability);
            owner.command("demo", spec -> spec.executes(context -> {
            }));
        });

        assertEquals(1, registrations.get());
        assertEquals(1, active.get());

        scope.rebuild();

        assertEquals(2, registrations.get());
        assertEquals(1, unregisters.get());
        assertEquals(1, active.get());

        scope.close();
        assertEquals(2, unregisters.get());
        assertEquals(0, active.get());
    }
}
