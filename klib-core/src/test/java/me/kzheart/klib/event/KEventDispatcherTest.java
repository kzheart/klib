package me.kzheart.klib.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class KEventDispatcherTest {
    @Test
    void oneBukkitRouteFansOutAndScopeCloseDetachesHandlers() throws EventException {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        AtomicReference<Listener> listener = new AtomicReference<Listener>();
        AtomicReference<EventExecutor> executor = new AtomicReference<EventExecutor>();

        Plugin plugin = proxy(Plugin.class);
        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (target, method, arguments) -> {
                    if ("registerEvent".equals(method.getName())) {
                        registrations.incrementAndGet();
                        listener.set((Listener) arguments[1]);
                        executor.set((EventExecutor) arguments[3]);
                    }
                    return defaultValue(method.getReturnType());
                });
        ScopeImpl scope = new ScopeImpl("event-test");
        KEventDispatcher dispatcher = new KEventDispatcher(
                plugin,
                pluginManager,
                new KLogger(Logger.getLogger("KEventDispatcherTest")));

        dispatcher.on(scope, TestEvent.class, event -> handled.incrementAndGet());
        dispatcher.on(scope, TestEvent.class, event -> handled.incrementAndGet());

        assertEquals(1, registrations.get());
        executor.get().execute(listener.get(), new TestEvent());
        assertEquals(2, handled.get());

        scope.close();
        executor.get().execute(listener.get(), new TestEvent());
        assertEquals(2, handled.get());
        assertThrows(
                IllegalStateException.class,
                () -> dispatcher.on(scope, TestEvent.class, event -> handled.incrementAndGet()));
        assertEquals(1, registrations.get());
        dispatcher.dispose();
    }

    @Test
    void siblingEventsOnASharedHandlerListAreFilteredOut() throws EventException {
        AtomicInteger handled = new AtomicInteger();
        AtomicReference<Listener> listener = new AtomicReference<Listener>();
        AtomicReference<EventExecutor> executor = new AtomicReference<EventExecutor>();

        Plugin plugin = proxy(Plugin.class);
        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (target, method, arguments) -> {
                    if ("registerEvent".equals(method.getName())) {
                        listener.set((Listener) arguments[1]);
                        executor.set((EventExecutor) arguments[3]);
                    }
                    return defaultValue(method.getReturnType());
                });
        ScopeImpl scope = new ScopeImpl("shared-handler-list");
        KEventDispatcher dispatcher = new KEventDispatcher(
                plugin,
                pluginManager,
                new KLogger(Logger.getLogger("KEventDispatcherTest")));

        dispatcher.on(scope, SharedChildA.class, event -> handled.incrementAndGet());

        executor.get().execute(listener.get(), new SharedChildB());
        assertEquals(0, handled.get());
        executor.get().execute(listener.get(), new SharedChildA());
        assertEquals(1, handled.get());

        scope.close();
        dispatcher.dispose();
    }

    private static <T> T proxy(Class<T> type) {
        Object value = Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (target, method, arguments) -> defaultValue(method.getReturnType()));
        return type.cast(value);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0.0F);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        return null;
    }

    private abstract static class SharedBaseEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    private static final class SharedChildA extends SharedBaseEvent {
    }

    private static final class SharedChildB extends SharedBaseEvent {
    }

    private static final class TestEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}
