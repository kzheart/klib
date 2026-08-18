package me.kzheart.klib.hook.economy;

import me.kzheart.klib.hook.Hook;
import me.kzheart.klib.hook.Hooks;
import org.bukkit.Server;

/** 内置经济适配器的可选依赖工厂。 */
public final class CurrencyHooks {

    private CurrencyHooks() {
    }

    public static Hook<Currency> vault(Object service) {
        return Hooks.orNoop(
                "Vault",
                () -> service == null ? null : new VaultCurrency(service),
                new NoopCurrency("vault"));
    }

    public static Hook<Currency> vault(Server server) {
        return VaultDiscovery.discover(server);
    }

    public static Hook<Currency> playerPoints(Object api) {
        return Hooks.orNoop(
                "PlayerPoints",
                () -> api == null ? null : new PlayerPointsCurrency(api),
                new NoopCurrency("playerpoints"));
    }

    public static Hook<Currency> xConomy(Object api) {
        return Hooks.orNoop(
                "XConomy",
                () -> api == null ? null : new XConomyCurrency(api),
                new NoopCurrency("xconomy"));
    }
}
