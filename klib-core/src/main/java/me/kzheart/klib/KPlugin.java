package me.kzheart.klib;

import java.util.function.Consumer;
import me.kzheart.klib.event.KEventDispatcher;
import me.kzheart.klib.scheduler.BukkitSchedulerFactory;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Klib 插件基类：在 {@code onEnable} 中创建根作用域并执行 {@link #setup(Scope)}，
 * 在 {@code onDisable} 中逆序释放整棵作用域树。
 *
 * <p>约束：同一服务端只允许存在一份未重定位的 klib，因此第二个 {@code KPlugin} 实例会被禁用；
 * {@link #rootScope()}、{@link #logger()} 和 {@link #instance()} 只在插件处于启用状态时可用。</p>
 */
public abstract class KPlugin extends JavaPlugin {
    private static volatile KPlugin activeInstance;

    private volatile ScopeImpl rootScope;
    private KLogger klibLogger;

    protected abstract void setup(Scope root);

    @Override
    public final void onEnable() {
        KPlugin active = activeInstance;
        if (active != null && active != this) {
            getLogger().severe("检测到另一个已激活的 KPlugin 实例（" + describe(active) + "），本插件将被禁用："
                    + "同一服务端只允许存在一份未重定位的 klib。"
                    + "请使用 me.kzheart.klib Gradle 插件打包，把 klib 重定位到插件自己的包名下。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        activeInstance = this;
        klibLogger = new KLogger(getLogger());

        try {
            rootScope = PluginScopeBootstrap.create(getName(), new Consumer<Scope>() {
                @Override
                public void accept(Scope root) {
                    installCoreCapabilities(root);
                    setup(root);
                }
            });
            klibLogger.success("插件已启用");
        } catch (Throwable failure) {
            activeInstance = null;
            klibLogger.error("插件初始化失败，已清理部分初始化资源", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public final void onDisable() {
        ScopeImpl current = rootScope;
        rootScope = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException failure) {
                logger().error("插件关闭时有资源释放失败", failure);
            }
        }
        if (activeInstance == this) {
            activeInstance = null;
        }
    }

    public final Scope rootScope() {
        Scope current = rootScope;
        if (current == null || current.isClosed()) {
            throw new IllegalStateException("Plugin scope is not active: available only after onEnable"
                    + " succeeded and before onDisable closes it");
        }
        return current;
    }

    /**
     * 重新执行初始化图。重建失败时返回 {@code false}；
     * 此时插件已完成清理并被禁用。
     */
    public final boolean rebuild() {
        return PluginScopeBootstrap.rebuild(rootScope(), this::failRebuild);
    }

    public final KLogger logger() {
        KLogger current = klibLogger;
        if (current == null) {
            throw new IllegalStateException("Plugin logger is not initialized: available only from"
                    + " onEnable onwards, not from the constructor or onLoad");
        }
        return current;
    }

    public static KPlugin instance() {
        KPlugin current = activeInstance;
        if (current == null) {
            throw new IllegalStateException("No active KPlugin instance: available only after a KPlugin"
                    + " has finished onEnable and before it is disabled");
        }
        return current;
    }

    private static String describe(KPlugin other) {
        try {
            String name = other.getName();
            return name == null || name.isEmpty() ? other.getClass().getName() : name;
        } catch (RuntimeException | LinkageError failure) {
            return other.getClass().getName();
        }
    }

    private void installCoreCapabilities(Scope root) {
        KEventDispatcher events = root.install(new KEventDispatcher(
                this,
                getServer().getPluginManager(),
                logger()));
        root.registerCapability(KLogger.class, logger());
        root.registerCapability(KEventDispatcher.class, events);
        root.registerCapability(
                SchedulerFactory.class,
                new BukkitSchedulerFactory(this, getServer().getScheduler(), logger()));
    }

    private void failRebuild(Throwable failure) {
        logger().error("插件重载失败，已关闭残留资源并禁用插件", failure);
        ScopeImpl current = rootScope;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        getServer().getPluginManager().disablePlugin(this);
    }
}
