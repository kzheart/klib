package me.kzheart.klib.hook.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Proxy;
import me.kzheart.klib.hook.DependencyStatus;
import me.kzheart.klib.hook.Hook;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

class VaultDiscoveryTest {

    @Test
    void missingVaultPluginProducesAnExplicitNoopHook() {
        PluginManager pluginManager = proxy(PluginManager.class, null);
        Server server = proxy(Server.class, pluginManager);

        Hook<Currency> hook = VaultDiscovery.discover(server);

        assertFalse(hook.available());
        assertEquals(DependencyStatus.NOOP, hook.status());
        assertEquals(NoopCurrency.class, hook.value().getClass());
    }

    @Test
    void resolvesTheRegisteredVaultEconomyProviderThroughBukkitServices() {
        Plugin vault = proxy(Plugin.class, null, true);
        Economy economy = new Economy() {
            @Override
            public double getBalance(Player player) {
                return 19.5D;
            }

            @Override
            public String format(double amount) {
                return String.valueOf(amount);
            }
        };
        RegisteredServiceProvider<Economy> registration =
                new RegisteredServiceProvider<Economy>(
                        Economy.class,
                        economy,
                        ServicePriority.Normal,
                        vault);
        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                VaultDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {PluginManager.class},
                (instance, method, arguments) -> method.getName().equals("getPlugin")
                        ? vault
                        : defaultValue(method.getReturnType()));
        ServicesManager services = (ServicesManager) Proxy.newProxyInstance(
                VaultDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {ServicesManager.class},
                (instance, method, arguments) -> method.getName().equals("getRegistration")
                        ? registration
                        : defaultValue(method.getReturnType()));
        Server server = (Server) Proxy.newProxyInstance(
                VaultDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (instance, method, arguments) -> {
                    if (method.getName().equals("getPluginManager")) {
                        return pluginManager;
                    }
                    if (method.getName().equals("getServicesManager")) {
                        return services;
                    }
                    return defaultValue(method.getReturnType());
                });

        Hook<Currency> hook = VaultDiscovery.discover(server);

        assertEquals(DependencyStatus.AVAILABLE, hook.status());
        assertEquals(VaultCurrency.class, hook.value().getClass());
    }

    private static <T> T proxy(Class<T> type, Object pluginManager) {
        return proxy(type, pluginManager, false);
    }

    private static <T> T proxy(
            Class<T> type,
            Object pluginManager,
            boolean enabled
    ) {
        Object value = Proxy.newProxyInstance(
                VaultDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {type},
                (instance, method, arguments) -> {
                    if (method.getName().equals("getPluginManager")) {
                        return pluginManager;
                    }
                    if (method.getName().equals("isEnabled")) {
                        return Boolean.valueOf(enabled);
                    }
                    if (method.getName().equals("getName")) {
                        return "Vault";
                    }
                    return defaultValue(method.getReturnType());
                });
        return type.cast(value);
    }

    private static Object defaultValue(Class<?> type) {
        if (type.equals(Boolean.TYPE)) {
            return Boolean.FALSE;
        }
        if (type.equals(Character.TYPE)) {
            return Character.valueOf('\0');
        }
        if (type.equals(Byte.TYPE)) {
            return Byte.valueOf((byte) 0);
        }
        if (type.equals(Short.TYPE)) {
            return Short.valueOf((short) 0);
        }
        if (type.equals(Integer.TYPE)) {
            return Integer.valueOf(0);
        }
        if (type.equals(Long.TYPE)) {
            return Long.valueOf(0L);
        }
        if (type.equals(Float.TYPE)) {
            return Float.valueOf(0F);
        }
        if (type.equals(Double.TYPE)) {
            return Double.valueOf(0D);
        }
        return null;
    }
}
