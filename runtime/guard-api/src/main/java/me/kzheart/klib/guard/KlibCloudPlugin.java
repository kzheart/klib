package me.kzheart.klib.guard;

import java.io.File;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.scope.Scope;

/** 保留父加载器中的 Java 扩展契约；生命周期状态与清理规则由 Kotlin 实现。 */
public abstract class KlibCloudPlugin implements RemotePluginEntrypoint {
    private KlibCloudPluginLifecycle lifecycle;

    protected void load() throws Exception { }
    protected abstract void setup(Scope root);
    protected void disable() throws Exception { }

    @Override public final synchronized void onLoad(PluginHost host) throws Exception { lifecycle().load(host); }
    @Override public final synchronized void onEnable() { lifecycle().enable(); }
    @Override public final synchronized void onDisable() throws Exception { lifecycle().disable(); }
    protected final PluginHost host() { return lifecycle().host(); }
    protected final File dataFolder() { return host().dataFolder(); }
    protected final KLogger logger() { return lifecycle().logger(); }
    protected final Scope rootScope() { return lifecycle().rootScope(); }

    // Do not resolve the Kotlin controller while the host is constructing a subclass.
    private synchronized KlibCloudPluginLifecycle lifecycle() {
        if (lifecycle == null) lifecycle = new KlibCloudPluginLifecycle(this);
        return lifecycle;
    }
}
