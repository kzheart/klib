package me.kzheart.klib.hook.papi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class PapiDetectionTest {

    @Test
    void missingPlaceholderApiFallsBackToNoopRegistrar() {
        Plugin plugin = plugin("Sample", false);

        PapiRegistrar registrar = Papi.detectRegistrar(plugin);

        assertFalse(registrar instanceof BukkitPapiRegistrar);
        ScopeImpl scope = new ScopeImpl("papi");
        PapiRegistration registration = Papi.registerBukkit(
                scope, plugin, "sample", dsl -> dsl.key("value", player -> "ok"));
        assertNotNull(registration);
        assertFalse(registration.isAvailable());
        scope.close();
        assertTrue(registration.isDisposed());
    }

    @Test
    void installedPlaceholderApiSelectsTheBukkitRegistrar() {
        Plugin plugin = plugin("Sample", true);

        assertTrue(Papi.detectRegistrar(plugin) instanceof BukkitPapiRegistrar);
    }

    @Test
    void disabledPlaceholderApiFallsBackToNoopRegistrar() {
        Plugin plugin = plugin("Sample", true, false, null);

        assertFalse(Papi.detectRegistrar(plugin) instanceof BukkitPapiRegistrar);
    }

    @Test
    void registrarLinkageFailureFallsBackToNoopRegistrar() {
        Plugin plugin = plugin("Sample", true, true, new NoClassDefFoundError(
                "me/clip/placeholderapi/expansion/PlaceholderExpansion"));

        assertFalse(Papi.detectRegistrar(plugin) instanceof BukkitPapiRegistrar);
    }

    @Test
    void registrarRuntimeFailureFallsBackToNoopRegistrar() {
        Plugin plugin = plugin("Sample", true, true, new IllegalStateException("broken metadata"));

        assertFalse(Papi.detectRegistrar(plugin) instanceof BukkitPapiRegistrar);
    }

    private static Plugin plugin(String name, boolean papiInstalled) {
        return plugin(name, papiInstalled, papiInstalled, null);
    }

    private static Plugin plugin(
            String name,
            boolean papiPresent,
            boolean papiEnabled,
            Throwable registrarFailure
    ) {
        PluginDescriptionFile description = new PluginDescriptionFile(name, "1.0", "main");
        PluginManager pluginManager = proxy(PluginManager.class, (proxy, method, arguments) -> {
            if (method.getName().equals("isPluginEnabled")
                    && "PlaceholderAPI".equals(arguments[0])) {
                return papiEnabled;
            }
            if (method.getName().equals("getPlugin")
                    && papiPresent
                    && "PlaceholderAPI".equals(arguments[0])) {
                return plugin("PlaceholderAPI", false);
            }
            return defaultValue(method.getReturnType());
        });
        Server server = proxy(Server.class, (proxy, method, arguments) -> {
            if (method.getName().equals("getPluginManager")) {
                return pluginManager;
            }
            return defaultValue(method.getReturnType());
        });
        return proxy(Plugin.class, (proxy, method, arguments) -> {
            switch (method.getName()) {
                case "getName":
                    if (registrarFailure != null) {
                        throw registrarFailure;
                    }
                    return name;
                case "getDescription":
                    return description;
                case "getServer":
                    return server;
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
                PapiDetectionTest.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type.isPrimitive() && type != Void.TYPE) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
