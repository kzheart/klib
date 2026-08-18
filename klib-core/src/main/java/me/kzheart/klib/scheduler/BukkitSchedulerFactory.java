package me.kzheart.klib.scheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

public final class BukkitSchedulerFactory implements SchedulerFactory {
    private final Plugin plugin;
    private final BukkitScheduler scheduler;
    private final KLogger logger;
    private final Map<Scope, KScheduler> adapters = new HashMap<Scope, KScheduler>();

    public BukkitSchedulerFactory(Plugin plugin, BukkitScheduler scheduler, KLogger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public KScheduler forScope(final Scope scope) {
        Objects.requireNonNull(scope, "scope");
        synchronized (adapters) {
            KScheduler existing = adapters.get(scope);
            if (existing != null) {
                return existing;
            }
        }
        KScheduler created = new BukkitSchedulerAdapter(plugin, scope, scheduler, logger);
        synchronized (adapters) {
            KScheduler existing = adapters.get(scope);
            if (existing != null) {
                return existing;
            }
            adapters.put(scope, created);
        }
        // 作用域关闭时移除缓存项；在缓存锁外安装资源，
        // 以保持“作用域锁 -> 缓存锁”的单向加锁顺序。
        try {
            scope.install(new Disposable() {
                @Override
                public void dispose() {
                    synchronized (adapters) {
                        adapters.remove(scope);
                    }
                }
            });
        } catch (RuntimeException failure) {
            synchronized (adapters) {
                adapters.remove(scope);
            }
            throw failure;
        }
        return created;
    }
}
