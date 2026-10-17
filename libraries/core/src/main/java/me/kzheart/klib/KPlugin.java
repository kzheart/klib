package me.kzheart.klib;

import me.kzheart.klib.command.api.Commands;
import me.kzheart.klib.component.Components;
import me.kzheart.klib.component.KContext;
import me.kzheart.klib.config.api.Configs;
import me.kzheart.klib.event.Events;
import me.kzheart.klib.scheduler.Tasks;
import me.kzheart.klib.scope.Scope;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Java extension boundary: the non-final static instance() method remains hideable
 * by existing Java plugin subclasses. All lifecycle state lives in the Kotlin core.
 */
public abstract class KPlugin extends JavaPlugin {
    private KPluginLifecycle lifecycle;
    private synchronized KPluginLifecycle lifecycle() {
        if (lifecycle == null) lifecycle = new KPluginLifecycle(this, this::setup);
        return lifecycle;
    }

    protected void setup(Scope root) { setup(); }
    protected void setup() { }
    public final KContext context() { return lifecycle().context(); }
    protected final Commands commands() { return context().commands(); }
    protected final Configs configs() { return context().configs(); }
    protected final Events events() { return context().events(); }
    protected final Tasks tasks() { return context().tasks(); }
    protected final Components components() { return context().components(); }
    @Override public final void onEnable() { lifecycle().enable(); }
    @Override public final void onDisable() { lifecycle().disable(); }
    public final Scope rootScope() { return lifecycle().rootScope(); }
    public final boolean rebuild() { return lifecycle().rebuild(); }
    public final KLogger logger() { return lifecycle().logger(); }
    public static KPlugin instance() { return KPluginLifecycle.instance(); }
}
