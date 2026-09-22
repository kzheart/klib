package me.kzheart.klib.component;

import me.kzheart.klib.command.api.Commands;
import me.kzheart.klib.config.api.Configs;
import me.kzheart.klib.event.Events;
import me.kzheart.klib.scheduler.Tasks;

public abstract class KComponent {
    private KContext context;
    final void attach(KContext value) {
        if (context != null) throw new IllegalStateException("Component instance already installed");
        context = value;
    }
    protected abstract void setup();
    public final KContext context() {
        if (context == null || context.scope().isClosed()) throw new IllegalStateException("Component is not active");
        return context;
    }
    protected final Commands commands() { return context().commands(); }
    protected final Configs configs() { return context().configs(); }
    protected final Events events() { return context().events(); }
    protected final Tasks tasks() { return context().tasks(); }
    protected final Components components() { return context().components(); }
}
