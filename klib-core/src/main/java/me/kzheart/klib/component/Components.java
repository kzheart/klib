package me.kzheart.klib.component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import me.kzheart.klib.reflect.Declarations;
import me.kzheart.klib.scope.Scope;

public final class Components {
    private static final AtomicLong IDS = new AtomicLong();
    private final Scope owner;
    public Components(Scope owner) { this.owner = Objects.requireNonNull(owner, "owner"); }
    public ComponentHandle install(Object component) {
        Objects.requireNonNull(component, "component");
        Method start = null;
        Method stop = null;
        for (Method method : Declarations.methods(component.getClass())) {
            if (method.isAnnotationPresent(OnStart.class)) {
                Declarations.voidMethod(method, 0);
                if (start != null) throw Declarations.invalid(method, "multiple @OnStart methods");
                start = method;
            }
            if (method.isAnnotationPresent(OnStop.class)) {
                Declarations.voidMethod(method, 0);
                if (stop != null) throw Declarations.invalid(method, "multiple @OnStop methods");
                stop = method;
            }
        }
        final Method onStart = start;
        final Method onStop = stop;
        Scope scope = owner.scope("component-" + IDS.incrementAndGet(), child -> {
            KContext context = new KContext(child);
            if (component instanceof KComponent) ((KComponent) component).attach(context);
            // Installed first, therefore called after registrations have been disposed.
            if (onStop != null) child.install(() -> Declarations.invoke(component, onStop));
            if (onStart != null) Declarations.invoke(component, onStart);
            if (component instanceof KComponent) ((KComponent) component).setup();
        });
        return new ComponentHandle(scope);
    }
}
