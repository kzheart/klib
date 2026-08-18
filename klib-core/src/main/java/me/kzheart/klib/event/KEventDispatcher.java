package me.kzheart.klib.event;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

public final class KEventDispatcher implements Disposable {
    private final Object lifecycleLock = new Object();
    private final Plugin plugin;
    private final PluginManager pluginManager;
    private final KLogger logger;
    private final Map<RouteKey, Route> routes = new HashMap<RouteKey, Route>();
    private final EventExecutor executor = new SharedExecutor();

    private volatile boolean closed;

    public KEventDispatcher(Plugin plugin, PluginManager pluginManager, KLogger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public <E extends Event> Disposable on(
            Scope scope,
            Class<E> eventType,
            Consumer<? super E> handler
    ) {
        return on(scope, eventType, EventPriority.NORMAL, false, handler);
    }

    public <E extends Event> Disposable on(
            Scope scope,
            Class<E> eventType,
            EventPriority priority,
            boolean ignoreCancelled,
            Consumer<? super E> handler
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(handler, "handler");

        Subscription<E> subscription = scope.install(new Subscription<E>(scope, eventType, handler));
        try {
            synchronized (lifecycleLock) {
                if (closed) {
                    subscription.markDisposed();
                    throw new IllegalStateException("Event dispatcher is closed");
                }
                if (subscription.disposed) {
                    throw new IllegalStateException("Scope closed while registering event handler");
                }
                RouteKey key = new RouteKey(eventType, priority, ignoreCancelled);
                Route route = routes.get(key);
                if (route == null) {
                    route = new Route(key);
                    try {
                        pluginManager.registerEvent(
                                eventType,
                                route.listener,
                                priority,
                                executor,
                                plugin,
                                ignoreCancelled);
                    } catch (RuntimeException failure) {
                        subscription.markDisposed();
                        throw failure;
                    }
                    routes.put(key, route);
                }
                subscription.attach(route);
                route.subscriptions.add(subscription);
            }
        } catch (RuntimeException failure) {
            scope.remove(subscription);
            throw failure;
        }
        return subscription;
    }

    @Override
    public void dispose() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            for (Route route : routes.values()) {
                route.subscriptions.clear();
                HandlerList.unregisterAll(route.listener);
            }
            routes.clear();
        }
    }

    private void remove(Subscription<?> subscription) {
        synchronized (lifecycleLock) {
            Route route = subscription.route;
            route.subscriptions.remove(subscription);
            if (route.subscriptions.isEmpty() && routes.remove(route.key) != null) {
                HandlerList.unregisterAll(route.listener);
            }
        }
    }

    private final class SharedExecutor implements EventExecutor {
        @Override
        public void execute(Listener listener, Event event) throws EventException {
            RouteListener routeListener = (RouteListener) listener;
            for (Subscription<?> subscription : routeListener.route.subscriptions) {
                try {
                    subscription.accept(event);
                } catch (Throwable failure) {
                    logger.error("事件处理失败: " + event.getEventName(), failure);
                }
            }
        }
    }

    private final class Subscription<E extends Event> implements Disposable {
        private final Scope owner;
        private final Class<E> eventType;
        private final Consumer<? super E> handler;
        private Route route;
        private volatile boolean disposed;

        private Subscription(Scope owner, Class<E> eventType, Consumer<? super E> handler) {
            this.owner = owner;
            this.eventType = eventType;
            this.handler = handler;
        }

        private void attach(Route route) {
            this.route = route;
        }

        private void markDisposed() {
            disposed = true;
        }

        private void accept(Event event) {
            // 共享的 HandlerList 也会派发兄弟子类事件，需要跳过这些事件。
            if (!eventType.isInstance(event)) {
                return;
            }
            if (disposed || closed) {
                return;
            }
            handler.accept(eventType.cast(event));
        }

        @Override
        public void dispose() {
            synchronized (lifecycleLock) {
                if (disposed) {
                    return;
                }
                disposed = true;
                if (route == null) {
                    return;
                }
            }
            remove(this);
            owner.remove(this);
        }
    }

    private static final class Route {
        private final RouteKey key;
        private final RouteListener listener;
        private final CopyOnWriteArrayList<Subscription<?>> subscriptions =
                new CopyOnWriteArrayList<Subscription<?>>();

        private Route(RouteKey key) {
            this.key = key;
            this.listener = new RouteListener(this);
        }
    }

    private static final class RouteListener implements Listener {
        private final Route route;

        private RouteListener(Route route) {
            this.route = route;
        }
    }

    private static final class RouteKey {
        private final Class<? extends Event> eventType;
        private final EventPriority priority;
        private final boolean ignoreCancelled;

        private RouteKey(
                Class<? extends Event> eventType,
                EventPriority priority,
                boolean ignoreCancelled
        ) {
            this.eventType = eventType;
            this.priority = priority;
            this.ignoreCancelled = ignoreCancelled;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RouteKey)) {
                return false;
            }
            RouteKey that = (RouteKey) other;
            return eventType.equals(that.eventType)
                    && priority == that.priority
                    && ignoreCancelled == that.ignoreCancelled;
        }

        @Override
        public int hashCode() {
            int result = eventType.hashCode();
            result = 31 * result + priority.hashCode();
            return 31 * result + (ignoreCancelled ? 1 : 0);
        }
    }
}
