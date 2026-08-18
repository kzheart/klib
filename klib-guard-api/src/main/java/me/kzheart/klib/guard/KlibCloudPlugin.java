package me.kzheart.klib.guard;

import java.io.File;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.event.KEventDispatcher;
import me.kzheart.klib.scheduler.BukkitSchedulerFactory;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.scope.ScopeImpl;

/** 由 Guard 中央门户加载的单个商品 Klib 生命周期基类。 */
public abstract class KlibCloudPlugin implements RemotePluginEntrypoint {

    private PluginHost host;
    private KLogger logger;
    private ScopeImpl rootScope;

    protected void load() throws Exception {
    }

    protected abstract void setup(Scope root);

    protected void disable() throws Exception {
    }

    @Override
    public final synchronized void onLoad(PluginHost value) throws Exception {
        if (value == null || host != null) throw new IllegalStateException("invalid lifecycle");
        host = value;
        logger = new KLogger(value.logger());
        try {
            load();
        } catch (Exception failure) {
            host = null;
            logger = null;
            throw failure;
        } catch (LinkageError failure) {
            host = null;
            logger = null;
            throw failure;
        }
    }

    @Override
    public final synchronized void onEnable() {
        if (host == null || rootScope != null) throw new IllegalStateException("invalid lifecycle");
        final PluginHost currentHost = host;
        final KLogger currentLogger = logger;
        rootScope = ScopeImpl.create(currentHost.productId(), root -> {
            KEventDispatcher events = root.install(new KEventDispatcher(
                    currentHost.plugin(),
                    currentHost.server().getPluginManager(),
                    currentLogger));
            root.registerCapability(KLogger.class, currentLogger);
            root.registerCapability(KEventDispatcher.class, events);
            root.registerCapability(
                    SchedulerFactory.class,
                    new BukkitSchedulerFactory(
                            currentHost.plugin(),
                            currentHost.server().getScheduler(),
                            currentLogger));
            setup(root);
        });
        currentLogger.success("云端插件已启用: " + currentHost.productId());
    }

    @Override
    public final synchronized void onDisable() throws Exception {
        ScopeImpl currentScope = rootScope;
        rootScope = null;
        RuntimeException closeFailure = null;
        if (currentScope != null) {
            try {
                currentScope.close();
            } catch (RuntimeException failure) {
                closeFailure = failure;
            }
        }
        try {
            if (host != null) disable();
        } finally {
            host = null;
            logger = null;
        }
        if (closeFailure != null) throw closeFailure;
    }

    protected final PluginHost host() {
        PluginHost current = host;
        if (current == null) throw new IllegalStateException("cloud plugin is not loaded");
        return current;
    }

    protected final File dataFolder() {
        return host().dataFolder();
    }

    protected final KLogger logger() {
        KLogger current = logger;
        if (current == null) throw new IllegalStateException("cloud plugin is not loaded");
        return current;
    }

    protected final Scope rootScope() {
        Scope current = rootScope;
        if (current == null || current.isClosed()) {
            throw new IllegalStateException("cloud plugin scope is not active");
        }
        return current;
    }
}
