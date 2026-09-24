package me.kzheart.klib.cooldown;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.event.KEventDispatcher;
import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventException;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownsTest {
    private final MutableClock clock = new MutableClock();

    @Test
    void acquireBlocksUntilDeadlineAndReportsRemaining() {
        Cooldowns<String> cooldowns = Cooldowns.create(clock);

        assertTrue(cooldowns.tryAcquire("fire", Duration.ofSeconds(5)).acquired());
        clock.advance(2_000L);
        Cooldowns.Attempt blocked = cooldowns.tryAcquire("fire", Duration.ofSeconds(5));
        assertFalse(blocked.acquired());
        assertEquals(Duration.ofSeconds(3), blocked.remaining());
        assertEquals(Duration.ofSeconds(3), cooldowns.remaining("fire"));

        clock.advance(3_000L);
        assertTrue(cooldowns.isReady("fire"));
        assertTrue(cooldowns.tryAcquire("fire", Duration.ofSeconds(5)).acquired());
    }

    @Test
    void reduceExtendAndClearIfWorkPerKey() {
        Cooldowns<String> cooldowns = Cooldowns.create(clock);
        cooldowns.set("w:1", Duration.ofSeconds(10));
        cooldowns.set("w:2", Duration.ofSeconds(10));
        cooldowns.set("p:1", Duration.ofSeconds(10));

        cooldowns.reduce("w:1", Duration.ofSeconds(4));
        assertEquals(Duration.ofSeconds(6), cooldowns.remaining("w:1"));
        cooldowns.reduce("w:1", Duration.ofSeconds(60));
        assertTrue(cooldowns.isReady("w:1"));

        cooldowns.extend("p:1", Duration.ofSeconds(5));
        assertEquals(Duration.ofSeconds(15), cooldowns.remaining("p:1"));
        cooldowns.extend("fresh", Duration.ofSeconds(2));
        assertEquals(Duration.ofSeconds(2), cooldowns.remaining("fresh"));

        cooldowns.clearIf(key -> key.startsWith("w:"));
        assertTrue(cooldowns.isReady("w:2"));
        assertFalse(cooldowns.isReady("p:1"));
    }

    @Test
    void expiredEntriesAreNotCountedAndDisposeClears() {
        Cooldowns<String> cooldowns = Cooldowns.create(clock);
        cooldowns.set("a", Duration.ofSeconds(1));
        cooldowns.set("b", Duration.ofSeconds(5));
        clock.advance(1_000L);
        assertEquals(1, cooldowns.size());
        cooldowns.dispose();
        assertEquals(0, cooldowns.size());
    }

    @Test
    void zeroDurationDoesNotStartAndNegativeIsRejected() {
        Cooldowns<String> cooldowns = Cooldowns.create(clock);
        assertTrue(cooldowns.tryAcquire("x", Duration.ZERO).acquired());
        assertTrue(cooldowns.tryAcquire("x", Duration.ZERO).acquired());
        assertThrows(IllegalArgumentException.class, () -> cooldowns.set("x", Duration.ofMillis(-1)));
    }

    @Test
    void hugeDurationSaturatesInsteadOfOverflowing() {
        Cooldowns<String> cooldowns = Cooldowns.create(clock);
        cooldowns.set("forever", Duration.ofSeconds(Long.MAX_VALUE));
        assertFalse(cooldowns.isReady("forever"));
    }

    @Test
    void perPlayerCooldownsAreRemovedOnQuitAndClearedWithScope() throws EventException {
        AtomicReference<Listener> listener = new AtomicReference<Listener>();
        AtomicReference<EventExecutor> executor = new AtomicReference<EventExecutor>();
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class}, (target, method, arguments) -> {
                    if ("registerEvent".equals(method.getName())) {
                        listener.set((Listener) arguments[1]);
                        executor.set((EventExecutor) arguments[3]);
                    }
                    return null;
                });
        ScopeImpl scope = new ScopeImpl("cooldowns");
        scope.registerCapability(KEventDispatcher.class, scope.install(new KEventDispatcher(
                proxy(Plugin.class), manager, new KLogger(Logger.getLogger("test")))));

        Cooldowns<UUID> cooldowns = Cooldowns.perPlayer(scope, clock);
        UUID leaving = UUID.randomUUID();
        UUID staying = UUID.randomUUID();
        cooldowns.set(leaving, Duration.ofMinutes(1));
        cooldowns.set(staying, Duration.ofMinutes(1));

        executor.get().execute(listener.get(), new PlayerQuitEvent(player(leaving), "bye"));
        assertTrue(cooldowns.isReady(leaving));
        assertFalse(cooldowns.isReady(staying));

        scope.close();
        assertTrue(cooldowns.isReady(staying));
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(CooldownsTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (target, method, arguments) -> "getUniqueId".equals(method.getName()) ? id : null);
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (target, method, arguments) -> null));
    }

    private static final class MutableClock extends Clock {
        private long millis = 1_000_000L;

        void advance(long delta) {
            millis += delta;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
