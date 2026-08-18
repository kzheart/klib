package me.kzheart.klib.hook.papi;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import org.bukkit.plugin.Plugin;

/** 由作用域持有的 PlaceholderAPI DSL 入口。 */
public final class Papi {
    private static final Logger LOGGER = Logger.getLogger(Papi.class.getName());

    private Papi() {
    }

    public static PapiRegistration register(
            Scope scope,
            PapiRegistrar registrar,
            String identifier,
            Consumer<? super PapiDsl> configure
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(registrar, "registrar");
        Objects.requireNonNull(configure, "configure");
        String normalized = normalizeIdentifier(identifier);
        PapiDsl dsl = new PapiDsl();
        configure.accept(dsl);
        Disposable delegate = Objects.requireNonNull(
                registrar.register(normalized, dsl.build()),
                "registrar returned null registration");
        PapiRegistration registration = new PapiRegistration(
                normalized, registrar.available(), delegate);
        try {
            return scope.install(registration);
        } catch (RuntimeException failure) {
            registration.dispose();
            throw failure;
        }
    }

    /**
     * 通过 {@link #detectRegistrar(Plugin)} 注册，使未安装 PlaceholderAPI 的服务器
     * 降级为空操作注册，而不是抛出 {@link NoClassDefFoundError}。
     */
    public static PapiRegistration registerBukkit(
            Scope scope,
            Plugin plugin,
            String identifier,
            Consumer<? super PapiDsl> configure
    ) {
        return register(scope, detectRegistrar(plugin), identifier, configure);
    }

    /**
     * 插件已安装时返回真正的 PlaceholderAPI 注册器，否则返回空操作注册器。
     * {@link BukkitPapiRegistrar} 仅在成功分支中加载类，从而隔离 PlaceholderAPI 链接。
     */
    public static PapiRegistrar detectRegistrar(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        try {
            if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                return noopRegistrar();
            }
            return newBukkitRegistrar(plugin);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.log(Level.WARNING,
                    "PlaceholderAPI is present but unavailable; using noop registrar", failure);
            return noopRegistrar();
        }
    }

    /** 不接触 PlaceholderAPI 且接受所有扩展的注册器。 */
    public static PapiRegistrar noopRegistrar() {
        return new PapiRegistrar() {
            @Override
            public Disposable register(String identifier, PapiExpansion expansion) {
                return () -> { };
            }

            @Override
            public boolean available() {
                return false;
            }
        };
    }

    private static PapiRegistrar newBukkitRegistrar(Plugin plugin) {
        return new BukkitPapiRegistrar(plugin);
    }

    private static String normalizeIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("invalid PAPI identifier: " + identifier);
        }
        return normalized;
    }
}
