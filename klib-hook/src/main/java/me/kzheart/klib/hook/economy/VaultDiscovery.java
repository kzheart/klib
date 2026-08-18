package me.kzheart.klib.hook.economy;

import java.util.Objects;
import me.kzheart.klib.hook.Hook;
import me.kzheart.klib.hook.Hooks;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/** 通过 Bukkit 发现 Vault 的 Economy 提供器，且不链接 Vault API。 */
public final class VaultDiscovery {

    private static final String ECONOMY_TYPE = "net.milkbowl.vault.economy.Economy";

    private VaultDiscovery() {
    }

    public static Hook<Currency> discover(Server server) {
        Objects.requireNonNull(server, "server");
        return Hooks.orNoop(
                "Vault",
                () -> findProvider(server),
                new NoopCurrency("vault"));
    }

    private static Currency findProvider(Server server) {
        Plugin vault = server.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            return null;
        }
        try {
            Class<?> economyType = Class.forName(
                    ECONOMY_TYPE,
                    false,
                    vault.getClass().getClassLoader());
            RegisteredServiceProvider<?> registration =
                    server.getServicesManager().getRegistration(economyType);
            if (registration == null || registration.getProvider() == null) {
                return null;
            }
            return new VaultCurrency(registration.getProvider());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Vault Economy API class is unavailable", failure);
        }
    }
}
