package me.kzheart.klib.event;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import me.kzheart.klib.reflect.Declarations;
import me.kzheart.klib.scope.*;
import org.bukkit.event.*;

public final class Events {
    private static final AtomicLong IDS = new AtomicLong();
    private final Scope owner;
    public Events(Scope owner) { this.owner = Objects.requireNonNull(owner, "owner"); }
    public <E extends Event> Disposable on(Class<E> type, Consumer<? super E> handler) {
        return owner.on(type, handler);
    }
    public Disposable register(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        List<Method> methods = new ArrayList<Method>();
        for (Method m : Declarations.methods(listener.getClass())) {
            if (!m.isAnnotationPresent(EventHandler.class)) continue;
            Declarations.voidMethod(m, 1);
            if (!Event.class.isAssignableFrom(m.getParameterTypes()[0])) {
                throw Declarations.invalid(m, "parameter must extend Event");
            }
            methods.add(m);
        }
        return owner.scope("events-" + IDS.incrementAndGet(), child -> {
            for (Method m : methods) {
                EventHandler a = m.getAnnotation(EventHandler.class);
                Class<? extends Event> type = m.getParameterTypes()[0].asSubclass(Event.class);
                child.on(type, a.priority(), a.ignoreCancelled(), e -> Declarations.invoke(listener, m, e));
            }
        });
    }
}
